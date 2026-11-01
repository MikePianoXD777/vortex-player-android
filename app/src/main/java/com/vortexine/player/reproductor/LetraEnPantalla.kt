package com.vortexine.player.reproductor

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingSimpleBasePlayer
import com.google.common.util.concurrent.ListenableFuture
import com.vortexine.player.VortexApp
import com.vortexine.player.motor.Letras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 1.5: la letra en la pantalla bloqueada (y en la notificación, el reloj y el coche): mientras suena una
 * canción con letra sincronizada, el renglón de abajo del título es la línea que se está cantando.
 * Es el reproductor que ve la sesión: el de verdad, con el artista cambiado por la línea.
 */
@UnstableApi
class LetraEnPantalla(private val ctx: Context, private val j: Player) : ForwardingSimpleBasePlayer(j) {
    private var letra: Letras.Letra? = null
    private var de = ""          // la uri de la canción de esa letra
    private var linea = ""
    private val h = Handler(Looper.getMainLooper())
    private val reloj = object : Runnable {
        override fun run() {
            val l = letra
            if (l != null && l.sincronizada && j.isPlaying) {
                val pos = j.currentPosition
                val i = l.lineas.indexOfLast { it.ms <= pos }
                val nueva = if (i < 0) "" else l.lineas[i].texto.ifBlank { "♪" }
                if (nueva != linea) { linea = nueva; invalidateState() }
            }
            h.postDelayed(this, 400)
        }
    }

    init {
        j.addListener(object : Player.Listener {
            override fun onMediaItemTransition(m: androidx.media3.common.MediaItem?, razon: Int) = cargar()
        })
        cargar()
        h.post(reloj)
    }

    private fun cargar() {
        val item = j.currentMediaItem
        val uri = item?.mediaId.orEmpty()
        if (uri == de) return
        de = uri
        letra = null
        if (linea.isNotEmpty()) { linea = ""; invalidateState() }
        if (item == null) return
        val md = item.mediaMetadata
        val segundos = ((md.durationMs ?: j.duration.coerceAtLeast(0)) / 1000).toInt()
        val app = ctx.applicationContext as VortexApp
        app.alcance.launch {
            val l = runCatching {
                Letras.de(File(ctx.filesDir, "letras"), md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), segundos,
                    md.extras?.getString("id") ?: Radio.idDe(uri).orEmpty())
            }.getOrNull()
            withContext(Dispatchers.Main) { if (de == uri) letra = l }
        }
    }

    override fun getState(): State {
        val s = super.getState()
        val i = s.currentMediaItemIndex
        if (linea.isEmpty() || i < 0 || i >= s.playlist.size) return s
        val dato = s.playlist[i]
        val md = (dato.mediaMetadata ?: dato.mediaItem.mediaMetadata).buildUpon()
            .setArtist(linea).setSubtitle(linea).build()
        val lista = s.playlist.toMutableList()
        lista[i] = dato.buildUpon().setMediaMetadata(md).build()
        return s.buildUpon().setPlaylist(lista).build()
    }

    fun soltar() { h.removeCallbacksAndMessages(null) }
}
