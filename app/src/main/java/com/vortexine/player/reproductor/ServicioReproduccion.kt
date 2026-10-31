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

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val jugador = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)   // pausa si entra una llamada
            .setHandleAudioBecomingNoisy(true)                                // pausa si se desconectan los audífonos
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Una sesión de audio propia desde el principio: si no, el ecualizador cae en la salida general del teléfono
        val sesionAudio = (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
        jugador.setAudioSessionId(sesionAudio)
        Jugador.actual = jugador
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        sesion = MediaSession.Builder(this, jugador).setSessionActivity(abrir).build()
        Ecualizador.conectar(sesionAudio)
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = sesion

    override fun onTaskRemoved(rootIntent: Intent?) {
        val j = sesion?.player
        if (j == null || !j.playWhenReady || j.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        Ecualizador.soltar()
        sesion?.run {
            player.release()
            release()
        }
        sesion = null
        Jugador.actual = null
        super.onDestroy()
    }
}

/** El ExoPlayer del servicio, para lo que no pasa por los controles normales (el ecualizador). */
object Jugador {
    @Volatile var actual: ExoPlayer? = null
}
