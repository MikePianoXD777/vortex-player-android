package com.vortexine.player.motor

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.MediaStore

/** Una canción que está en el teléfono (bajada con Vortex Player o de cualquier otro lado). */
data class Pista(
    val uri: String,
    val titulo: String,
    val artista: String,
    val album: String,
    val duracionMs: Long,
    val bytes: Long,
    val portada: String = "",      // URL de YouTube Music o content:// de la carátula del álbum
    val videoId: String = "",      // si se bajó de YouTube: para no repetirla y para recomendar
    val codec: String = "",
    val bits: Int = 0,
    val hz: Int = 0,
    val kbps: Int = 0,
) {
    val etiqueta get() = if (codec.isEmpty()) "" else Calidad.etiqueta(codec, bits, hz, kbps)
    val nivel get() = Calidad.nivel(codec, bits, hz)
}

data class Playlist(val id: Long, val nombre: String, val cuantas: Int)

/**
 * La biblioteca: toda la música del teléfono (la que ve Android) más lo que sabe Vortex Player de las que
 * bajó (título, artista y portada de YouTube Music) y tus playlists. Se guarda en SQLite.
 */
class Biblioteca(private val ctx: Context) : SQLiteOpenHelper(ctx, "vortex-player.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bajadas (video_id TEXT PRIMARY KEY, uri TEXT, titulo TEXT, artistas TEXT, album TEXT, portada TEXT)")
        db.execSQL("CREATE TABLE calidad (uri TEXT PRIMARY KEY, bytes INTEGER, codec TEXT, bits INTEGER, hz INTEGER, kbps INTEGER)")
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, nombre TEXT)")
        db.execSQL("CREATE TABLE en_playlist (playlist INTEGER, uri TEXT, pos INTEGER, PRIMARY KEY (playlist, uri))")
    }

    override fun onUpgrade(db: SQLiteDatabase, viejo: Int, nuevo: Int) {}

    // --- la música del teléfono ----------------------------------------------------------------------

    /** Todas las canciones que ve Android, con su calidad (se lee una vez y se recuerda). */
    fun pistas(): List<Pista> {
        val bajadas = mutableMapOf<String, Array<String>>()
        readableDatabase.rawQuery("SELECT uri, video_id, titulo, artistas, album, portada FROM bajadas", null).use { c ->
            // Por el número de MediaStore: Android 10+ devuelve «external_primary/…» y la lista dice «external/…»
            while (c.moveToNext()) bajadas[numero(c.getString(0))] = Array(5) { c.getString(it + 1) ?: "" }
        }
        val calidades = mutableMapOf<String, Calidad.Datos>()
        val tamanos = mutableMapOf<String, Long>()
        readableDatabase.rawQuery("SELECT uri, bytes, codec, bits, hz, kbps FROM calidad", null).use { c ->
            while (c.moveToNext()) {
                calidades[c.getString(0)] = Calidad.Datos(c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5))
                tamanos[c.getString(0)] = c.getLong(1)
            }
        }
        val salida = mutableListOf<Pista>()
        val col = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ALBUM_ID)
        val consulta = runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, col,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 20000", null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC")
        }.getOrNull() ?: return emptyList()
        val nuevas = mutableListOf<Pair<String, Calidad.Datos>>()
        consulta.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                val bytes = c.getLong(5)
                val dur = c.getLong(4)
                var q = calidades[uri]?.takeIf { tamanos[uri] == bytes }
                if (q == null) {
                    q = Calidad.leer(ctx, Uri.parse(uri), bytes, dur) ?: Calidad.Datos("", 0, 0, 0)
                    nuevas += uri to q
                }
                val b = bajadas[c.getLong(0).toString()]
                val caratula = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(6)).toString()
                salida += Pista(
                    uri = uri,
                    titulo = b?.get(1)?.ifEmpty { null } ?: (c.getString(1) ?: "").replace(Regex("\\s*\\[[\\w-]{11}]$"), ""),
                    artista = b?.get(2)?.ifEmpty { null } ?: (c.getString(2)?.takeUnless { it == "<unknown>" } ?: ""),
                    album = b?.get(3)?.ifEmpty { null } ?: (c.getString(3) ?: ""),
                    duracionMs = dur, bytes = bytes,
                    portada = b?.get(4)?.ifEmpty { null } ?: caratula,
                    videoId = b?.get(0) ?: "",
                    codec = q.codec, bits = q.bits, hz = q.hz, kbps = q.kbps,
                )
            }
        }
        if (nuevas.isNotEmpty()) writableDatabase.run {
            beginTransaction()
            try {
                for ((uri, q) in nuevas) insertWithOnConflict("calidad", null, ContentValues().apply {
                    put("uri", uri); put("bytes", salida.first { it.uri == uri }.bytes)
                    put("codec", q.codec); put("bits", q.bits); put("hz", q.hz); put("kbps", q.kbps)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                setTransactionSuccessful()
            } finally { endTransaction() }
        }
        return salida
    }

    private fun numero(uri: String) = uri.substringAfterLast('/')

    /** Lo que ya bajaste de YouTube (por su id), para marcarlo «En tu biblioteca». */
    fun idsBajados(): Set<String> = readableDatabase.rawQuery("SELECT video_id FROM bajadas", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun recordarBajada(c: Cancion, uri: String) {
        writableDatabase.insertWithOnConflict("bajadas", null, ContentValues().apply {
            put("video_id", c.id); put("uri", uri); put("titulo", c.titulo); put("artistas", c.artistas)
            put("album", c.album); put("portada", c.portada)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** La URI con la que la biblioteca conoce a esa canción (para meterla a una playlist). */
    fun uriDeBajada(videoId: String): String? =
        readableDatabase.rawQuery("SELECT uri FROM bajadas WHERE video_id = ?", arrayOf(videoId)).use { c ->
            if (c.moveToFirst()) normal(c.getString(0)) else null
        }

    /** content://media/external/audio/media/N, como las da `pistas()`. */
    fun normal(uri: String): String = numero(uri).toLongOrNull()
        ?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it).toString() } ?: uri

    /** Quita del teléfono una canción bajada con Vortex Player (las demás no se tocan). */
    fun borrar(p: Pista): Boolean {
        val ok = runCatching { ctx.contentResolver.delete(Uri.parse(p.uri), null, null) > 0 }.getOrDefault(false)
        if (ok) writableDatabase.run {
            delete("bajadas", "uri LIKE ?", arrayOf("%/" + numero(p.uri)))
            delete("en_playlist", "uri = ?", arrayOf(p.uri))
            delete("calidad", "uri = ?", arrayOf(p.uri))
        }
        return ok
    }

    // --- playlists -----------------------------------------------------------------------------------

    fun playlists(): List<Playlist> = readableDatabase.rawQuery(
        "SELECT p.id, p.nombre, COUNT(e.uri) FROM playlists p LEFT JOIN en_playlist e ON e.playlist = p.id GROUP BY p.id ORDER BY p.nombre COLLATE NOCASE",
        null).use { c -> buildList { while (c.moveToNext()) add(Playlist(c.getLong(0), c.getString(1), c.getInt(2))) } }

    fun nuevaPlaylist(nombre: String): Long =
        writableDatabase.insert("playlists", null, ContentValues().apply { put("nombre", nombre.trim().take(60)) })

    fun renombrarPlaylist(id: Long, nombre: String) {
        writableDatabase.update("playlists", ContentValues().apply { put("nombre", nombre.trim().take(60)) }, "id = ?", arrayOf(id.toString()))
    }

    fun borrarPlaylist(id: Long) {
        writableDatabase.delete("en_playlist", "playlist = ?", arrayOf(id.toString()))
        writableDatabase.delete("playlists", "id = ?", arrayOf(id.toString()))
    }

    fun urisDe(playlist: Long): List<String> = readableDatabase.rawQuery(
        "SELECT uri FROM en_playlist WHERE playlist = ? ORDER BY pos", arrayOf(playlist.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun agregarA(playlist: Long, uri: String) {
        val pos = readableDatabase.rawQuery("SELECT COALESCE(MAX(pos), -1) + 1 FROM en_playlist WHERE playlist = ?",
            arrayOf(playlist.toString())).use { c -> c.moveToFirst(); c.getInt(0) }
        writableDatabase.insertWithOnConflict("en_playlist", null, ContentValues().apply {
            put("playlist", playlist); put("uri", uri); put("pos", pos)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun quitarDe(playlist: Long, uri: String) {
        writableDatabase.delete("en_playlist", "playlist = ? AND uri = ?", arrayOf(playlist.toString(), uri))
    }
}
