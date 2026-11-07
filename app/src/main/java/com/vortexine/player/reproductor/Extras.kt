package com.vortexine.player.reproductor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1.3: el temporizador para dormir. Vive con el reproductor (no con la pantalla): funciona con el teléfono
 * bloqueado. Los últimos 10 s baja el volumen poco a poco y luego pausa. «Al terminar la canción» usa
 * `pauseAtEndOfMediaItems` de ExoPlayer.
 */
object Temporizador {
    /** `hasta`: SystemClock.elapsedRealtime() en que pausa (0 = no); `alFinal`: al terminar la canción. */
    data class Estado(val hasta: Long = 0, val alFinal: Boolean = false) {
        val activo get() = hasta > 0 || alFinal
    }

    val estado = MutableStateFlow(Estado())
    private val mano = Handler(Looper.getMainLooper())
    private var bajando = false

    /** Pausa en `minutos`; -1: al terminar la canción; 0: apagado. Desde el hilo principal. */
    fun poner(minutos: Int) {
        val j = Jugador.actual
        mano.removeCallbacks(revisar)
        if (bajando) j?.volume = 1f
        bajando = false
        j?.pauseAtEndOfMediaItems = minutos < 0
        estado.value = Estado(if (minutos > 0) SystemClock.elapsedRealtime() + minutos * 60_000L else 0, minutos < 0)
        if (minutos > 0) mano.post(revisar)
    }

    private val revisar = object : Runnable {
        override fun run() {
            val e = estado.value
            if (e.hasta == 0L) return
            val j = Jugador.actual
            val falta = e.hasta - SystemClock.elapsedRealtime()
            when {
                falta <= 0 -> { j?.pause(); j?.volume = 1f; bajando = false; estado.value = Estado(); return }
                falta <= 10_000 && j?.isPlaying == true -> { bajando = true; j.volume = falta / 10_000f }
            }
            mano.postDelayed(this, if (falta <= 10_000) 100 else 1000)
        }
    }

    /** El reproductor pausó al terminar la canción: el temporizador ya cumplió. */
    fun terminoCancion() {
        if (!estado.value.alFinal) return
        Jugador.actual?.pauseAtEndOfMediaItems = false
        estado.value = Estado()
    }
}

