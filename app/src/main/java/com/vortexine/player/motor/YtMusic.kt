package com.vortexine.player.motor

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Una canción encontrada en línea (YouTube Music, YouTube o Spotify). */
data class Cancion(
    val id: String,
    val titulo: String,
    val artistas: String = "",
    val album: String = "",
    val duracion: Int = 0,
    val portada: String = "",
) {
    val url get() = "https://music.youtube.com/watch?v=$id"
}

/**
 * Buscar, recomendar y leer enlaces, igual que `descargas.py` de Vortex Player en la PC.
 * YouTube Music por su API web (`youtubei/v1`), sin llave ni cuenta. Spotify por su página «embed»:
 * solo se leen los nombres y cada canción se busca en YouTube Music.
 */
object YtMusic {
    private const val CLIENTE = """{"clientName":"WEB_REMIX","clientVersion":"1.20251020.01.00","hl":"es","gl":"MX"}"""
    private const val SOLO_CANCIONES = "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBUQFQ=="   // el filtro «Canciones» de su página

    /** Para las pruebas: cambia cómo se piden las cosas a internet. */
    var pedir: (String, String) -> String = Red::pedirJson
    var bajarTexto: (String) -> String = Red::bajarTexto

    // --- enlaces -------------------------------------------------------------------------------------

    data class Enlace(val fuente: String, val clase: String, val id: String)

    /** («spotify» | «youtube», clase, id) o null si no es un enlace que se pueda bajar. */
    fun tipoDe(texto: String): Enlace? {
        val url = texto.trim()
        Regex("^spotify:(track|album|playlist):([A-Za-z0-9]+)$").find(url)?.let {
            return Enlace("spotify", it.groupValues[1], it.groupValues[2])
        }
        val u = runCatching { URI(if ("://" in url) url else "https://$url") }.getOrNull() ?: return null
        val host = (u.host ?: "").lowercase()
        val ruta = u.path ?: ""
        val q = (u.rawQuery ?: "").split("&").mapNotNull {
            val p = it.split("=", limit = 2); if (p.size == 2) p[0] to URLDecoder.decode(p[1], "UTF-8") else null
        }.toMap()
        if (host == "open.spotify.com") {
            val m = Regex("/(track|album|playlist)/([A-Za-z0-9]+)").find(ruta) ?: return null
            return Enlace("spotify", m.groupValues[1], m.groupValues[2])
        }
        if (host == "youtu.be") return Enlace("youtube", "cancion", ruta.trim('/'))
        if (host.endsWith("youtube.com")) {
            if (ruta == "/watch" && q["v"] != null) return Enlace("youtube", "cancion", q.getValue("v"))
            if (ruta == "/playlist" && q["list"] != null) return Enlace("youtube", "playlist", q.getValue("list"))
            Regex("^/(?:browse|channel)/([\\w-]+)").find(ruta)?.let { return Enlace("youtube", "playlist", it.groupValues[1]) }
            Regex("^/shorts/([\\w-]+)").find(ruta)?.let { return Enlace("youtube", "cancion", it.groupValues[1]) }
        }
        return null
    }

    // --- Spotify -------------------------------------------------------------------------------------

    data class PistaSpotify(val titulo: String, val artistas: String, val duracion: Int)

