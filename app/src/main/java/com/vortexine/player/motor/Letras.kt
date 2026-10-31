package com.vortexine.player.motor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Las letras, de LRCLIB (lrclib.net: gratis, sin cuenta ni llave). Casi siempre vienen sincronizadas (LRC),
 * así que se van marcando al ritmo de la canción como en Spotify. Si LRCLIB no la tiene (o está caído), la de
 * YouTube Music (de Musixmatch, sin tiempos). Se guardan en el teléfono: la segunda vez salen sin internet.
 * El mismo orden de búsqueda que `letras.py` de la PC.
 */
object Letras {
    data class Linea(val ms: Long, val texto: String)
    data class Letra(val lineas: List<Linea>, val sincronizada: Boolean)

    private const val API = "https://lrclib.net/api"
    private const val AGENTE = "Vortex Player (https://github.com/MikePianoXD777/vortex-os)"
    private const val REINTENTAR_MS = 7L * 24 * 3600 * 1000   // si no había letra, se vuelve a buscar en una semana
    private const val MEJORAR_MS = 3L * 24 * 3600 * 1000      // una sin tiempos: se busca la sincronizada cada 3 días

    private val BASURA = Regex(
        "\\s*[(\\[【「]([^)\\]】」]*?(official|oficial|video|vídeo|audio|lyric|letra|visuali[sz]er|mv\\b|m/v|hd|4k|hq|" +
            "remaster|en vivo|live|explicit|clean|color coded|sub|español|english)[^)\\]】」]*)[)\\]】」]",
        RegexOption.IGNORE_CASE)
    private val PARENTESIS = Regex("\\s*[(\\[【「][^)\\]】」]*[)\\]】」]")

