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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
                Icon(Icons.Rounded.CloudSync, null, tint = C.acento, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.texto, color = C.suave, fontSize = 12.sp, maxLines = 1)
            }
            if (s.total > 0) LinearProgressIndicator({ s.hechas / s.total.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp),
                color = C.acento, trackColor = C.borde)
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

// --- 1.4: «Tu resumen» -----------------------------------------------------------------------------------

/** «3 h 20 min». */
fun minutos(m: Int): String = if (m < 60) "$m min" else "${m / 60} h" + if (m % 60 > 0) " ${m % 60} min" else ""

/** Arriba de «Tu música», una vez por semana: lo de la semana pasada (siempre está en Ajustes). */
@Composable
fun AvisoResumen(vm: VistaModelo, ver: () -> Unit) {
    val aviso by vm.avisoResumen.collectAsStateWithLifecycle()
    val r by vm.resumen.collectAsStateWithLifecycle()
    if (!aviso) return
    val pasada = r.second
    val top = pasada.canciones.firstOrNull()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = C.tarjeta)) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (top != null) Portada(top.portada, 48.dp, 4.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Tu semana en música", color = C.texto, fontWeight = FontWeight.SemiBold)
                Text(minutos(pasada.minutos) + " escuchando" + (top?.let { " · la que más sonó: «${it.titulo}»" } ?: ""),
                    color = C.tenue, fontSize = 13.sp, maxLines = 2)
                TextButton({ vm.resumenVisto(); ver() }, contentPadding = PaddingValues(0.dp)) { Text("Ver tu resumen") }
            }
            IconButton(vm::resumenVisto) { Icon(Icons.Rounded.Close, "Cerrar", tint = C.tenue) }
        }
    }
}

@Composable
private fun TuResumen(vm: VistaModelo) {
    val r by vm.resumen.collectAsStateWithLifecycle()
    val aviso by vm.avisoResumen.collectAsStateWithLifecycle()
    var cual by rememberSaveable { mutableIntStateOf(if (aviso) 1 else 0) }
    LaunchedEffect(Unit) { vm.cargarResumen() }
    val s = if (cual == 0) r.first else r.second
    val fecha = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale("es", "MX")) }
    Seccion("Tu resumen", Icons.Rounded.Insights) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Esta semana", "La semana pasada").forEachIndexed { i, nombre ->
                FilterChip(cual == i, { cual = i }, { Text(nombre) }, border = null,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.morado, selectedLabelColor = C.blanco,
                        containerColor = C.campo, labelColor = C.suave))
            }
        }
        if (s.desde > 0) Text("Del lunes ${fecha.format(java.util.Date(s.desde))} al domingo ${fecha.format(java.util.Date(s.hasta - 1))}",
            color = C.tenue, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (s.escuchas == 0) {
            Text(if (cual == 1) "La semana pasada no escuchaste nada aquí." else "Todavía no hay nada esta semana: pon música y aquí irá apareciendo.",
                color = C.suave, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            return@Seccion
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(minutos(s.minutos) to "escuchando", "${s.distintas}" to if (s.distintas == 1) "canción" else "canciones",
                "${s.dias} de 7" to "días con música").forEach { (n, t) ->
                Column {
                    Text(n, color = C.acento, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(t, color = C.tenue, fontSize = 12.sp)
                }
            }
        }
        Text("Lo que más escuchaste", color = C.texto, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        s.canciones.forEachIndexed { i, c ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = C.tenue, fontSize = 13.sp, modifier = Modifier.width(18.dp))
                Portada(c.portada, 40.dp, 4.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(c.titulo, color = C.texto, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(c.artista.ifEmpty { "Artista desconocido" }, color = C.tenue, fontSize = 12.sp, maxLines = 1)
                }
                Text(if (c.veces == 1) "1 vez" else "${c.veces} veces", color = C.tenue, fontSize = 12.sp)
            }
        }
        if (s.artistas.isNotEmpty()) {
            Text("Tus artistas", color = C.texto, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            s.artistas.forEachIndexed { i, a ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = C.tenue, fontSize = 13.sp, modifier = Modifier.width(18.dp))
                    Text(a.artista, color = C.texto, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(minutos(a.minutos), color = C.tenue, fontSize = 12.sp)
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
    val fundido by vm.fundido.collectAsStateWithLifecycle()
    val letras by vm.letrasGuardadas.collectAsStateWithLifecycle()
    val radio by vm.radio.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Titulo("Ajustes", "Tu resumen, tu cuenta, la reproducción y las actualizaciones")

        TuResumen(vm)

        Seccion("Cuenta de Google", Icons.Rounded.AccountCircle) {
            if (cuenta.conectada) {
                Text(cuenta.nombre.ifEmpty { "Tu cuenta" }, color = C.texto, fontWeight = FontWeight.SemiBold)
                Text(cuenta.correo, color = C.tenue, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(textoSincro(s), color = if (s.error.isNotEmpty() && !s.trabajando) C.peligro else C.suave, fontSize = 13.sp)
                if (s.trabajando && s.total > 0) LinearProgressIndicator({ s.hechas / s.total.toFloat() },
                    Modifier.fillMaxWidth().padding(top = 6.dp), color = C.acento, trackColor = C.borde)
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

        // 1.3: crossfade y las letras guardadas para verlas sin internet (1.4: y la radio sin fin)
        Seccion("Reproducción", Icons.Rounded.Tune) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Radio sin fin", color = C.texto, fontWeight = FontWeight.SemiBold)
                    Text("Cuando se acaba lo que pusiste, siguen canciones parecidas de YouTube Music, sin bajarlas. " +
                        "Si una te gusta, bájala con el botón donde va el corazón.", color = C.tenue, fontSize = 12.sp)
                }
                Switch(radio, vm::ponerRadio, colors = SwitchDefaults.colors(checkedTrackColor = C.morado))
            }
            Text("Crossfade", color = C.texto, fontWeight = FontWeight.SemiBold)
            Text("La canción que termina se va apagando mientras la siguiente entra.", color = C.tenue, fontSize = 12.sp)
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Sin", 3 to "3 s", 5 to "5 s", 8 to "8 s", 12 to "12 s").forEach { (seg, nombre) ->
                    FilterChip(fundido == seg, { vm.ponerFundido(seg) }, { Text(nombre) }, border = null,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.morado, selectedLabelColor = C.blanco,
                            containerColor = C.campo, labelColor = C.suave))
                }
            }
            Text("Letras sin internet", color = C.texto, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
            Text(if (letras.total == 0) "Se buscan solas para toda tu música y se guardan en el teléfono."
                else "${letras.con} de ${letras.total} canciones ya buscadas" + if (letras.trabajando) " · buscando las demás…" else ".",
                color = C.tenue, fontSize = 12.sp)
            if (letras.trabajando && letras.total > 0) LinearProgressIndicator({ letras.con / letras.total.toFloat() },
                Modifier.fillMaxWidth().padding(top = 6.dp), color = C.acento, trackColor = C.borde)
            else TextButton(vm::bajarLetras) { Text("Buscar las que faltan") }
        }

        Seccion("Actualizaciones", Icons.Rounded.SystemUpdate) {
            Text("Versión ${Actualizador.actual()}", color = C.texto, fontWeight = FontWeight.SemiBold)
            val v = nueva
            if (v != null) {
                Text("Hay una versión nueva: ${v.nombre}", color = C.acento, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
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
                Icon(icono, null, tint = C.lila, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(titulo, color = C.lila, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            contenido()
        }
    }
}
