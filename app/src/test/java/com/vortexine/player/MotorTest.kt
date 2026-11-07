package com.vortexine.player

import com.vortexine.player.motor.Biblioteca
import com.vortexine.player.motor.Calidad
import com.vortexine.player.motor.Descargas
import com.vortexine.player.motor.Cancion
import com.vortexine.player.motor.YtMusic
import com.vortexine.player.reproductor.Radio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lo mismo que prueba `test_motor.py` de Vortex Player en la PC, para el motor del teléfono. */
class MotorTest {
    @Test fun semanaYRadio() {   // 1.4: «Tu resumen» va de lunes a lunes; la radio lleva el id en su uri
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
        val jueves = f.parse("2026-10-29 15:30")!!.time
        val (desde, hasta) = Biblioteca.semana(0, jueves)
        assertEquals("2026-10-26 00:00", f.format(java.util.Date(desde)))
        assertEquals("2026-11-02 00:00", f.format(java.util.Date(hasta)))
        assertEquals("2026-10-19 00:00", f.format(java.util.Date(Biblioteca.semana(1, jueves).first)))
        assertEquals("2026-10-26 00:00", f.format(java.util.Date(Biblioteca.semana(0, f.parse("2026-11-01 23:59")!!.time).first)))
        assertEquals("ZRtdQ81jPUQ", Radio.idDe(Radio.uriDe("ZRtdQ81jPUQ")))
        assertNull(Radio.idDe("content://media/external/audio/media/5"))
    }

    @Test fun enlaces() {
        assertEquals(YtMusic.Enlace("youtube", "cancion", "dQw4w9WgXcQ"), YtMusic.tipoDe("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=x"))
        assertEquals(YtMusic.Enlace("youtube", "cancion", "dQw4w9WgXcQ"), YtMusic.tipoDe("youtu.be/dQw4w9WgXcQ"))
        assertEquals(YtMusic.Enlace("youtube", "playlist", "PLabc"), YtMusic.tipoDe("https://www.youtube.com/playlist?list=PLabc"))
        assertEquals(YtMusic.Enlace("spotify", "album", "4aawyAB9vmqN3uQ7FjRGTy"),
            YtMusic.tipoDe("https://open.spotify.com/intl-es/album/4aawyAB9vmqN3uQ7FjRGTy?si=1"))
        assertEquals(YtMusic.Enlace("spotify", "track", "abc123"), YtMusic.tipoDe("spotify:track:abc123"))
        assertNull(YtMusic.tipoDe("idol yoasobi"))
    }

    @Test fun compartirPlaylist() {
        // 1.6: el mismo enlace que arma la PC, de 50 en 50, y se lee de vuelta con su nombre
        val (uno) = YtMusic.enlacesPlaylist("Para Ana & co", listOf("ZRtdQ81jPUQ", "", "dQw4w9WgXcQ", "ZRtdQ81jPUQ"))
        assertEquals("https://www.youtube.com/watch_videos?video_ids=ZRtdQ81jPUQ,dQw4w9WgXcQ&title=Para%20Ana%20%26%20co", uno)
        assertEquals(YtMusic.Enlace("youtube", "compartida", "Para Ana & co\nZRtdQ81jPUQ,dQw4w9WgXcQ"), YtMusic.tipoDe(uno))
        val muchos = YtMusic.enlacesPlaylist("Larga", (0 until 120).map { "%011d".format(it) })
        assertEquals(3, muchos.size)
        assertEquals("Larga (1/3)", YtMusic.tipoDe(muchos[0])!!.id.substringBefore("\n"))
        assertEquals(listOf(50, 50, 20), muchos.map { YtMusic.tipoDe(it)!!.id.substringAfter("\n").split(",").size })
        assertEquals(emptyList<String>(), YtMusic.enlacesPlaylist("Vacía", listOf("", "")))
    }

    @Test fun etiquetas() {
        assertEquals("Hi-Res 24/96", Calidad.etiqueta("flac", 24, 96000, 0))
        assertEquals("CD 16/44,1", Calidad.etiqueta("flac", 16, 44100, 0))
        assertEquals("Hi-Res 88,2 kHz", Calidad.etiqueta("alac", 0, 88200, 0))
        assertEquals("Opus 160 kbps", Calidad.etiqueta("opus", 0, 48000, 160))
        assertEquals("hires", Calidad.nivel("flac", 24, 48000))
        assertEquals("comprimida", Calidad.nivel("aac", 0, 44100))
        assertEquals("flac", Calidad.codecDeMime("audio/flac"))
        assertEquals("aac", Calidad.codecDeMime("audio/mp4a-latm"))
    }

    @Test fun duraciones_y_claves() {
        assertEquals(214, YtMusic.segundos("3:34"))
        assertEquals(3723, YtMusic.segundos("1:02:03"))
        assertEquals(0, YtMusic.segundos("2023"))
        assertEquals(YtMusic.clave("Idol", "YOASOBI"), YtMusic.clave("idol (from Oshi no Ko)", "YOASOBI, Ayase"))
    }

    @Test fun nombre_de_archivo_lleva_el_id() {
        assertEquals("YOASOBI - Idol [ZRtdQ81jPUQ].mka",
            Descargas.nombreArchivo(Cancion("ZRtdQ81jPUQ", "Idol", "YOASOBI, Ayase"), "mka"))
        assertEquals("AC DC - Back in Black [x].m4a", Descargas.nombreArchivo(Cancion("x", "Back in Black", "AC/DC"), "m4a"))
    }