    /** (nombre, pistas) desde la página «embed» de Spotify. */
    fun leerSpotify(pagina: String): Pair<String, List<PistaSpotify>> {
        val m = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(pagina)
            ?: throw IllegalStateException("Spotify cambió su página: no encontré la lista de canciones")
        var crudo = m.groupValues[1]
        if (crudo.trimStart().startsWith("&")) crudo = android.text.Html.fromHtml(crudo).toString()
        val entidad = JSONObject(crudo).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        val nombre = entidad.optString("name").ifEmpty { entidad.optString("title") }.ifEmpty { "Spotify" }
        val pistas = mutableListOf<PistaSpotify>()
        val lista = entidad.optJSONArray("trackList") ?: JSONArray()
        for (i in 0 until lista.length()) {
            val t = lista.getJSONObject(i)
            pistas += PistaSpotify(t.optString("title"), t.optString("subtitle").replace(' ', ' '),
                Math.round(t.optLong("duration") / 1000.0).toInt())
        }
        if (pistas.isEmpty() && entidad.optString("type") == "track") {
            val arts = entidad.optJSONArray("artists") ?: JSONArray()
            val nombres = (0 until arts.length()).joinToString(", ") { arts.getJSONObject(it).optString("name") }
            pistas += PistaSpotify(nombre, nombres, Math.round(entidad.optLong("duration") / 1000.0).toInt())
        }
        return nombre to pistas.filter { it.titulo.isNotEmpty() }
    }

    fun pistasSpotify(clase: String, id: String) = leerSpotify(bajarTexto("https://open.spotify.com/embed/$clase/$id"))

    // --- buscar --------------------------------------------------------------------------------------

    private fun todos(o: Any?, clave: String, salida: MutableList<JSONObject> = mutableListOf()): MutableList<JSONObject> {
        when (o) {
            is JSONObject -> for (k in o.keys()) {
                val v = o.get(k)
                if (k == clave && v is JSONObject) salida += v
                todos(v, clave, salida)
            }
            is JSONArray -> for (i in 0 until o.length()) todos(o.get(i), clave, salida)
        }
        return salida
    }

    private fun runs(o: JSONObject?): String {
        val r = o?.optJSONArray("runs") ?: return ""
        return (0 until r.length()).joinToString("") { r.getJSONObject(it).optString("text") }
    }

    /** «3:34» o «1:02:03» → segundos (0 si no es una duración). */
    fun segundos(texto: String): Int {
        val t = texto.trim()
        if (!Regex("\\d{1,2}(:\\d{2}){1,2}").matches(t)) return 0
        return t.split(":").fold(0) { s, p -> s * 60 + p.toInt() }
    }

    private fun portadaGrande(fotos: JSONArray?): String {
        if (fotos == null || fotos.length() == 0) return ""
        // Vienen a 60 px: se piden a 226, el tamaño de su lista grande
        return fotos.getJSONObject(fotos.length() - 1).optString("url").replace(Regex("=w\\d+-h\\d+"), "=w226-h226")
    }

