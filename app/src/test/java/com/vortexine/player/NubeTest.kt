package com.vortexine.player

import com.vortexine.player.motor.Actualizador
import com.vortexine.player.motor.Nube
import com.vortexine.player.motor.Nube.CancionNube
import com.vortexine.player.motor.Nube.PlaylistNube
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los mismos casos que `test_nube.py` de la PC: las dos apps tienen que mezclar igual. */
class NubeTest {
    private fun c(id: String, t: Long) = CancionNube(id, "T$id", "A", "", 200, t)
    private fun local(canciones: List<CancionNube> = emptyList(), quitadas: Map<String, Long> = emptyMap(),
                      playlists: List<PlaylistNube> = emptyList(), borradas: Map<String, Long> = emptyMap()) =
        Nube.Local(canciones.associateBy { it.id }, quitadas, playlists.associateBy { it.uuid }, borradas)

    @Test fun juntaLasCancionesDeLosDos() {
        val pc = Nube.desde(local(listOf(c("aaaaaaaaaaa", 10), c("bbbbbbbbbbb", 20))))
        val tel = Nube.desde(local(listOf(c("ccccccccccc", 30))))
        val m = Nube.mezclar(pc, tel)
        assertEquals(setOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), Nube.canciones(m).map { it.id }.toSet())
        assertTrue(Nube.iguales(m, Nube.mezclar(tel, pc)))   // da lo mismo el orden
    }

    @Test fun loQuitadoSeQuedaQuitadoSalvoQueSeVuelvaABajar() {
        val nube = Nube.desde(local(listOf(c("aaaaaaaaaaa", 10), c("bbbbbbbbbbb", 10))))
        val tel = Nube.desde(local(quitadas = mapOf("aaaaaaaaaaa" to 50L, "bbbbbbbbbbb" to 50L)))
        val otra = Nube.desde(local(listOf(c("bbbbbbbbbbb", 90))))   // en la PC la volvieron a bajar después
        val m = Nube.mezclar(Nube.mezclar(nube, tel), otra)
        assertEquals(listOf("bbbbbbbbbbb"), Nube.canciones(m).map { it.id })
        assertEquals(50L, m.getJSONObject("quitadas").getLong("aaaaaaaaaaa"))
        assertFalse(m.getJSONObject("quitadas").has("bbbbbbbbbbb"))
    }

    @Test fun deLaPlaylistGanaLaUltimaQueCambio() {
        val vieja = PlaylistNube("u1", "Anime", "", listOf("aaaaaaaaaaa"), 10)
        val nueva = PlaylistNube("u1", "Anime 2026", "", listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), 20)
        val m = Nube.mezclar(Nube.desde(local(playlists = listOf(nueva))), Nube.desde(local(playlists = listOf(vieja))))
        assertEquals(listOf(nueva), Nube.playlists(m))
        // borrada después del último cambio: se va; cambiada después de borrarla: se queda
        val borrada = Nube.mezclar(m, Nube.desde(local(borradas = mapOf("u1" to 30L))))
        assertEquals(emptyList<PlaylistNube>(), Nube.playlists(borrada))
        val revive = Nube.mezclar(borrada, Nube.desde(local(playlists = listOf(nueva.copy(modificada = 40)))))
        assertEquals(1, Nube.playlists(revive).size)
    }

    @Test fun elPlanBajaLoQueFaltaYQuitaLoBorradoEnOtroLado() {
        val doc = Nube.mezclar(Nube.desde(local(listOf(c("aaaaaaaaaaa", 10), c("ccccccccccc", 10)),
            playlists = listOf(PlaylistNube("u1", "Chill", "https://youtube.com/playlist?list=PL1", listOf("aaaaaaaaaaa"), 10)))),
            Nube.desde(local(quitadas = mapOf("bbbbbbbbbbb" to 50L), borradas = mapOf("u2" to 50L))))
        val aqui = local(listOf(c("aaaaaaaaaaa", 5), c("bbbbbbbbbbb", 40)),
            playlists = listOf(PlaylistNube("u2", "Vieja", "", emptyList(), 30)))
        val plan = Nube.plan(doc, aqui)
        assertEquals(listOf("ccccccccccc"), plan.bajar.map { it.id })
        assertEquals(listOf("bbbbbbbbbbb"), plan.borrar)
        assertEquals(listOf("u1"), plan.playlists.map { it.uuid })
        assertEquals(listOf("u2"), plan.quitarPlaylists)
        // lo que cambiaste aquí después no se pisa
        val cambiada = local(playlists = listOf(PlaylistNube("u1", "Mía", "", emptyList(), 99)))
        assertEquals(emptyList<PlaylistNube>(), Nube.plan(doc, cambiada).playlists)
    }

    @Test fun leeLoQueEscribeLaPc() {
        // Así lo guarda nube.py (la duración puede venir con decimales y faltar claves)
        val pc = """{"formato": 1, "canciones": {"aaaaaaaaaaa": {"titulo": "Idol", "artista": "YOASOBI", "album": "",
            "duracion": 213, "agregada": 1793472533000}}, "playlists": {"u1": {"nombre": "Anime", "url": "",
            "canciones": ["aaaaaaaaaaa"], "modificada": 1793472533000}}}"""
        val doc = Nube.leer(pc)
        assertEquals("YOASOBI", Nube.canciones(doc).single().artista)
        assertEquals(listOf("aaaaaaaaaaa"), Nube.playlists(doc).single().canciones)
        assertTrue(doc.getJSONObject("quitadas").length() == 0)
        assertTrue(Nube.iguales(Nube.leer("basura"), Nube.vacio()))
    }

    @Test fun versiones() {
        assertTrue(Actualizador.masNueva("1.10.0", "1.9.2"))
        assertTrue(Actualizador.masNueva("v1.1.0", "1.0.0"))
        assertFalse(Actualizador.masNueva("1.1.0", "1.1.0"))
        assertFalse(Actualizador.masNueva("1.0.9", "1.1"))
        val v = Actualizador.leer(JSONObject().put("tag_name", "v1.2.0").put("body", "Notas")
            .put("assets", org.json.JSONArray().put(JSONObject().put("browser_download_url", "https://x/VortexPlayer-1.2.0.apk"))).toString())
        assertEquals(Actualizador.Version("1.2.0", "https://x/VortexPlayer-1.2.0.apk", "Notas"), v)
    }
}