    /** «YOASOBI - アイドル (Official Music Video)» → «アイドル»; quita lo que no es parte del título. */
    fun limpiar(titulo: String, artista: String = ""): String {
        var t = BASURA.replace(titulo, "").trim()
        val guion = t.indexOf(" - ")
        if (guion > 0) {
            val izq = t.substring(0, guion).trim()
            val a = limpiarArtista(artista)
            if (a.isEmpty() || izq.equals(a, true) || a.contains(izq, true) || izq.contains(a, true)) t = t.substring(guion + 3).trim()
        }
        t = t.replace(Regex("\\s+(ft\\.?|feat\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE), "")
        return t.trim(' ', '-', '|', '"').ifEmpty { titulo.trim() }
    }

    fun limpiarArtista(artista: String): String = artista.split(",", " & ", " x ", " feat", " ft.")[0]
        .replace(Regex("\\s*-\\s*Topic$|VEVO$|\\s+Official$", RegexOption.IGNORE_CASE), "").trim()

    /** «[01:23.45] texto» → (83450 ms, «texto»). Varias marcas en una línea dan varias líneas. */
    fun leerLrc(lrc: String): List<Linea> {
        val marca = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        val salida = mutableListOf<Linea>()
        for (fila in lrc.lines()) {
            val marcas = marca.findAll(fila).toList()
            if (marcas.isEmpty()) continue
            val texto = fila.substring(marcas.last().range.last + 1).trim()
            for (m in marcas) {
                val (mi, se, fr) = m.destructured
                val frac = if (fr.isEmpty()) 0 else fr.padEnd(3, '0').take(3).toInt()
                salida += Linea(mi.toLong() * 60_000 + se.toLong() * 1000 + frac, texto)
            }
        }
        return salida.sortedBy { it.ms }
    }

    fun texto(plano: String): List<Linea> = plano.lines().map { Linea(-1, it.trim()) }

    /** De los resultados de LRCLIB, el que dura lo mismo (±4 s), mejor si viene sincronizado. */
    fun elegir(resultados: JSONArray, segundos: Int, holgura: Int = 4): JSONObject? {
        val todos = (0 until resultados.length()).map { resultados.getJSONObject(it) }
            .filter { !it.optString("syncedLyrics").isNullOrBlank() || !it.optString("plainLyrics").isNullOrBlank() }
        val cerca = if (segundos <= 0) todos else todos.filter { Math.abs(it.optDouble("duration", 0.0) - segundos) <= holgura }
        return cerca.firstOrNull { it.optString("syncedLyrics").isNotBlank() && it.optString("syncedLyrics") != "null" } ?: cerca.firstOrNull()
    }

    fun deJson(o: JSONObject): Letra? {
        val sinc = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plano = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return when {
            sinc != null -> leerLrc(sinc).takeIf { it.isNotEmpty() }?.let { Letra(it, true) }
            plano != null -> Letra(texto(plano), false)
            else -> null
        }
    }

    private fun pedir(url: String): String? {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", AGENTE).build()
        Red.cliente.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw java.io.IOException("LRCLIB contestó ${r.code}")
            return r.body.string()
        }
    }

    /** Busca en internet: primero exacto (título, artista y duración) y luego más suelto. */
    fun buscar(titulo: String, artista: String, segundos: Int): Letra? {
        val t = limpiar(titulo, artista)
        val a = limpiarArtista(artista)
        val c = YtMusic::codificar
        if (a.isNotEmpty() && segundos > 0) {
            pedir("$API/get?track_name=${c(t)}&artist_name=${c(a)}&duration=$segundos")?.let { deJson(JSONObject(it))?.let { l -> return l } }
        }
        val corto = PARENTESIS.replace(t, "").trim().ifEmpty { t }
        val intentos = buildList {
            if (a.isNotEmpty()) add("$API/search?track_name=${c(corto)}&artist_name=${c(a)}")
            add("$API/search?q=${c("$a $corto".trim())}")
            if (corto != t) add("$API/search?q=${c("$a $t".trim())}")
        }
        for (url in intentos) {
            val r = pedir(url) ?: continue
            elegir(JSONArray(r), segundos)?.let { o -> deJson(o)?.let { return it } }
        }
        return null
    }

    private fun archivo(dir: File, titulo: String, artista: String, segundos: Int): File {
        val h = MessageDigest.getInstance("SHA-1").digest("$titulo|$artista|$segundos".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        return File(dir, "$h.json")
    }

    /**
     * La letra guardada, o la busca (LRCLIB y luego YouTube Music) y la guarda. null si no hay (y no se vuelve
     * a buscar en una semana). Sin internet lanza IOException y no anota nada.
     */
    fun de(dir: File, titulo: String, artista: String, segundos: Int, videoId: String = "",
           deYoutube: (String) -> String? = YtMusic::letra): Letra? {
        val f = archivo(dir, titulo, artista, segundos)
        val ahora = System.currentTimeMillis()
        var guardada: Letra? = null
        runCatching {
            if (f.exists()) {
                val o = JSONObject(f.readText())
                if (o.optBoolean("nada")) {
                    if (ahora - o.optLong("cuando") < REINTENTAR_MS) return null
                } else {
                    val l = o.getJSONArray("lineas")
                    guardada = Letra((0 until l.length()).map { l.getJSONArray(it).let { x -> Linea(x.getLong(0), x.getString(1)) } },
                        o.getBoolean("sincronizada"))
                    if (guardada?.sincronizada == true || ahora - o.optLong("cuando") < MEJORAR_MS) return guardada
                }
            }
        }
        var falla: Exception? = null
        var letra = try { buscar(titulo, artista, segundos) } catch (e: Exception) { falla = e; null }
        if (letra == null && videoId.isNotEmpty()) {
            letra = try { deYoutube(videoId)?.let { Letra(texto(it), false) } } catch (e: Exception) { falla = falla ?: e; null }
        }
        if (letra == null) {
            guardada?.let { return it }
            falla?.let { throw it }   // sin internet (o LRCLIB caído sin respaldo): no se anota «nada»
        }
        dir.mkdirs()
        f.writeText(if (letra == null) JSONObject().put("nada", true).put("cuando", ahora).toString()
            else JSONObject().put("sincronizada", letra.sincronizada).put("cuando", ahora)
                .put("lineas", JSONArray(letra.lineas.map { JSONArray().put(it.ms).put(it.texto) })).toString())
        return letra
    }

    /** La línea que va sonando (-1 antes de la primera). */
    fun actual(lineas: List<Linea>, ms: Long): Int {
        var i = -1
        for ((k, l) in lineas.withIndex()) if (l.ms <= ms + 300) i = k else break
        return i
    }
}
