package com.vortexine.player.motor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tu biblioteca en la nube: un JSON en la carpeta oculta de la app en tu Google Drive, el mismo que lee
 * Vortex Player en la PC (`nube.py`). Guarda las canciones de YouTube (por su id) y las playlists:
 *
 * ```
 * {"formato": 1,
 *  "canciones": {"<id>": {"titulo", "artista", "album", "duracion", "agregada"}},
 *  "quitadas":  {"<id>": cuándo},
 *  "playlists": {"<uuid>": {"nombre", "url", "canciones": ["<id>", …], "modificada"}},
 *  "borradas":  {"<uuid>": cuándo}}
 * ```
 * Los tiempos van en milisegundos. Mezclar dos copias nunca pierde nada: las canciones se juntan, una
 * canción quitada se queda quitada salvo que alguien la vuelva a bajar después, y de una playlist gana la
 * que se cambió al último.
 */
object Nube {
    const val FORMATO = 1

    data class CancionNube(val id: String, val titulo: String, val artista: String, val album: String,
                           val duracion: Int, val agregada: Long)

    data class PlaylistNube(val uuid: String, val nombre: String, val url: String, val canciones: List<String>,
                            val modificada: Long)

    /** Lo que hay en este teléfono, dicho como la nube lo entiende. */
    data class Local(val canciones: Map<String, CancionNube>, val quitadas: Map<String, Long>,
                     val playlists: Map<String, PlaylistNube>, val borradas: Map<String, Long>)

    /** Lo que hay que hacer aquí para quedar igual que la nube. */
    data class Plan(val bajar: List<CancionNube>, val borrar: List<String>, val playlists: List<PlaylistNube>,
                    val quitarPlaylists: List<String>)

    fun vacio() = JSONObject().put("formato", FORMATO).put("canciones", JSONObject()).put("quitadas", JSONObject())
        .put("playlists", JSONObject()).put("borradas", JSONObject())

    fun leer(texto: String?): JSONObject {
        val o = runCatching { JSONObject(texto ?: "") }.getOrNull() ?: return vacio()
        for (k in listOf("canciones", "quitadas", "playlists", "borradas")) if (o.optJSONObject(k) == null) o.put(k, JSONObject())
        return o.put("formato", FORMATO)
    }

    private fun claves(o: JSONObject) = o.keys().asSequence().toList()

    /** Junta dos copias (la de la nube y la de aquí). Da lo mismo el orden. */
    fun mezclar(a: JSONObject, b: JSONObject): JSONObject {
        val salida = vacio()
        val canciones = salida.getJSONObject("canciones")
        val quitadas = salida.getJSONObject("quitadas")
        val playlists = salida.getJSONObject("playlists")
        val borradas = salida.getJSONObject("borradas")
        for (doc in listOf(a, b)) {
            val cs = doc.optJSONObject("canciones") ?: JSONObject()
            for (id in claves(cs)) {
                val c = cs.getJSONObject(id)
                val previa = canciones.optJSONObject(id)
                if (previa == null || c.optLong("agregada") > previa.optLong("agregada")) canciones.put(id, JSONObject(c.toString()))
            }
            val qs = doc.optJSONObject("quitadas") ?: JSONObject()
            for (id in claves(qs)) quitadas.put(id, maxOf(qs.optLong(id), quitadas.optLong(id)))
            val ps = doc.optJSONObject("playlists") ?: JSONObject()
            for (u in claves(ps)) {
                val p = ps.getJSONObject(u)
                val previa = playlists.optJSONObject(u)
                if (previa == null || p.optLong("modificada") > previa.optLong("modificada")) playlists.put(u, JSONObject(p.toString()))
            }
            val bs = doc.optJSONObject("borradas") ?: JSONObject()
            for (u in claves(bs)) borradas.put(u, maxOf(bs.optLong(u), borradas.optLong(u)))
        }
        // Lo que se volvió a agregar después de quitarlo, revive; lo demás se queda quitado
        for (id in claves(quitadas)) {
            val c = canciones.optJSONObject(id) ?: continue
            if (c.optLong("agregada") > quitadas.optLong(id)) quitadas.remove(id) else canciones.remove(id)
        }
        for (u in claves(borradas)) {
            val p = playlists.optJSONObject(u) ?: continue
            if (p.optLong("modificada") > borradas.optLong(u)) borradas.remove(u) else playlists.remove(u)
        }
        return salida
    }

    fun desde(local: Local): JSONObject {
        val o = vacio()
        for (c in local.canciones.values) o.getJSONObject("canciones").put(c.id, JSONObject().put("titulo", c.titulo)
            .put("artista", c.artista).put("album", c.album).put("duracion", c.duracion).put("agregada", c.agregada))
        for ((id, t) in local.quitadas) o.getJSONObject("quitadas").put(id, t)
        for (p in local.playlists.values) o.getJSONObject("playlists").put(p.uuid, JSONObject().put("nombre", p.nombre)
            .put("url", p.url).put("canciones", JSONArray(p.canciones)).put("modificada", p.modificada))
        for ((u, t) in local.borradas) o.getJSONObject("borradas").put(u, t)
        return o
    }

    fun canciones(doc: JSONObject): List<CancionNube> {
        val cs = doc.optJSONObject("canciones") ?: return emptyList()
        return claves(cs).map { id ->
            val c = cs.getJSONObject(id)
            CancionNube(id, c.optString("titulo"), c.optString("artista"), c.optString("album"), c.optInt("duracion"), c.optLong("agregada"))
        }.sortedBy { it.agregada }
    }

    fun playlists(doc: JSONObject): List<PlaylistNube> {
        val ps = doc.optJSONObject("playlists") ?: return emptyList()
        return claves(ps).map { u ->
            val p = ps.getJSONObject(u)
            val ids = p.optJSONArray("canciones") ?: JSONArray()
            PlaylistNube(u, p.optString("nombre"), p.optString("url"), List(ids.length()) { ids.getString(it) }, p.optLong("modificada"))
        }
    }

    fun plan(doc: JSONObject, local: Local): Plan {
        val quitadas = doc.optJSONObject("quitadas") ?: JSONObject()
        val borradas = doc.optJSONObject("borradas") ?: JSONObject()
        return Plan(
            bajar = canciones(doc).filter { it.id !in local.canciones },
            borrar = claves(quitadas).filter { id -> local.canciones[id]?.let { it.agregada <= quitadas.optLong(id) } == true },
            playlists = playlists(doc).filter { p -> local.playlists[p.uuid].let { it == null || it.modificada <= p.modificada && it != p } },
            quitarPlaylists = claves(borradas).filter { u -> local.playlists[u]?.let { it.modificada <= borradas.optLong(u) } == true },
        )
    }

    /** Para no volver a subir lo mismo: misma información aunque las claves vengan en otro orden. */
    fun iguales(a: JSONObject, b: JSONObject) = normal(a) == normal(b)

    private fun normal(o: Any?): Any? = when (o) {
        is JSONObject -> claves(o).sorted().associateWith { normal(o.get(it)) }
        is JSONArray -> List(o.length()) { normal(o.get(it)) }
        is Int -> o.toLong()
        else -> o
    }
}