/**
 * 1.3: crossfade. A `segundos` del final, la canción que termina sigue sonando en otro ExoPlayer (en la misma
 * sesión de audio, para que le toque el ecualizador) mientras se apaga, y la siguiente entra subiendo en el
 * reproductor de siempre. El otro no pide el foco de audio: no le quita nada a la llamada ni a la pausa.
 *
 * 1.6.1: el otro reproductor tarda en abrir el archivo. Si se apaga la canción antes de que suene, hay un hueco y
 * se oye cortada. Por eso arranca mudo unos segundos antes, se empareja con la canción y el relevo es cuando ya
 * suena, con un cruce de 120 ms; la que sale no baja hasta que la siguiente ya está sonando.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Fundido(private val ctx: Context, private val j: ExoPlayer, private val sesionAudio: Int) {
    private val mano = Handler(Looper.getMainLooper())
    private var cola: ExoPlayer? = null
    private var cargada = 0L

    // Mientras la cola suena muda, esperando el relevo
    private var emparejando = false
    private var idCola = ""
    private var ajustes = 0
    private var adelanto = 0L
    private var sonandoDesde = 0L
    private var sumaDiferencia = 0L
    private var muestras = 0
    private var alineada = false
    private var relevando = false

    private val escucha = object : Player.Listener {
        override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { cargada = SystemClock.elapsedRealtime() }
        override fun onIsPlayingChanged(tocando: Boolean) {
            mano.removeCallbacks(revisar)   // el salto del cruce la llama a media vuelta: sin esto quedan dos ciclos
            if (tocando) mano.post(revisar) else if (!j.playWhenReady) soltarCola()
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) Temporizador.terminoCancion()
        }
    }

    fun empezar() { j.addListener(escucha) }

    fun soltar() {
        mano.removeCallbacksAndMessages(null)
        j.removeListener(escucha)
        soltarCola()
    }

    private val revisar = object : Runnable {
        override fun run() {
            if (!j.isPlaying) return
            val n = segundos * 1000L
            val dur = j.duration
            val falta = dur - j.currentPosition
            val puede = n > 0 && dur != C.TIME_UNSET && dur > n * 3 && falta in FALTA_MINIMA..(n + PRECARGA)
                && j.hasNextMediaItem() && j.repeatMode != Player.REPEAT_MODE_ONE && !j.pauseAtEndOfMediaItems
                && SystemClock.elapsedRealtime() - cargada > 2000 && !deLaRadio()
            when {
                relevando -> if (j.currentMediaItem?.mediaId != idCola) soltarCola()
                !emparejando -> if (puede) arrancarMuda()
                // Saltaste, adelantaste o ya no alcanza: se suelta y la canción sigue normal, sin cruce
                !puede || j.currentMediaItem?.mediaId != idCola -> soltarCola()
                else -> emparejar(falta, n)
            }
            mano.postDelayed(this, if (emparejando || relevando) 50 else 200)
        }
    }

    /** 1.4: la de YouTube tarda en abrir: con una de la radio (la que sale o la que entra) no hay crossfade. */
    private fun deLaRadio(): Boolean = Radio.idDe(j.currentMediaItem?.mediaId.orEmpty()) != null ||
        j.nextMediaItemIndex.let { it != C.INDEX_UNSET && Radio.idDe(j.getMediaItemAt(it).mediaId) != null }

    private fun arrancarMuda() {
        val item = j.currentMediaItem ?: return
        soltarCola()
        cola = ExoPlayer.Builder(ctx).build().apply {
            setAudioSessionId(sesionAudio)
            setMediaItem(item, j.currentPosition + 150)
            volume = 0f
            prepare()
            play()
        }
        emparejando = true
        idCola = item.mediaId
        ajustes = 0
        adelanto = 0
        reiniciarMedida()
    }

    private fun reiniciarMedida() {
        sonandoDesde = 0
        sumaDiferencia = 0
        muestras = 0
        alineada = false
    }

    private fun emparejar(falta: Long, n: Long) {
        val muda = cola ?: return
        val ahora = SystemClock.elapsedRealtime()
        if (!muda.isPlaying) { reiniciarMedida(); return }
        if (alineada) {
            if (falta <= n) cruzar(muda, falta)
            return
        }
        if (sonandoDesde == 0L) sonandoDesde = ahora
        if (ahora - sonandoDesde < ASENTARSE) return   // recién arranca: su posición todavía no es confiable
        // La posición avanza a saltitos: una sola lectura puede errar por cientos de ms, así que se promedia
        sumaDiferencia += j.currentPosition - muda.currentPosition
        muestras++
        if (ahora - sonandoDesde < ASENTARSE + VENTANA) return
        val diferencia = sumaDiferencia / muestras
        if (abs(diferencia) > TOLERANCIA && ajustes < MAX_AJUSTES) {
            // Cada salto tarda lo mismo en volver a sonar: lo que faltó esta vez se adelanta la siguiente
            adelanto += diferencia
            muda.seekTo(muda.currentPosition + diferencia + adelanto)
            ajustes++
            reiniciarMedida()
            return
        }
        alineada = true
    }

    /** La misma canción pasa de un reproductor al otro con un cruce cortito, para tapar lo que no se alcanzó a emparejar. */
    private fun cruzar(sale: ExoPlayer, falta: Long) {
        emparejando = false
        relevando = true
        val base = j.volume
        val desde = SystemClock.elapsedRealtime()
        mano.post(object : Runnable {
            override fun run() {
                if (cola !== sale) return
                val t = ((SystemClock.elapsedRealtime() - desde).toFloat() / RELEVO).coerceIn(0f, 1f)
                val curva = curvaDe(t)
                sale.volume = base * curva.second
                j.volume = base * curva.first
                if (t < 1f) mano.postDelayed(this, 15) else pasarALaSiguiente(sale, base, falta - RELEVO)
            }
        })
    }

    private fun pasarALaSiguiente(sale: ExoPlayer, base: Float, falta: Long) {
        relevando = false
        sale.volume = base
        j.volume = 0f
        j.seekToNextMediaItem()
        val salto = SystemClock.elapsedRealtime()
        var inicio = 0L
        var dura = 0L
        mano.post(object : Runnable {
            override fun run() {
                if (cola !== sale) return
                val ahora = SystemClock.elapsedRealtime()
                if (inicio == 0L) {
                    // La que sale sigue completa hasta que la siguiente ya suena
                    if (!j.isPlaying && ahora - salto < ESPERA_MAXIMA) { mano.postDelayed(this, 30); return }
                    inicio = ahora
                    dura = (falta - (ahora - salto)).coerceAtLeast(500)
                }
                val t = ((ahora - inicio).toFloat() / dura).coerceIn(0f, 1f)
                val curva = curvaDe(t)
                sale.volume = base * curva.first
                j.volume = curva.second
                if (t < 1f) mano.postDelayed(this, 50) else soltarCola()
            }
        })
    }

    private fun soltarCola() {
        emparejando = false
        relevando = false
        cola?.release()
        cola = null
        if (j.volume < 1f && !j.pauseAtEndOfMediaItems && Temporizador.estado.value.hasta == 0L) j.volume = 1f
    }

    companion object {
        private const val PRECARGA = 6000L        // cuánto antes del cruce arranca la cola, muda
        private const val FALTA_MINIMA = 800L     // con menos ya no alcanza: pasa sin cruce
        private const val ASENTARSE = 200L
        private const val VENTANA = 600L          // cuánto se promedia la diferencia antes de corregirla
        private const val TOLERANCIA = 40L        // ms de diferencia que el cruce del relevo tapa sin que se note
        private const val MAX_AJUSTES = 5
        private const val RELEVO = 120L           // lo que dura el cruce cortito entre los dos reproductores
        private const val ESPERA_MAXIMA = 1500L   // si la siguiente tarda más, el cruce empieza igual

        /**
         * Volumen de la que sale y de la que entra en el punto `t` (0 a 1). Con potencia constante: en línea
         * recta, a la mitad del cruce el sonido baja 3 dB y se nota un hueco.
         */
        fun curvaDe(t: Float): Pair<Float, Float> {
            val a = t.coerceIn(0f, 1f) * (PI.toFloat() / 2f)
            return cos(a) to sin(a)
        }

        /** Segundos de crossfade (0 = sin); se guarda en las preferencias «ajustes». */
        @Volatile var segundos = 5

        fun cargar(ctx: Context) { segundos = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getInt("fundido", 5) }

        fun poner(ctx: Context, s: Int) {
            segundos = s
            ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putInt("fundido", s).apply()
        }
    }
}
