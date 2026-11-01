package com.vortexine.player.reproductor

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vortexine.player.VortexApp
import com.vortexine.player.motor.Cuenta
import com.vortexine.player.motor.Drive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 1.5: seguir en el otro aparato. Lo que suena (y en qué segundo) va a `sonando.json` en la carpeta oculta de
 * Vortex Player en tu Drive; la PC lo lee al abrirla y ofrece «Seguir aquí», y al revés.
 */
object Seguir {
    const val ARCHIVO = "sonando.json"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun aparato(ctx: Context): String = prefs(ctx).getString("aparato", null)
        ?: ("cel-" + java.util.UUID.randomUUID().toString().take(12)).also { prefs(ctx).edit().putString("aparato", it).apply() }

    /** Cuándo subió este cel lo último (ms): lo de la PC solo se ofrece si es más nuevo. */
    fun subido(ctx: Context) = prefs(ctx).getLong("sonando_subido", 0)

    fun visto(ctx: Context) = prefs(ctx).getLong("seguir_visto", 0)
    fun marcarVisto(ctx: Context, cuando: Long) = prefs(ctx).edit().putLong("seguir_visto", cuando).apply()

    suspend fun subir(ctx: Context, datos: JSONObject) {
        val token = Cuenta.token(ctx) ?: return
        val d = Drive(token, ARCHIVO)
        val id = d.buscar()
        if (id != null) d.escribir(id, datos.toString()) else d.crear(datos.toString())
        prefs(ctx).edit().putLong("sonando_subido", datos.getLong("cuando")).apply()
    }

    suspend fun leer(ctx: Context): JSONObject? {
        val token = Cuenta.token(ctx) ?: return null
        val d = Drive(token, ARCHIVO)
        return d.buscar()?.let { JSONObject(d.leer(it)) }
    }

    /** ¿Ofrecer «Seguir aquí»? De otro aparato, de menos de 12 h y más nuevo que lo de aquí y lo ya descartado. */
    fun ofrecer(ctx: Context, d: JSONObject?, ahora: Long = System.currentTimeMillis()): Boolean {
        if (d == null || d.optString("id").isEmpty() || d.optString("aparato") == aparato(ctx)) return false
        val cuando = d.optLong("cuando")
        return ahora - cuando < 12 * 3_600_000L && cuando > maxOf(subido(ctx), visto(ctx))
    }
}

/** En el servicio: sube lo que suena al pausar (pasados 3 s de la canción) y cada minuto mientras suena. */
class SubirSonando(private val ctx: Context, private val j: ExoPlayer) : Player.Listener {
    private val h = Handler(Looper.getMainLooper())
    private var ultimo = ""
    private val cadaMinuto = object : Runnable {
        override fun run() { subir(); if (j.isPlaying) h.postDelayed(this, 60_000) }
    }

    override fun onIsPlayingChanged(tocando: Boolean) {
        h.removeCallbacksAndMessages(null)
        if (tocando) h.postDelayed(cadaMinuto, 60_000) else h.postDelayed({ subir() }, 2500)
    }

    fun subir() {
        val app = ctx.applicationContext as VortexApp
        val item = j.currentMediaItem ?: return
        val md = item.mediaMetadata
        val id = md.extras?.getString("id")?.ifEmpty { null } ?: Radio.idDe(item.mediaId) ?: return
        if (j.currentPosition < 3000 || !Cuenta.datos.value.conectada) return
        val clave = "$id|${j.currentPosition / 10_000}|${j.isPlaying}"
        if (clave == ultimo) return
        ultimo = clave
        val portada = md.artworkUri?.toString().orEmpty()
        val datos = JSONObject().put("aparato", Seguir.aparato(ctx)).put("nombre", "tu cel").put("id", id)
            .put("titulo", md.title?.toString().orEmpty()).put("artista", md.artist?.toString().orEmpty())
            .put("album", md.albumTitle?.toString().orEmpty()).put("duracion", (j.duration.coerceAtLeast(0) / 1000.0))
            .put("portada", if (portada.startsWith("http")) portada else "").put("posicion", j.currentPosition / 1000.0)
            .put("cuando", System.currentTimeMillis())
        app.alcance.launch { runCatching { Seguir.subir(ctx, datos) } }
    }

    fun soltar() { h.removeCallbacksAndMessages(null) }
}
