package com.vortexine.player

import com.vortexine.player.motor.Letras
import com.vortexine.player.reproductor.Ecualizador
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Los mismos casos que `test_letras.py` de la PC. */
class LetrasTest {
    @Test fun limpiaLosTitulosDeYoutube() {
        assertEquals("アイドル", Letras.limpiar("YOASOBI - アイドル (Official Music Video)", "YOASOBI"))
        assertEquals("Ethereal (Slowed)", Letras.limpiar("Ethereal (Slowed)", "Txmy"))
        assertEquals("Idol", Letras.limpiar("Idol [Lyrics] ft. Someone", ""))
        assertEquals("Sweetly", Letras.limpiar("Sweetly", "Flavio Belardo"))
        assertEquals("YOASOBI", Letras.limpiarArtista("YOASOBI - Topic"))
        assertEquals("Kenshi Yonezu", Letras.limpiarArtista("Kenshi Yonezu, Hikaru Utada"))
    }

    @Test fun leeLrc() {
        val l = Letras.leerLrc("[ar:x]\n[00:01.50] Hola\n[00:03.2]Mundo\n[01:00.00][01:30.00] Coro\n")
        assertEquals(listOf(1500L, 3200L, 60000L, 90000L), l.map { it.ms })
        assertEquals(listOf("Hola", "Mundo", "Coro", "Coro"), l.map { it.texto })
        assertEquals(-1, Letras.actual(l, 0))
        assertEquals(1, Letras.actual(l, 3000))   // 300 ms antes ya se marca
        assertEquals(3, Letras.actual(l, 99999))
    }

    @Test fun eligeLaQueDuraLoMismo() {
        val r = JSONArray()
            .put(JSONObject().put("duration", 100.0).put("plainLyrics", "lejos"))
            .put(JSONObject().put("duration", 181.0).put("plainLyrics", "sin sincronizar"))
            .put(JSONObject().put("duration", 179.0).put("syncedLyrics", "[00:01.00] sí"))
        assertEquals("[00:01.00] sí", Letras.elegir(r, 180)!!.getString("syncedLyrics"))
        assertNull(Letras.elegir(JSONArray().put(JSONObject().put("duration", 100.0).put("plainLyrics", "x")), 180))
    }

    @Test fun losPerfilesSeAcomodanALasBandas() {
        val graves = Ecualizador.PERFILES.first { it.nombre == "Graves" }
        assertEquals(6f, Ecualizador.dbEn(graves, 20), 0.01f)
        assertEquals(6f, Ecualizador.dbEn(graves, 62), 0.01f)
        assertEquals(5.5f, Ecualizador.dbEn(graves, 88), 0.1f)   // a medio camino (log) entre 62 y 125
        assertEquals(0f, Ecualizador.dbEn(graves, 14000), 0.01f)
        Ecualizador.PERFILES.forEach { assertEquals(it.nombre, 10, it.db.size) }
    }
}
