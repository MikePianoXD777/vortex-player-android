package com.vortexine.player.reproductor

import android.content.Context
import android.media.audiofx.Equalizer
import kotlin.math.ln

/**
 * El ecualizador de Android sobre el audio de Vortex Player (cada teléfono trae sus bandas: casi siempre 5).
 * Lo que eliges se guarda y se vuelve a poner al abrir la app.
 *
 * 1.2: perfiles propios (los mismos que en la PC, `ecualizador.py`), en dB sobre 10 frecuencias; se pasan a
 * las bandas de cada teléfono interpolando en escala logarítmica.
 */
object Ecualizador {
    private var eq: Equalizer? = null
    private var preferencias: android.content.SharedPreferences? = null

    data class Banda(val hz: Int, val nivel: Int)
    data class Estado(val disponible: Boolean, val encendido: Boolean, val min: Int, val max: Int,
                      val bandas: List<Banda>, val perfiles: List<String>, val perfil: String)
    data class Perfil(val nombre: String, val db: List<Float>)

    val FRECUENCIAS = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val PERSONALIZADO = "Personalizado"

    private fun p(nombre: String, vararg db: Number) = Perfil(nombre, db.map { it.toFloat() })

    val PERFILES = listOf(
        p("Plano", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        p("Graves", 6, 6, 5, 3, 1, 0, 0, 0, 0, 0),
        p("Graves al máximo", 9, 8, 6, 3, 0, -1, 0, 0, 0, 0),
        p("Menos graves", -6, -5, -4, -2, 0, 0, 0, 0, 0, 0),
        p("Agudos", 0, 0, 0, 0, 0, 1, 3, 5, 6, 7),
        p("Voz", -2, -2, -1, 1, 3, 4, 4, 3, 1, 0),
        p("Pop", -1, 1, 3, 4, 3, 0, -1, -1, 1, 2),
        p("Rock", 5, 4, 3, 1, -1, -1, 1, 3, 4, 5),
        p("Metal", 4, 4, 2, 0, -2, 0, 2, 4, 5, 4),
        p("Electrónica", 6, 5, 2, 0, -2, 1, 0, 2, 4, 5),
        p("Hip hop", 6, 5, 3, 2, -1, -1, 1, 0, 2, 3),
        p("Reggaetón", 7, 6, 3, 0, -1, 0, 1, 2, 2, 2),
        p("J-pop y anime", 2, 2, 1, 0, 1, 2, 3, 4, 4, 3),
        p("Clásica", 4, 3, 2, 1, -1, -1, 0, 2, 3, 4),
        p("Jazz", 3, 2, 1, 2, -2, -2, 0, 1, 3, 4),
        p("Piano", 2, 1, 0, 2, 3, 1, 3, 4, 3, 2),
        p("Acústica", 4, 3, 2, 1, 1, 1, 2, 3, 3, 2),
        p("Lo-fi", 3, 3, 2, 1, 0, -1, -2, -3, -4, -5),
        p("Volumen bajo", 6, 5, 3, 0, -1, 0, 0, 2, 4, 5),
    )

    /** Los dB del perfil en `hz`, interpolando entre sus frecuencias en escala logarítmica. */
    fun dbEn(perfil: Perfil, hz: Int): Float {
        val x = ln(hz.coerceAtLeast(1).toDouble())
        val f = FRECUENCIAS.map { ln(it.toDouble()) }
        if (x <= f.first()) return perfil.db.first()
        if (x >= f.last()) return perfil.db.last()
        val i = f.indexOfLast { it <= x }
        val t = ((x - f[i]) / (f[i + 1] - f[i])).toFloat()
        return perfil.db[i] + (perfil.db[i + 1] - perfil.db[i]) * t
    }

    fun iniciar(ctx: Context) {
        preferencias = ctx.getSharedPreferences("ecualizador", Context.MODE_PRIVATE)
    }

    fun conectar(sesionAudio: Int) {
        soltar()
        eq = runCatching { Equalizer(0, sesionAudio) }
            .onFailure { android.util.Log.w("Ecualizador", "no se pudo abrir en la sesión $sesionAudio", it) }.getOrNull()
        val p = preferencias ?: return
        eq?.let { e ->
            for (b in 0 until e.numberOfBands) {
                val v = p.getInt("banda$b", Int.MIN_VALUE)
                if (v != Int.MIN_VALUE) runCatching { e.setBandLevel(b.toShort(), v.toShort()) }
            }
            runCatching { e.enabled = p.getBoolean("encendido", false) }
        }
    }

    fun soltar() {
        runCatching { eq?.release() }
        eq = null
    }

    fun estado(): Estado {
        val perfil = preferencias?.getString("perfil", null) ?: PERSONALIZADO
        val nombres = PERFILES.map { it.nombre }
        val e = eq ?: return Estado(false, false, -1500, 1500, emptyList(), nombres, perfil)
        return runCatching {
            val (min, max) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            Estado(true, e.enabled, min, max,
                (0 until e.numberOfBands).map { Banda(e.getCenterFreq(it.toShort()) / 1000, e.getBandLevel(it.toShort()).toInt()) },
                nombres, perfil)
        }.getOrDefault(Estado(false, false, -1500, 1500, emptyList(), nombres, perfil))
    }

    fun encender(si: Boolean) {
        runCatching { eq?.enabled = si }
        preferencias?.edit()?.putBoolean("encendido", si)?.apply()
    }

    /** Mover una banda a mano deja el perfil en «Personalizado». */
    fun poner(banda: Int, nivel: Int) {
        runCatching { eq?.setBandLevel(banda.toShort(), nivel.toShort()) }
        preferencias?.edit()?.putInt("banda$banda", nivel)?.putString("perfil", PERSONALIZADO)?.apply()
    }

    /** Pone un perfil (y prende el ecualizador si estaba apagado: si eliges uno es para oírlo). */
    fun perfil(nombre: String) {
        val e = eq ?: return
        val perfil = PERFILES.firstOrNull { it.nombre == nombre } ?: return
        runCatching {
            val (min, max) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            val ed = preferencias?.edit()
            for (b in 0 until e.numberOfBands) {
                val mb = (dbEn(perfil, e.getCenterFreq(b.toShort()) / 1000) * 100).toInt().coerceIn(min, max)
                e.setBandLevel(b.toShort(), mb.toShort())
                ed?.putInt("banda$b", mb)
            }
            if (!e.enabled) { e.enabled = true; ed?.putBoolean("encendido", true) }
            ed?.putString("perfil", nombre)?.apply()
        }
    }
}
