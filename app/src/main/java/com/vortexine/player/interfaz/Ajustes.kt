package com.vortexine.player.interfaz

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.api.ApiException
import com.vortexine.player.motor.Actualizador
import com.vortexine.player.motor.Cuenta
import com.vortexine.player.motor.Sincro
import kotlinx.coroutines.launch

/** Inicia sesión con Google: Android enseña sus cuentas y pide permiso para la carpeta oculta de Drive. */
@Composable
fun rememberIniciarSesion(vm: VistaModelo): () -> Unit {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    val lanzador = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val token = if (r.resultCode == Activity.RESULT_OK) runCatching { Cuenta.resultadoDe(ctx, r.data).accessToken }.getOrNull() else null
        if (token != null) vm.conectarCuenta(token) else vm.aviso.value = "No se inició sesión."
    }
    return {
        alcance.launch {
            try {
                val r = Cuenta.autorizar(ctx)
                val pendiente = r.pendingIntent
                if (r.hasResolution() && pendiente != null) lanzador.launch(IntentSenderRequest.Builder(pendiente.intentSender).build())
                else r.accessToken?.let { vm.conectarCuenta(it) }
            } catch (e: ApiException) {
                vm.aviso.value = if (e.statusCode == 10) "Esta versión todavía no está registrada con Google."
                else "Google no dejó iniciar sesión (${e.statusCode})."
            } catch (e: Exception) {
                vm.aviso.value = "Google no dejó iniciar sesión: ${e.message}"
            }
        }
    }
}

fun hace(t: Long): String {
    val min = (System.currentTimeMillis() - t) / 60_000
    return when {
        min < 1 -> "hace un momento"
        min < 60 -> "hace $min min"
        min < 24 * 60 -> "hace ${min / 60} h"
        else -> "el " + java.text.SimpleDateFormat("d/M", java.util.Locale("es", "MX")).format(java.util.Date(t))
    }
}

fun textoSincro(s: Sincro.Estado) = when {
    s.trabajando -> s.texto
    s.error.isNotEmpty() -> s.error
    s.ultima > 0 -> "Sincronizado ${hace(s.ultima)}."
    else -> "Sincronizando por primera vez…"
}

/** Arriba de «Tu música»: lo que hace la nube, o la invitación a iniciar sesión (se puede esconder). */
@Composable
fun AvisoNube(vm: VistaModelo) {
    val cuenta by vm.cuenta.collectAsStateWithLifecycle()
    val s by vm.sincro.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cuenta", Context.MODE_PRIVATE) }
    var sinInvitar by remember { mutableStateOf(prefs.getBoolean("sin_invitar", false)) }
    val iniciar = rememberIniciarSesion(vm)

    if (cuenta.conectada && s.trabajando) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudSync, null, tint = C.azul, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.texto, color = C.suave, fontSize = 12.sp, maxLines = 1)
            }
            if (s.total > 0) LinearProgressIndicator({ s.hechas / s.total.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp),
                color = C.azul, trackColor = C.borde)
        }
    } else if (!cuenta.conectada && !sinInvitar) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = C.tarjeta)) {
            Column(Modifier.padding(16.dp)) {
                Text("¿Tienes Vortex Player en la compu?", color = C.texto, fontWeight = FontWeight.SemiBold)
                Text("Inicia sesión con Google y aquí aparecen tus canciones y playlists; el teléfono las baja solo.",
                    color = C.tenue, fontSize = 13.sp)
                Row(Modifier.padding(top = 8.dp)) {
                    Button(iniciar) { Text("Iniciar sesión") }
                    TextButton({ sinInvitar = true; prefs.edit().putBoolean("sin_invitar", true).apply() }) { Text("Ahora no") }
                }
            }
        }
    }
}

@Composable
fun PantallaAjustes(vm: VistaModelo) {
    val ctx = LocalContext.current
    val cuenta by vm.cuenta.collectAsStateWithLifecycle()
    val s by vm.sincro.collectAsStateWithLifecycle()
    val nueva by vm.nueva.collectAsStateWithLifecycle()
    val instalando by vm.instalando.collectAsStateWithLifecycle()
    var permiso by remember { mutableStateOf(Actualizador.puedeInstalar(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permiso = Actualizador.puedeInstalar(ctx) }
    val iniciar = rememberIniciarSesion(vm)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Titulo("Ajustes", "Tu cuenta y las actualizaciones")

        Seccion("Cuenta de Google", Icons.Rounded.AccountCircle) {
            if (cuenta.conectada) {
                Text(cuenta.nombre.ifEmpty { "Tu cuenta" }, color = C.texto, fontWeight = FontWeight.SemiBold)
                Text(cuenta.correo, color = C.tenue, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(textoSincro(s), color = if (s.error.isNotEmpty() && !s.trabajando) C.peligro else C.suave, fontSize = 13.sp)
                if (s.trabajando && s.total > 0) LinearProgressIndicator({ s.hechas / s.total.toFloat() },
                    Modifier.fillMaxWidth().padding(top = 6.dp), color = C.azul, trackColor = C.borde)
                Text("Tus canciones y playlists son las mismas aquí y en Vortex Player de la compu. Si borras una canción " +
                    "o una playlist en un lado, se borra en el otro.", color = C.tenue, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp)) {
                    Button(vm::sincronizarAhora, enabled = !s.trabajando) { Text("Sincronizar ahora") }
                    TextButton({ vm.cerrarSesion() }) { Text("Cerrar sesión") }
                }
            } else {
                Text("Inicia sesión y tendrás aquí las mismas canciones y playlists que en Vortex Player de la compu. " +
                    "El teléfono baja solo lo que le falte.", color = C.suave, fontSize = 14.sp)
                Text("Se guardan en una carpeta oculta de tu Google Drive que solo ve Vortex Player.",
                    color = C.tenue, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Button(iniciar, Modifier.padding(top = 10.dp)) { Text("Iniciar sesión con Google") }
            }
        }

        Seccion("Actualizaciones", Icons.Rounded.SystemUpdate) {
            Text("Versión ${Actualizador.actual()}", color = C.texto, fontWeight = FontWeight.SemiBold)
            val v = nueva
            if (v != null) {
                Text("Hay una versión nueva: ${v.nombre}", color = C.azul, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                if (v.notas.isNotBlank()) Text(v.notas.take(400), color = C.tenue, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Button(vm::actualizarAhora, Modifier.padding(top = 8.dp), enabled = !instalando && permiso) {
                    Text(if (instalando) "Instalando…" else "Actualizar ahora")
                }
            } else {
                Text("Se actualiza sola cuando sale una versión nueva.", color = C.tenue, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                OutlinedButton(vm::revisarActualizacion, Modifier.padding(top = 8.dp)) { Text("Buscar actualización") }
            }
            if (!permiso) {
                Text("Para que se actualice sola, Android pide tu permiso una vez.", color = C.suave, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp))
                TextButton({
                    if (Build.VERSION.SDK_INT >= 26) ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${ctx.packageName}")))
                }) { Text("Dar permiso") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Seccion(titulo: String, icono: androidx.compose.ui.graphics.vector.ImageVector, contenido: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = C.tarjeta)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(icono, null, tint = C.violeta, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(titulo, color = C.violeta, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            contenido()
        }
    }
}
