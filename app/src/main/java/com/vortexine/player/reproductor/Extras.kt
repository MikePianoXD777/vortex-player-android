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
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Fundido(private val ctx: Context, private val j: ExoPlayer, private val sesionAudio: Int) {
    private val mano = Handler(Looper.getMainLooper())
    private var cola: ExoPlayer? = null
    private var cargada = 0L

    private val escucha = object : Player.Listener {
        override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { cargada = SystemClock.elapsedRealtime() }
        override fun onIsPlayingChanged(tocando: Boolean) {
            if (tocando) mano.post(revisar) else { mano.removeCallbacks(revisar); if (!j.playWhenReady) soltarCola() }
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
            if (n > 0 && dur != C.TIME_UNSET && dur > n * 3 && falta in 1..n && j.hasNextMediaItem()
                && j.repeatMode != Player.REPEAT_MODE_ONE && !j.pauseAtEndOfMediaItems
                && SystemClock.elapsedRealtime() - cargada > 2000) cruzar(falta)
            mano.postDelayed(this, 200)
        }
    }

    private fun cruzar(falta: Long) {
        val item = j.currentMediaItem ?: return
        soltarCola()
        val sale = ExoPlayer.Builder(ctx).build().apply {
            setAudioSessionId(sesionAudio)
            setMediaItem(item, j.currentPosition)
            volume = j.volume
            prepare()
            play()
        }
        cola = sale
        j.volume = 0f
        j.seekToNextMediaItem()
        val inicio = SystemClock.elapsedRealtime()
        val dura = falta.coerceAtLeast(500)
        mano.post(object : Runnable {
            override fun run() {
                val t = ((SystemClock.elapsedRealtime() - inicio).toFloat() / dura).coerceIn(0f, 1f)
                if (cola === sale) sale.volume = 1f - t
                j.volume = t
                if (t < 1f) mano.postDelayed(this, 50) else if (cola === sale) soltarCola()
            }
        })
    }

    private fun soltarCola() {
        cola?.release()
        cola = null
        if (j.volume < 1f && !j.pauseAtEndOfMediaItems && Temporizador.estado.value.hasta == 0L) j.volume = 1f
    }

    companion object {
        /** Segundos de crossfade (0 = sin); se guarda en las preferencias «ajustes». */
        @Volatile var segundos = 5

        fun cargar(ctx: Context) { segundos = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getInt("fundido", 5) }

        fun poner(ctx: Context, s: Int) {
            segundos = s
            ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putInt("fundido", s).apply()
        }
    }
}
