package com.vortexine.player.motor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.vortexine.player.BuildConfig
import com.vortexine.player.segundo.ResultadoInstalacion
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.IOException

/**
 * Vortex Player se actualiza solo: revisa la última versión publicada en GitHub, la baja y la instala.
 * En Android 12 y más nuevos se instala sin preguntar (después del primer permiso); en los anteriores
 * Android enseña su ventana de «¿Actualizar?» y basta un toque.
 */
object Actualizador {
    data class Version(val nombre: String, val apk: String, val notas: String)

    /** La versión nueva, si hay. */
    val hay = MutableStateFlow<Version?>(null)
    val instalando = MutableStateFlow(false)

    fun actual(): String = BuildConfig.VERSION_NAME

    /** ¿`a` es más nueva que `b`? («1.10.0» > «1.9.2») */
    fun masNueva(a: String, b: String): Boolean {
        fun partes(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val x = partes(a)
        val y = partes(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Lee la última publicación (formato de la API de GitHub: `tag_name`, `body`, `assets[].browser_download_url`). */
    fun leer(json: String): Version? {
        val r = JSONObject(json)
        val assets = r.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it).optString("browser_download_url") }
            .firstOrNull { it.endsWith(".apk") } ?: return null
        return Version(r.optString("tag_name").removePrefix("v"), apk, r.optString("body"))
    }

    fun ultima(): Version? = leer(Red.bajarTexto(BuildConfig.ACTUALIZACIONES))

    fun puedeInstalar(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Revisa y, si hay una nueva y Android deja, la instala. Devuelve la nueva (o null si estás al día). */
    fun revisar(ctx: Context, instalar: Boolean = true): Version? {
        val v = ultima()?.takeIf { masNueva(it.nombre, actual()) }
        hay.value = v
        if (v != null && instalar && puedeInstalar(ctx)) instalar(ctx, v)
        return v
    }

    fun instalar(ctx: Context, v: Version) {
        if (instalando.value) return
        instalando.value = true
        try {
            val instalador = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sesion = instalador.openSession(instalador.createSession(params))
            sesion.use { s ->
                val req = okhttp3.Request.Builder().url(v.apk).header("User-Agent", Red.AGENTE).build()
                Red.cliente.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("GitHub contestó ${r.code} al bajar la actualización")
                    s.openWrite("vortex-player.apk", 0, r.body.contentLength()).use { out ->
                        r.body.byteStream().copyTo(out)
                        s.fsync(out)
                    }
                }
                val aviso = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ResultadoInstalacion::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                s.commit(aviso.intentSender)
            }
        } catch (e: Exception) {
            instalando.value = false
            throw e
        }
    }
}
