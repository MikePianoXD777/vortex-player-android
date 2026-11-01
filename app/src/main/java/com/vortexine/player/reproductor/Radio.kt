package com.vortexine.player.reproductor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import com.vortexine.player.VortexApp
import com.vortexine.player.motor.Cancion
import com.vortexine.player.motor.Descargas
import com.vortexine.player.motor.YtMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.4: la radio sin fin. Cuando suena la última de la cola, se piden 10 parecidas a ella y a las dos de antes
 * (la radio de YouTube Music) y se agregan al final. Suenan sin bajarlas: la cola las lleva como
 * «ytradio://id» y el audio de YouTube se pide justo cuando le toca a cada una (los enlaces caducan).
 */
object Radio {
    private const val ESQUEMA = "ytradio"

    fun uriDe(id: String) = "$ESQUEMA://$id"

    /** El id de YouTube si es una de la radio (null si es tuya). */
    fun idDe(uri: String): String? = uri.takeIf { it.startsWith("$ESQUEMA://") }?.removePrefix("$ESQUEMA://")

    fun activa(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getBoolean("radio", true)

    fun poner(ctx: Context, activa: Boolean) =
        ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putBoolean("radio", activa).apply()

    fun item(c: Cancion): MediaItem = MediaItem.Builder().setMediaId(uriDe(c.id)).setUri(uriDe(c.id))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(c.titulo).setArtist(c.artistas).setAlbumTitle(c.album)
            .setArtworkUri(c.portada.takeIf { it.isNotEmpty() }?.let(Uri::parse))
            .setDurationMs(c.duracion * 1000L).setExtras(Bundle().apply { putString("id", c.id) }).build()).build()

    // El audio de cada una, recordado un rato (YouTube da enlaces que sirven unas horas)
    private val enlaces = ConcurrentHashMap<String, Pair<String, Long>>()

    /** Para el ExoPlayer del servicio: «ytradio://id» se cambia por el audio de YouTube al abrirla. */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun fuentes(ctx: Context) = DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(ctx)) { spec ->
        val id = idDe(spec.uri.toString())
        if (id == null) spec else {
            val guardado = enlaces[id]?.takeIf { SystemClock.elapsedRealtime() - it.second < 3 * 3600_000L }
            val url = guardado?.first ?: Descargas.mejorAudio(id).url.also { enlaces[id] = it to SystemClock.elapsedRealtime() }
            spec.withUri(Uri.parse(url))
        }
    })
}

/** Escucha al reproductor del servicio y le agrega la radio cuando se le acaba la cola. */
@androidx.annotation.OptIn(UnstableApi::class)
class RadioSinFin(private val ctx: Context, private val j: ExoPlayer) : Player.Listener {
    private var para = ""   // la última de la cola cuando se pidió: si cambia la cola, la respuesta ya no sirve
    private var vuelta = 0

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) revisar()
    }

    /** En el orden en que suenan (con aleatorio, el revuelto). */
    private fun orden(): List<Int> {
        val t = j.currentTimeline
        val salida = mutableListOf<Int>()
        var i = t.getFirstWindowIndex(j.shuffleModeEnabled)
        while (i != C.INDEX_UNSET) { salida += i; i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, j.shuffleModeEnabled) }
        return salida
    }

    private fun semilla(i: Int): YtMusic.Semilla {
        val it = j.getMediaItemAt(i)
        val md = it.mediaMetadata
        return YtMusic.Semilla(md.extras?.getString("id") ?: Radio.idDe(it.mediaId).orEmpty(), md.title?.toString().orEmpty(),
            md.artist?.toString().orEmpty())
    }

    fun revisar() {
        if (!Radio.activa(ctx) || j.mediaItemCount == 0 || j.repeatMode != Player.REPEAT_MODE_OFF || j.hasNextMediaItem()) return
        val ultima = j.getMediaItemAt(j.mediaItemCount - 1).mediaId
        if (para == ultima) return
        para = ultima
        val v = ++vuelta
        val orden = orden()
        val aqui = orden.indexOf(j.currentMediaItemIndex).coerceAtLeast(0)
        val semillas = orden.subList((aqui - 2).coerceAtLeast(0), aqui + 1).reversed().map(::semilla)
        val enCola = (0 until j.mediaItemCount).map(::semilla)
        val app = ctx.applicationContext as VortexApp
        app.alcance.launch {
            val r = runCatching {
                val tienes = app.biblioteca.pistas().map { YtMusic.Semilla(it.videoId, it.titulo, it.artista) } + enCola
                YtMusic.recomendar(semillas, tienes, 10)
            }.getOrNull()
            withContext(Dispatchers.Main) { fin(v, ultima, r.orEmpty()) }
        }
    }

    private fun fin(v: Int, ultima: String, canciones: List<Cancion>) {
        if (v != vuelta) return
        para = ""
        if (canciones.isEmpty() || j.mediaItemCount == 0 || j.getMediaItemAt(j.mediaItemCount - 1).mediaId != ultima
            || !Radio.activa(ctx)) return
        val acabada = j.playbackState == Player.STATE_ENDED
        val orden = orden()
        val desde = j.mediaItemCount
        j.addMediaItems(canciones.map(Radio::item))
        // con aleatorio, ExoPlayer las revolvería con lo que ya sonó: van al final, en orden
        if (j.shuffleModeEnabled) j.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(
            (orden + (desde until j.mediaItemCount)).toIntArray(), System.nanoTime()))
        if (acabada) { j.seekTo(desde, 0); j.play() }
    }
}

/**
 * 1.4: «Tu resumen». Cuenta cuánto suena de verdad cada canción (sin contar pausas ni saltos) y, al pasar a
 * otra, la anota si sonó 30 s o más (o la mitad, si es más corta).
 */
class Escuchas(private val ctx: Context, private val j: ExoPlayer) : Player.Listener {
    private var item: MediaItem? = null
    private var ms = 0L
    private var desde = 0L
    private var dur = 0L

    private fun ahora() = SystemClock.elapsedRealtime()

    override fun onIsPlayingChanged(tocando: Boolean) { if (tocando) desde = ahora() else sumar() }

    override fun onPlaybackStateChanged(estado: Int) {
        if (estado == Player.STATE_READY && j.currentMediaItem === item && j.duration != C.TIME_UNSET) dur = j.duration
    }

    override fun onMediaItemTransition(nuevo: MediaItem?, razon: Int) {
        sumar()
        cerrar()
        item = nuevo
        desde = if (j.isPlaying) ahora() else 0L
    }

    private fun sumar() {
        if (desde > 0) { ms += ahora() - desde; desde = if (j.isPlaying) ahora() else 0L }
    }

    fun cerrar() {
        val it = item ?: return
        val umbral = if (dur > 0) minOf(30_000L, dur / 2) else 30_000L
        val md = it.mediaMetadata
        val (m, uri) = ms to it.mediaId
        ms = 0L
        dur = 0L
        if (m < umbral || uri.isEmpty()) return
        val app = ctx.applicationContext as VortexApp
        app.alcance.launch {
            runCatching {
                app.biblioteca.anotarEscucha(uri, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(),
                    md.artworkUri?.toString().orEmpty(), m)
            }
        }
    }

    /** Al cerrar el servicio: lo que llevaba la que sonaba también cuenta. */
    fun soltar() { sumar(); cerrar(); item = null }
}
