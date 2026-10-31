package com.vortexine.player.motor

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * La cuenta de Google con la que se sincroniza. Solo se pide permiso para la carpeta oculta de la app en
 * Drive; Google Play Services guarda la sesión, y aquí solo se recuerda el nombre y el correo para mostrarlos.
 */
object Cuenta {
    private val ALCANCE = Scope("https://www.googleapis.com/auth/drive.appdata")

    data class Datos(val conectada: Boolean = false, val nombre: String = "", val correo: String = "")

    val datos = MutableStateFlow(Datos())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cuenta", Context.MODE_PRIVATE)

    fun cargar(ctx: Context) {
        val p = prefs(ctx)
        datos.value = Datos(p.getBoolean("conectada", false), p.getString("nombre", "") ?: "", p.getString("correo", "") ?: "")
    }

    private fun cliente(ctx: Context) = Identity.getAuthorizationClient(ctx)

    /** Pide el permiso: si Google necesita que elijas cuenta o aceptes, trae `pendingIntent` para abrirlo. */
    suspend fun autorizar(ctx: Context): AuthorizationResult =
        cliente(ctx).authorize(AuthorizationRequest.Builder().setRequestedScopes(listOf(ALCANCE)).build()).await()

    fun resultadoDe(ctx: Context, datosIntent: android.content.Intent?): AuthorizationResult =
        cliente(ctx).getAuthorizationResultFromIntent(datosIntent)

    /** Ya con el permiso: recuerda quién es la cuenta. */
    suspend fun conectar(ctx: Context, token: String) {
        val (nombre, correo) = withContext(Dispatchers.IO) { Drive(token).usuario() }
        prefs(ctx).edit().putBoolean("conectada", true).putString("nombre", nombre).putString("correo", correo).apply()
        datos.value = Datos(true, nombre, correo)
    }

    /** El token para Drive sin molestar a nadie; null si no hay sesión o si Google pide abrir la app. */
    suspend fun token(ctx: Context): String? {
        if (!datos.value.conectada) return null
        val r = runCatching { autorizar(ctx) }.getOrNull() ?: return null
        return if (r.hasResolution()) null else r.accessToken
    }

    /** Un token que Drive ya no acepta: se tira para que el siguiente sea nuevo. */
    suspend fun olvidarToken(ctx: Context, token: String) {
        runCatching { cliente(ctx).clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    suspend fun cerrar(ctx: Context) {
        runCatching {
            val r = autorizar(ctx)
            @Suppress("DEPRECATION")
            val cuenta = r.toGoogleSignInAccount()?.account
            r.accessToken?.let { olvidarToken(ctx, it) }
            if (cuenta != null) cliente(ctx).revokeAccess(RevokeAccessRequest.builder().setAccount(cuenta)
                .setScopes(listOf(ALCANCE)).build()).await()
        }
        prefs(ctx).edit().clear().apply()
        datos.value = Datos()
    }
}
