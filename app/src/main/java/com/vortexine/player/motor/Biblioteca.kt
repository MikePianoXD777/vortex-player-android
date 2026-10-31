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
    val agregadaMs: Long = 0,      // cuándo llegó al teléfono (para la nube)
) {
    val etiqueta get() = if (codec.isEmpty()) "" else Calidad.etiqueta(codec, bits, hz, kbps)
    val nivel get() = Calidad.nivel(codec, bits, hz)
}

data class Playlist(val id: Long, val nombre: String, val cuantas: Int)

/**
 * La biblioteca: toda la música del teléfono (la que ve Android) más lo que sabe Vortex Player de las que
 * bajó (título, artista y portada de YouTube Music) y tus playlists. Se guarda en SQLite.
 */
class Biblioteca(private val ctx: Context) : SQLiteOpenHelper(ctx, "vortex-player.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bajadas (video_id TEXT PRIMARY KEY, uri TEXT, titulo TEXT, artistas TEXT, album TEXT, portada TEXT)")
        db.execSQL("CREATE TABLE calidad (uri TEXT PRIMARY KEY, bytes INTEGER, codec TEXT, bits INTEGER, hz INTEGER, kbps INTEGER)")
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, nombre TEXT)")
        db.execSQL("CREATE TABLE en_playlist (playlist INTEGER, uri TEXT, pos INTEGER, PRIMARY KEY (playlist, uri))")
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, viejo: Int, nuevo: Int) {
        if (viejo < 2) {
            // 1.1: la nube. Cada playlist lleva su id en la nube y cuándo cambió; lo que borras queda anotado
            db.execSQL("ALTER TABLE playlists ADD COLUMN nube TEXT")
            db.execSQL("ALTER TABLE playlists ADD COLUMN url TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE playlists ADD COLUMN modificada INTEGER DEFAULT 0")
            db.execSQL("CREATE TABLE borradas (tipo TEXT, clave TEXT, cuando INTEGER, PRIMARY KEY (tipo, clave))")
        }
    }

    private fun ahora() = System.currentTimeMillis()

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
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DISPLAY_NAME)
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
                    // Las que no bajó este teléfono (copiadas de la PC) traen el id en el nombre: «Título [id].opus»
                    videoId = b?.get(0) ?: listOf(c.getString(8), c.getString(1)).firstNotNullOfOrNull {
                        it?.let { n -> ID_EN_NOMBRE.find(n)?.groupValues?.get(1) } } ?: "",
                    codec = q.codec, bits = q.bits, hz = q.hz, kbps = q.kbps,
                    agregadaMs = c.getLong(7) * 1000,
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
        writableDatabase.delete("borradas", "tipo = 'cancion' AND clave = ?", arrayOf(c.id))
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

    /**
     * Quita del teléfono una canción bajada con Vortex Player (las demás no se tocan). Con `anotar`, queda
     * anotada para que también se quite de la nube y de la PC.
     */
    fun borrar(p: Pista, anotar: Boolean = true): Boolean {
        val ok = runCatching { ctx.contentResolver.delete(Uri.parse(p.uri), null, null) > 0 }.getOrDefault(false)
        if (ok && anotar && p.videoId.isNotEmpty()) anotarBorrada("cancion", p.videoId)
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

    fun nuevaPlaylist(nombre: String): Long = writableDatabase.insert("playlists", null, ContentValues().apply {
        put("nombre", nombre.trim().take(60)); put("modificada", ahora())
    })

    /** Marca la playlist como cambiada aquí (en la próxima sincronización gana esta versión). */
    private fun cambio(id: Long) {
        writableDatabase.execSQL("UPDATE playlists SET modificada = ? WHERE id = ?", arrayOf<Any>(ahora(), id))
    }

    fun renombrarPlaylist(id: Long, nombre: String) {
        writableDatabase.update("playlists", ContentValues().apply { put("nombre", nombre.trim().take(60)) }, "id = ?", arrayOf(id.toString()))
        cambio(id)
    }

    fun borrarPlaylist(id: Long, anotar: Boolean = true) {
        if (anotar) readableDatabase.rawQuery("SELECT nube FROM playlists WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) anotarBorrada("playlist", c.getString(0))
        }
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
        }, SQLiteDatabase.CONFLICT_IGNORE).let { if (it != -1L) cambio(playlist) }
    }

    fun quitarDe(playlist: Long, uri: String) {
        if (writableDatabase.delete("en_playlist", "playlist = ? AND uri = ?", arrayOf(playlist.toString(), uri)) > 0) cambio(playlist)
    }

    // --- la nube -------------------------------------------------------------------------------------

    private fun anotarBorrada(tipo: String, clave: String) {
        writableDatabase.insertWithOnConflict("borradas", null, ContentValues().apply {
            put("tipo", tipo); put("clave", clave); put("cuando", ahora())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun borradas(tipo: String): Map<String, Long> =
        readableDatabase.rawQuery("SELECT clave, cuando FROM borradas WHERE tipo = ?", arrayOf(tipo)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }

    /** Lo que hay aquí, como lo entiende la nube. A las playlists que todavía no tienen id en la nube se les da uno. */
    fun estadoNube(pistas: List<Pista>): Nube.Local {
        val idDeUri = pistas.filter { it.videoId.isNotEmpty() }.associate { it.uri to it.videoId }
        val canciones = pistas.filter { it.videoId.isNotEmpty() }.associate {
            it.videoId to Nube.CancionNube(it.videoId, it.titulo, it.artista, it.album, (it.duracionMs / 1000).toInt(),
                it.agregadaMs.takeIf { t -> t > 0 } ?: ahora())
        }
        val playlists = mutableMapOf<String, Nube.PlaylistNube>()
        readableDatabase.rawQuery("SELECT id, nombre, nube, url, modificada FROM playlists", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uuid = c.getString(2) ?: java.util.UUID.randomUUID().toString().also {
                    writableDatabase.execSQL("UPDATE playlists SET nube = ? WHERE id = ?", arrayOf<Any>(it, id))
                }
                playlists[uuid] = Nube.PlaylistNube(uuid, c.getString(1) ?: "", c.getString(3) ?: "",
                    urisDe(id).mapNotNull { idDeUri[it] }.distinct(), c.getLong(4))
            }
        }
        return Nube.Local(canciones, borradas("cancion"), playlists, borradas("playlist"))
    }

    /** Deja la playlist como viene de la nube; las canciones de aquí que no son de YouTube se quedan al final. */
    fun ponerPlaylist(p: Nube.PlaylistNube, uriDeId: Map<String, String>, idDeUri: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.rawQuery("SELECT id FROM playlists WHERE nube = ?", arrayOf(p.uuid)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                ?: db.insert("playlists", null, ContentValues().apply { put("nube", p.uuid) })
            val locales = urisDe(id).filter { it !in idDeUri }
            db.update("playlists", ContentValues().apply {
                put("nombre", p.nombre.take(60)); put("url", p.url); put("modificada", p.modificada)
            }, "id = ?", arrayOf(id.toString()))
            db.delete("en_playlist", "playlist = ?", arrayOf(id.toString()))
            (p.canciones.mapNotNull { uriDeId[it] } + locales).distinct().forEachIndexed { i, uri ->
                db.insert("en_playlist", null, ContentValues().apply { put("playlist", id); put("uri", uri); put("pos", i) })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun quitarPlaylistNube(uuid: String) {
        readableDatabase.rawQuery("SELECT id FROM playlists WHERE nube = ?", arrayOf(uuid)).use { c ->
            if (c.moveToFirst()) borrarPlaylist(c.getLong(0), anotar = false)
        }
    }

    companion object {
        val ID_EN_NOMBRE = Regex("\\[([\\w-]{11})](\\.\\w+)?$")
    }
}
