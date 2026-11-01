package com.vortexine.player.reproductor

import android.content.Context
import androidx.media3.common.MediaItem
import com.vortexine.player.motor.Biblioteca
import com.vortexine.player.motor.Cancion
import com.vortexine.player.motor.Pista
import com.vortexine.player.motor.YtMusic
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.5: «Tu mezcla del día», como en la PC: lo que más escuchas (30 días) revuelto con 15 parecidas que no
 * tienes (suenan de YouTube Music sin bajarlas). Se arma una vez al día y se guarda para el despertador.
 */
object Mezcla {
    data class Datos(val fecha: String = "", val tuyas: List<String> = emptyList(), val nuevas: List<Cancion> = emptyList())

    fun hoy(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mezcla", Context.MODE_PRIVATE)

    fun leer(ctx: Context): Datos = runCatching {
        val o = JSONObject(prefs(ctx).getString("datos", "{}") ?: "{}")
        val t = o.optJSONArray("tuyas") ?: JSONArray()
        val n = o.optJSONArray("nuevas") ?: JSONArray()
        Datos(o.optString("fecha"), (0 until t.length()).map { t.getString(it) }, (0 until n.length()).map { n.getJSONObject(it).let { c ->
            Cancion(c.getString("id"), c.optString("titulo"), c.optString("artistas"), c.optString("album"), c.optInt("duracion"), c.optString("portada"))
        } })
    }.getOrDefault(Datos())

    fun guardar(ctx: Context, d: Datos) {
        val o = JSONObject().put("fecha", d.fecha).put("tuyas", JSONArray(d.tuyas)).put("nuevas", JSONArray(d.nuevas.map {
            JSONObject().put("id", it.id).put("titulo", it.titulo).put("artistas", it.artistas).put("album", it.album)
                .put("duracion", it.duracion).put("portada", it.portada)
        }))
        prefs(ctx).edit().putString("datos", o.toString()).apply()
    }

    /** Con internet: tus 15 más escuchadas (o al azar si todavía no hay) y 15 nuevas parecidas a 4 de ellas. */
    fun armar(bib: Biblioteca, pistas: List<Pista>): Datos {
        val porUri = pistas.associateBy { it.uri }
        val tuyas = bib.masEscuchadas(System.currentTimeMillis() - 30 * 86_400_000L, 30).mapNotNull { porUri[it] }.take(15).toMutableList()
        if (tuyas.size < 15) tuyas += (pistas - tuyas.toSet()).shuffled().take(15 - tuyas.size)
        if (tuyas.isEmpty()) return Datos()
        val semillas = tuyas.take(10).shuffled().take(4).map { YtMusic.Semilla(it.videoId, it.titulo, it.artista) }
        val nuevas = YtMusic.recomendar(semillas, pistas.map { YtMusic.Semilla(it.videoId, it.titulo, it.artista) }, 15)
        return Datos(hoy(), tuyas.map { it.uri }.shuffled(), nuevas)
    }

    /** Una tuya, una nueva… (las tuyas que ya borraste no van). */
    fun items(d: Datos, pistas: List<Pista>): List<MediaItem> {
        val porUri = pistas.associateBy { it.uri }
        val tuyas = d.tuyas.mapNotNull { porUri[it] }
        return (0 until maxOf(tuyas.size, d.nuevas.size)).flatMap { k ->
            listOfNotNull(tuyas.getOrNull(k)?.let(Radio::itemPista), d.nuevas.getOrNull(k)?.let(Radio::item))
        }
    }

    /** Las primeras 4 portadas, para el mosaico de la tarjeta. */
    fun portadas(d: Datos, pistas: List<Pista>): List<String> {
        val porUri = pistas.associateBy { it.uri }
        val tuyas = d.tuyas.mapNotNull { porUri[it]?.portada }
        return (0 until maxOf(tuyas.size, d.nuevas.size)).flatMap { listOfNotNull(tuyas.getOrNull(it), d.nuevas.getOrNull(it)?.portada) }
            .filter { it.isNotEmpty() }.take(4)
    }
}