    /** Las canciones de una respuesta de búsqueda de YouTube Music. */
    fun leerBusqueda(respuesta: String): List<Cancion> {
        val canciones = mutableListOf<Cancion>()
        for (it in todos(JSONObject(respuesta), "musicResponsiveListItemRenderer")) {
            val vid = it.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            val cols = it.optJSONArray("flexColumns") ?: continue
            val columnas = (0 until cols.length()).map {
                runs(cols.getJSONObject(it).optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
            }
            if (vid.isEmpty() || columnas.isEmpty() || columnas[0].isEmpty()) continue
            // La segunda columna: «Artista • Álbum • 3:34» (con «Canción •» delante si no va filtrada)
            var datos = (columnas.getOrNull(1) ?: "").split("•").map { it.trim() }
                .filter { it.isNotEmpty() && it != "Canción" && it != "Song" }
            val dur = datos.lastOrNull()?.let { segundos(it) } ?: 0
            if (dur > 0) datos = datos.dropLast(1)
            val fotos = it.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            canciones += Cancion(vid, columnas[0], datos.getOrElse(0) { "" }, datos.getOrElse(1) { "" }, dur, portadaGrande(fotos))
        }
        return canciones
    }

    private fun contexto() = JSONObject().put("client", JSONObject(CLIENTE))

    /** Canciones de YouTube Music. Lanza IOException sin conexión. */
    fun buscar(texto: String, n: Int = 20): List<Cancion> {
        val q = texto.trim().split(Regex("\\s+")).joinToString(" ")
        if (q.isEmpty()) return emptyList()
        val cuerpo = JSONObject().put("context", contexto()).put("query", q).put("params", SOLO_CANCIONES)
        val r = pedir("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", cuerpo.toString())
        return leerBusqueda(r).take(n)
    }

    // --- recomendaciones -----------------------------------------------------------------------------

    /** Las canciones de la «radio» de YouTube Music (lo que su página pone en «A continuación»). */
    fun leerRadio(respuesta: String): List<Cancion> {
        val canciones = mutableListOf<Cancion>()
        for (it in todos(JSONObject(respuesta), "playlistPanelVideoRenderer")) {
            val vid = it.optString("videoId")
            val titulo = runs(it.optJSONObject("title"))
            if (vid.isEmpty() || titulo.isEmpty()) continue
            val datos = runs(it.optJSONObject("longBylineText")).split("•").map { it.trim() }
                .filter { it.isNotEmpty() && !Regex("\\d{4}").matches(it) }
            canciones += Cancion(vid, titulo, datos.getOrElse(0) { "" }, datos.getOrElse(1) { "" },
                segundos(runs(it.optJSONObject("lengthText"))),
                portadaGrande(it.optJSONObject("thumbnail")?.optJSONArray("thumbnails")))
        }
        return canciones
    }

    fun radio(videoId: String): List<Cancion> {
        val cuerpo = JSONObject().put("context", contexto()).put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId").put("isAudioOnly", true)
        val r = pedir("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", cuerpo.toString())
        return leerRadio(r).filter { it.id != videoId }
    }

    /** Para reconocer la misma canción aunque venga de otro lado: «idol (from oshi no ko)» ≈ «idol». */
    fun clave(titulo: String, artista: String): String {
        val t = titulo.lowercase().replace(Regex("[(\\[].*?[)\\]]|\\s-\\s.*$"), "")
        return t.replace(Regex("\\W+"), "") + "|" + artista.lowercase().split(",")[0].replace(Regex("\\W+"), "")
    }

    data class Semilla(val id: String, val titulo: String, val artista: String)

    /**
     * Recomendaciones para unas canciones de semilla: las radios de cada una, intercaladas, sin repetir y sin
     * lo que ya tienes. Las semillas sin id (tus archivos) se buscan primero en YouTube Music.
     */
    fun recomendar(semillas: List<Semilla>, tienes: List<Semilla>, n: Int = 20): List<Cancion> {
        val ids = mutableListOf<String>()
        for (s in semillas) {
            val vid = s.id.ifEmpty {
                runCatching { buscar("${s.artista} ${s.titulo}", 1).firstOrNull()?.id }.getOrNull().orEmpty()
            }
            if (vid.isNotEmpty() && vid !in ids) ids += vid
        }
        if (ids.isEmpty()) return emptyList()
        val radios = mutableListOf<List<Cancion>>()
        var error: Exception? = null
        for (vid in ids) runCatching { radios += radio(vid) }.onFailure { error = it as? Exception }
        if (radios.isEmpty()) throw error ?: java.io.IOException("Sin conexión con YouTube")
        val yaIds = tienes.map { it.id }.filter { it.isNotEmpty() }.toSet() + ids
        val yaClaves = tienes.map { clave(it.titulo, it.artista) }.toSet()
        val salida = mutableListOf<Cancion>()
        val vistas = mutableSetOf<String>()
        for (fila in 0 until radios.maxOf { it.size }) for (r in radios) {
            val c = r.getOrNull(fila) ?: continue
            val k = clave(c.titulo, c.artistas)
            if (c.id in yaIds || k in yaClaves || c.id in vistas || k in vistas) continue
            vistas += c.id; vistas += k
            salida += c
            if (salida.size >= n) return salida
        }
        return salida
    }

    fun busquedaDe(p: PistaSpotify) = "${p.artistas.split(",")[0].trim()} ${p.titulo}".trim()

    fun codificar(s: String): String = URLEncoder.encode(s, "UTF-8")
}
