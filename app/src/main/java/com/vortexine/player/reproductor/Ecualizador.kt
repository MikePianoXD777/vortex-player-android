package com.vortexine.player.reproductor

import android.content.Context
import android.media.audiofx.Equalizer

/**
 * El ecualizador de Android sobre el audio de Vortex Player (cada teléfono trae sus bandas: casi siempre 5).
 * Lo que eliges se guarda y se vuelve a poner al abrir la app.
 */
object Ecualizador {
    private var eq: Equalizer? = null
    private var preferencias: android.content.SharedPreferences? = null

    data class Banda(val hz: Int, val nivel: Int)
    data class Estado(val disponible: Boolean, val encendido: Boolean, val min: Int, val max: Int,
                      val bandas: List<Banda>, val preajustes: List<String>)

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
        val e = eq ?: return Estado(false, false, -1500, 1500, emptyList(), emptyList())
        return runCatching {
            val (min, max) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            Estado(true, e.enabled, min, max,
                (0 until e.numberOfBands).map { Banda(e.getCenterFreq(it.toShort()) / 1000, e.getBandLevel(it.toShort()).toInt()) },
                (0 until e.numberOfPresets).map { nombre(e.getPresetName(it.toShort())) })
        }.getOrDefault(Estado(false, false, -1500, 1500, emptyList(), emptyList()))
    }

    private val traducidos = mapOf("Classical" to "Clásica", "Flat" to "Plano", "Heavy Metal" to "Metal", "Hip Hop" to "Hip hop")

    /** Algunos teléfonos traen el nombre con caracteres nulos al final. */
    private fun nombre(crudo: String) = crudo.replace("\u0000", "").trim().let { traducidos[it] ?: it }

    fun encender(si: Boolean) {
        runCatching { eq?.enabled = si }
        preferencias?.edit()?.putBoolean("encendido", si)?.apply()
    }

    fun poner(banda: Int, nivel: Int) {
        runCatching { eq?.setBandLevel(banda.toShort(), nivel.toShort()) }
        preferencias?.edit()?.putInt("banda$banda", nivel)?.apply()
    }

    fun preajuste(i: Int) {
        val e = eq ?: return
        runCatching {
            e.usePreset(i.toShort())
            val ed = preferencias?.edit()
            for (b in 0 until e.numberOfBands) ed?.putInt("banda$b", e.getBandLevel(b.toShort()).toInt())
            ed?.apply()
        }
    }
}