    private val busqueda = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
        {"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{
          "playlistItemData":{"videoId":"ZRtdQ81jPUQ"},
          "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3/x=w60-h60-l90"},{"url":"https://lh3/x=w120-h120-l90"}]}}},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"アイドル"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"YOASOBI"},{"text":" • "},{"text":"アイドル"},{"text":" • "},{"text":"3:34"}]}}}
          ]}}]}}]}}}}]}}}"""

    @Test fun leer_busqueda_de_youtube_music() {
        val r = YtMusic.leerBusqueda(busqueda)
        assertEquals(1, r.size)
        assertEquals(Cancion("ZRtdQ81jPUQ", "アイドル", "YOASOBI", "アイドル", 214, "https://lh3/x=w226-h226-l90"), r[0])
    }

    @Test fun recomendaciones_intercaladas_sin_lo_que_tienes() {
        fun radio(vararg c: Pair<String, String>) = """{"contents":[${c.joinToString(",") { (id, t) ->
            """{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$t"}]},"longBylineText":{"runs":[{"text":"Art • Album • 2024"}]},"lengthText":{"runs":[{"text":"3:00"}]}}}"""
        }}]}"""
        val radios = mapOf("a" to radio("a" to "A", "x1" to "X1", "x2" to "X2"), "b" to radio("b" to "B", "y1" to "Y1", "x1" to "X1"))
        YtMusic.pedir = { _, cuerpo -> radios.getValue(org.json.JSONObject(cuerpo).getString("videoId")) }
        try {
            val r = YtMusic.recomendar(listOf(YtMusic.Semilla("a", "A", "Art"), YtMusic.Semilla("b", "B", "Art")),
                tienes = listOf(YtMusic.Semilla("", "X2", "Art")))
            assertEquals(listOf("x1", "y1"), r.map { it.id })
            assertEquals("Album", r[0].album)
            assertEquals(180, r[0].duracion)
        } finally {
            YtMusic.pedir = com.vortexine.player.motor.Red::pedirJson
        }
    }

    // 1.5: lo nuevo de tus artistas, la radio con datos móviles y el despertador
    @Test fun lanzamientosYDatosYDespertador() {
        fun item(id: String, titulo: String, sub: String) = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$titulo"}]},
            "subtitle":{"runs":[{"text":"$sub"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"$id"}},
            "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://x/a=w60-h60"}]}}}}}"""
        val pagina = """{"contents":[{"musicCarouselShelfRenderer":{"contents":[${item("MPREb_viejo", "Kyougen", "Álbum • 2022")},
            ${item("MPREb_nuevo", "Hello", "Single • 2026")},${item("MPREb_ep", "Ep", "EP • 2025")},${item("VLPLx", "Lista", "Playlist")}]}}]}"""
        val l = YtMusic.leerLanzamientos(pagina, "Ado")
        assertEquals(listOf("MPREb_nuevo" to "Single", "MPREb_ep" to "EP", "MPREb_viejo" to "Álbum"), l.map { it.id to it.tipo })
        assertEquals(2026, l[0].anio)
        val album = """{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"aaaaaaaaaaa"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Hello"}]}}}]}}]}"""
        assertEquals(listOf(Cancion("aaaaaaaaaaa", "Hello", "Ado", "Hello", 0, l[0].portada)), YtMusic.leerCancionesDe(album, l[0]))

        fun pista(uri: String, artista: String) = com.vortexine.player.motor.Pista(uri, uri, artista, "", 1000, 0)
        val todas = listOf(pista("0", "Ado"), pista("1", "Otro"), pista("2", "Ado - Topic"), pista("3", "Más"), pista("4", "Ado"))
        val r = Radio.local(listOf(YtMusic.Semilla("", "", "Ado")), setOf("0"), todas, 3)
        assertEquals(setOf("2", "4"), r.take(2).map { it.uri }.toSet())
        assertEquals(3, r.size)

        val c = java.util.Calendar.getInstance().apply { set(2026, 10, 1, 8, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val ahora = c.timeInMillis
        val a = com.vortexine.player.reproductor.Despertador.Ajuste(true, 7, 30)
        assertEquals(ahora + 23 * 3_600_000L + 30 * 60_000L, com.vortexine.player.reproductor.Despertador.proxima(a, ahora))
        assertEquals(ahora + 30 * 60_000L, com.vortexine.player.reproductor.Despertador.proxima(a.copy(hora = 8, minuto = 30), ahora))
        assertNull(com.vortexine.player.reproductor.Despertador.proxima(a.copy(activo = false), ahora))
    }
}

class CrossfadeTest {
    @org.junit.Test
    fun `el cruce no baja de volumen a la mitad`() {
        for (i in 0..100) {
            val (sale, entra) = com.vortexine.player.reproductor.Fundido.curvaDe(i / 100f)
            org.junit.Assert.assertEquals(1f, sale * sale + entra * entra, 0.001f)
        }
    }

    @org.junit.Test
    fun `empieza con la que sale y termina con la que entra`() {
        val (sale0, entra0) = com.vortexine.player.reproductor.Fundido.curvaDe(0f)
        val (sale1, entra1) = com.vortexine.player.reproductor.Fundido.curvaDe(1f)
        org.junit.Assert.assertEquals(1f, sale0, 0.001f)
        org.junit.Assert.assertEquals(0f, entra0, 0.001f)
        org.junit.Assert.assertEquals(0f, sale1, 0.001f)
        org.junit.Assert.assertEquals(1f, entra1, 0.001f)
    }
}
