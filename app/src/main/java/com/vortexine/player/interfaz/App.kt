package com.vortexine.player.interfaz

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val PESTANAS = listOf(
    Triple("Biblioteca", Icons.Rounded.LibraryMusic, 0),
    Triple("Buscar", Icons.Rounded.Search, 1),
    Triple("Playlists", Icons.Rounded.QueueMusic, 2),
    Triple("Ecualizador", Icons.Rounded.GraphicEq, 3),
    Triple("Ajustes", Icons.Rounded.Settings, 4),
)

@Composable
fun App(vm: VistaModelo) {
    var pestana by rememberSaveable { mutableIntStateOf(0) }
    var abierta by rememberSaveable { mutableStateOf<Long?>(null) }
    var grande by rememberSaveable { mutableStateOf(false) }
    val sonando by vm.sonando.collectAsStateWithLifecycle()
    val aviso by vm.aviso.collectAsStateWithLifecycle()
    val avisos = remember { SnackbarHostState() }

    LaunchedEffect(aviso) {
        aviso?.let { avisos.showSnackbar(it); vm.aviso.value = null }
    }
    BackHandler(enabled = grande || abierta != null) { if (grande) grande = false else abierta = null }

    Box(Modifier.fillMaxSize().background(C.fondo)) {
        Scaffold(
            containerColor = C.fondo,
            snackbarHost = { SnackbarHost(avisos) },
            bottomBar = {
                Column {
                    if (sonando.uri.isNotEmpty()) MiniReproductor(sonando, vm) { grande = true }
                    NavigationBar(containerColor = C.barra) {
                        PESTANAS.forEach { (nombre, icono, i) ->
                            NavigationBarItem(
                                selected = pestana == i, onClick = { pestana = i; if (i == 2) abierta = null },
                                icon = { Icon(icono, nombre) }, label = { Text(nombre, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = C.realceFuerte,
                                    selectedIconColor = C.azul, selectedTextColor = C.azul,
                                    unselectedIconColor = C.tenue, unselectedTextColor = C.tenue),
                            )
                        }
                    }
                }
            },
        ) { margen ->
            Box(Modifier.padding(margen)) {
                when (pestana) {
                    0 -> PantallaBiblioteca(vm)
                    1 -> PantallaBuscar(vm)
                    2 -> if (abierta == null) PantallaPlaylists(vm) { abierta = it } else PantallaPlaylist(vm, abierta!!) { abierta = null }
                    3 -> PantallaEcualizador()
                    else -> PantallaAjustes(vm)
                }
            }
        }
        AnimatedVisibility(grande, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Reproductor(sonando, vm) { grande = false }
        }
    }
}

@Composable
fun MiniReproductor(s: Sonando, vm: VistaModelo, abrir: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.tarjeta).clickable(onClick = abrir)) {
        LinearProgressIndicator(
            progress = { if (s.duracion > 0) s.posicion.toFloat() / s.duracion else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = C.azul, trackColor = C.borde,
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Portada(s.portada, 44.dp, 6.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.titulo, color = C.texto, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.artista, color = C.tenue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(vm::pausarOSeguir) {
                Icon(if (s.tocando) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Tocar o pausar", tint = C.texto)
            }
            IconButton({ vm.siguiente() }, enabled = s.haySiguiente) { Icon(Icons.Rounded.SkipNext, "Siguiente") }
        }
    }
}

@Composable
fun Reproductor(s: Sonando, vm: VistaModelo, cerrar: () -> Unit) {
    var arrastrando by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.fillMaxSize().background(C.fondo).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(cerrar) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", tint = C.texto) }
            Text("Sonando", color = C.tenue, modifier = Modifier.weight(1f), fontSize = 13.sp)
        }
        Spacer(Modifier.weight(0.6f))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Portada(s.portada.replace("=w226-h226", "=w600-h600"), minOf(maxWidth, 360.dp), 16.dp)
        }
        Spacer(Modifier.weight(0.5f))
        Column(Modifier.fillMaxWidth()) {
            Text(s.titulo, color = C.texto, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.artista, color = C.suave, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Etiqueta(s.etiqueta, s.nivel)
        }
        Spacer(Modifier.height(16.dp))
        val dur = s.duracion.coerceAtLeast(1)
        Slider(
            value = arrastrando ?: (s.posicion.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { arrastrando = it },
            onValueChangeFinished = { arrastrando?.let { vm.ir((it * dur).toLong()) }; arrastrando = null },
            colors = SliderDefaults.colors(thumbColor = C.azul, activeTrackColor = C.azul, inactiveTrackColor = C.borde),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(tiempo(arrastrando?.let { (it * dur).toLong() } ?: s.posicion), color = C.tenue, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(tiempo(s.duracion), color = C.tenue, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(vm::alternarAleatorio) {
                Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = if (s.aleatorio) C.azul else C.tenue)
            }
            IconButton({ vm.anterior() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(36.dp), tint = C.texto) }
            FilledIconButton(vm::pausarOSeguir, Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = C.azul, contentColor = C.fondo)) {
                Icon(if (s.tocando) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Tocar o pausar", Modifier.size(40.dp))
            }
            IconButton({ vm.siguiente() }, Modifier.size(56.dp), enabled = s.haySiguiente) {
                Icon(Icons.Rounded.SkipNext, "Siguiente", Modifier.size(36.dp), tint = if (s.haySiguiente) C.texto else C.borde)
            }
            IconButton(vm::alternarRepetir) {
                Icon(if (s.repetir == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repetir", tint = if (s.repetir != androidx.media3.common.Player.REPEAT_MODE_OFF) C.azul else C.tenue)
            }
        }
        Spacer(Modifier.weight(0.4f))
    }
}
