package com.vortexine.player.motor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Bajar de YouTube y YouTube Music el mejor audio que haya y guardarlo tal cual (Opus o AAC), sin
 * recomprimirlo, como en la PC. NewPipeExtractor (la librería de NewPipe) encuentra el audio; se baja en
 * trozos de 1 MB porque YouTube frena las descargas de un jalón. Se guarda en Música/Vortex Player.
 */
object Descargas {
    private const val TROZO = 1L shl 20

    data class Audio(val url: String, val extension: String, val mime: String, val kbps: Int)

    /** El mejor audio de una canción (Opus si hay; si no, AAC). */
    fun mejorAudio(videoId: String): Audio {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val opciones = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val mejor = opciones.maxWithOrNull(compareBy<AudioStream>({ it.averageBitrate }, { if (it.format?.suffix == "webm") 1 else 0 }))
            ?: throw IOException("YouTube no dio audio para esta canción")
        val ext = mejor.format?.suffix ?: "m4a"
        val opus = ext == "webm" || ext == "opus"
        // Opus viene en WebM, que es Matroska: Android no deja guardar «audio/webm» en Música, pero sí .mka
        return Audio(mejor.content, if (opus) "mka" else "m4a", if (opus) "audio/x-matroska" else "audio/mp4", mejor.averageBitrate)
    }

    /** Título, artista y portada de una canción suelta (para un enlace pegado). */
    fun datosDe(videoId: String): Cancion {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        return Cancion(videoId, info.name, info.uploaderName.orEmpty().removeSuffix(" - Topic"), "",
            info.duration.toInt(), info.thumbnails.maxByOrNull { it.width }?.url.orEmpty())
    }

    /** Las canciones de una playlist de YouTube (todas las páginas). */
    fun cancionesDePlaylist(listId: String): Pair<String, List<Cancion>> {
        val url = "https://www.youtube.com/playlist?list=$listId"
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val items = mutableListOf<StreamInfoItem>().apply { addAll(info.relatedItems) }
        var pagina = info.nextPage
        while (pagina != null && items.size < 1000) {
            val mas = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, pagina)
            items += mas.items
            pagina = mas.nextPage
        }
        return info.name to items.mapNotNull { s ->
            val id = Regex("[?&]v=([\\w-]{11})").find(s.url)?.groupValues?.get(1) ?: return@mapNotNull null
            Cancion(id, s.name, s.uploaderName.orEmpty().removeSuffix(" - Topic"), "", s.duration.toInt(),
                s.thumbnails.maxByOrNull { it.width }?.url.orEmpty())
        }
    }

    private fun limpiar(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(90)

    fun nombreArchivo(c: Cancion, ext: String): String {
        val base = if (c.artistas.isNotEmpty()) "${c.artistas.split(",")[0].trim()} - ${c.titulo}" else c.titulo
        return "${limpiar(base)} [${c.id}].$ext"
    }

    /** Baja la canción y la deja en Música/Vortex Player. Devuelve su content:// para la biblioteca. */
    fun bajar(ctx: Context, c: Cancion, progreso: (Float) -> Unit = {}, cancelada: () -> Boolean = { false }): String {
        val audio = mejorAudio(c.id)
        val d = destino(ctx, nombreArchivo(c, audio.extension), audio.mime, c)
        try {
            d.salida.use { copiar(audio.url, it, progreso, cancelada) }
        } catch (e: Exception) {
            d.terminar(false)
            throw e
        }
        return d.terminar(true)
    }

    private fun copiar(url: String, out: OutputStream, progreso: (Float) -> Unit, cancelada: () -> Boolean) {
        var hecho = 0L
        var total = -1L
        val buf = ByteArray(64 * 1024)
        while (total < 0 || hecho < total) {
            if (cancelada()) throw InterruptedException("Cancelada")
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", Red.AGENTE)
                .header("Range", "bytes=$hecho-${hecho + TROZO - 1}").build()
            Red.cliente.newCall(req).execute().use { r ->
                if (r.code !in listOf(200, 206)) throw IOException("YouTube contestó ${r.code} al bajar")
                if (total < 0) {
                    total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: r.body.contentLength().takeIf { it > 0 } ?: throw IOException("No sé cuánto mide el audio")
                }
                val entrada = r.body.byteStream()
                var leidos = 0L
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    leidos += n
                    hecho += n
                    progreso(hecho.toFloat() / total)
                }
                if (leidos == 0L) throw IOException("La descarga se cortó")
                if (r.code == 200) return   // el servidor mandó todo de un jalón
            }
        }
    }

    /** Dónde se escribe; `terminar(true)` la deja lista y devuelve su URI, `terminar(false)` la borra. */
    private class Destino(val salida: OutputStream, val terminar: (Boolean) -> String)

    private fun destino(ctx: Context, nombre: String, mime: String, c: Cancion): Destino {
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val valores = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, nombre)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Vortex Player")
                put(MediaStore.Audio.Media.TITLE, c.titulo)
                put(MediaStore.Audio.Media.ARTIST, c.artistas)
                put(MediaStore.Audio.Media.ALBUM, c.album)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), valores)
                ?: throw IOException("Android no dejó guardar la canción")
            val out = cr.openOutputStream(uri) ?: throw IOException("Android no dejó guardar la canción")
            return Destino(out) { ok ->
                if (ok) cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                else cr.delete(uri, null, null)
                uri.toString()
            }
        }
        // Android 7 a 9: archivo normal en Música/Vortex Player y se le avisa a Android que existe
        @Suppress("DEPRECATION")
        val carpeta = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Vortex Player")
        carpeta.mkdirs()
        val archivo = File(carpeta, nombre)
        return Destino(archivo.outputStream()) { ok ->
            if (!ok) {
                archivo.delete()
                ""
            } else {
                val valores = ContentValues().apply {
                    @Suppress("DEPRECATION") put(MediaStore.Audio.Media.DATA, archivo.absolutePath)
                    put(MediaStore.Audio.Media.TITLE, c.titulo); put(MediaStore.Audio.Media.ARTIST, c.artistas)
                    put(MediaStore.Audio.Media.ALBUM, c.album); put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
                (runCatching { cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, valores) }.getOrNull()
                    ?: Uri.fromFile(archivo)).toString()
            }
        }
    }
}
