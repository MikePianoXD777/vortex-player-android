package com.vortexine.player.reproductor

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vortexine.player.interfaz.MainActivity

/**
 * El reproductor vive aquí, no en la pantalla: sigue sonando con el teléfono bloqueado, y Android pone
 * solo los controles en la notificación, la pantalla bloqueada, el reloj y el coche.
 */
class ServicioReproduccion : MediaSessionService() {
    private var sesion: MediaSession? = null
    private var fundido: Fundido? = null
    private var escuchas: Escuchas? = null
    private var letraEnPantalla: LetraEnPantalla? = null
    private var subirSonando: SubirSonando? = null

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val jugador = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)   // pausa si entra una llamada
            .setHandleAudioBecomingNoisy(true)                                // pausa si se desconectan los audífonos
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMediaSourceFactory(Radio.fuentes(this))                      // 1.4: las de la radio suenan de YouTube
            .build()
        // Una sesión de audio propia desde el principio: si no, el ecualizador cae en la salida general del teléfono
        val sesionAudio = (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
        jugador.setAudioSessionId(sesionAudio)
        Jugador.actual = jugador
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // 1.5: la sesión ve el reproductor con la línea de la letra en lugar del artista (pantalla bloqueada)
        letraEnPantalla = LetraEnPantalla(this, jugador)
        sesion = MediaSession.Builder(this, letraEnPantalla!!).setSessionActivity(abrir).build()
        Ecualizador.conectar(sesionAudio)
        // 1.3: crossfade y el widget de la pantalla de inicio
        Fundido.cargar(this)
        fundido = Fundido(this, jugador, sesionAudio).also { it.empezar() }
        // 1.4: la radio sin fin y lo que cuenta para «Tu resumen»
        jugador.addListener(RadioSinFin(this, jugador))
        escuchas = Escuchas(this, jugador).also { jugador.addListener(it) }
        subirSonando = SubirSonando(this, jugador).also { jugador.addListener(it) }   // 1.5: seguir en la PC
        jugador.addListener(object : androidx.media3.common.Player.Listener {
            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                if (events.containsAny(androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION,
                        androidx.media3.common.Player.EVENT_IS_PLAYING_CHANGED, androidx.media3.common.Player.EVENT_MEDIA_METADATA_CHANGED))
                    Widget.pintar(this@ServicioReproduccion, player)
            }
        })
        Widget.pintar(this, jugador)
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = sesion

    /** 1.5: la alarma del despertador arranca el servicio con DESPERTAR. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Despertador.DESPERTAR) {
            Jugador.actual?.let { Despertador.sonar(this, it) }
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val j = sesion?.player
        if (j == null || !j.playWhenReady || j.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        escuchas?.soltar()
        subirSonando?.run { subir(); soltar() }
        letraEnPantalla?.soltar()
        fundido?.soltar()
        Temporizador.poner(0)
        Ecualizador.soltar()
        sesion?.run {
            Jugador.actual?.release()
            release()
        }
        sesion = null
        Jugador.actual = null
        Widget.pintar(this, null)
        super.onDestroy()
    }
}

/** El ExoPlayer del servicio, para lo que no pasa por los controles normales (el ecualizador). */
object Jugador {
    @Volatile var actual: ExoPlayer? = null
}
