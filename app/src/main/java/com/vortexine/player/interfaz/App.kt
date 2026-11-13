package com.vortexine.player.interfaz

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortexine.player.motor.Letras

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
                Column(Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, C.barra), endY = 120f))) {
                    SeguirAqui(vm)   // 1.5: lo que dejaste sonando en la PC
                    AnimatedVisibility(sonando.uri.isNotEmpty(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        MiniReproductor(sonando, vm) { grande = true }
                    }
                    NavigationBar(containerColor = C.barra, tonalElevation = 0.dp) {
                        PESTANAS.forEach { (nombre, icono, i) ->
                            NavigationBarItem(
                                selected = pestana == i, onClick = { if (pestana == i && i == 2) abierta = null; pestana = i },
                                icon = { Icon(icono, nombre) }, label = { Text(nombre, fontSize = 11.sp, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                                    selectedIconColor = C.blanco, selectedTextColor = C.blanco,
                                    unselectedIconColor = C.tenue, unselectedTextColor = C.tenue),
                            )
                        }
                    }
                }
            },
        ) { margen ->
            Box(Modifier.padding(margen)) {
                AnimatedContent(pestana, transitionSpec = { entrePestanas() }, label = "pestañas") { p ->
                    Box(conDesenfoque().fillMaxSize()) {
                        when (p) {
                            0 -> PantallaBiblioteca(vm) { pestana = 4 }
                            1 -> PantallaBuscar(vm)
                            2 -> AnimatedContent(abierta, transitionSpec = { deLado(targetState != null) }, label = "playlist") { a ->
                                Box(conDesenfoque().fillMaxSize()) {
                                    if (a == null) PantallaPlaylists(vm) { abierta = it } else PantallaPlaylist(vm, a, { q -> pestana = 1; if (q.isNotBlank()) vm.buscar(q) }) { abierta = null }
                                }
                            }
                            3 -> PantallaEcualizador()
                            else -> PantallaAjustes(vm)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(grande,
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 320f)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(280, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(250))) {
            Box(conDesenfoque()) { Reproductor(sonando, vm) { grande = false } }
        }
    }
}

/** 1.5: «¿Seguir aquí?»: lo que sonaba en la PC, en el mismo segundo. */
@Composable
private fun SeguirAqui(vm: VistaModelo) {
    val d by vm.seguir.collectAsStateWithLifecycle()
    AnimatedVisibility(d != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val o = d ?: return@AnimatedVisibility
        val min = ((System.currentTimeMillis() - o.optLong("cuando")) / 60_000).toInt()
        val hace = if (min < 1) "hace un momento" else if (min < 60) "hace $min min" else "hace ${min / 60} h"
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(C.tarjeta)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Computer, null, tint = C.lila, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("En ${o.optString("nombre", "tu PC")} sonaba · $hace", color = C.tenue, fontSize = 12.sp)
                Text(o.optString("titulo") + o.optString("artista").let { if (it.isNotEmpty()) " · $it" else "" },
                    color = C.blanco, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(vm::seguirAqui) { Text("Seguir aquí", color = C.acento, fontWeight = FontWeight.Bold) }
            IconButton(vm::descartarSeguir) { Icon(Icons.Rounded.Close, "Cerrar", tint = C.tenue) }
        }
    }
}

/** 1.5: practicar una canción: más lenta sin cambiar el tono y repetir un pedazo de A a B. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Practicar(vm: VistaModelo, s: Sonando, cerrar: () -> Unit) {
    val v by vm.velocidad.collectAsStateWithLifecycle()
    val t by vm.tramo.collectAsStateWithLifecycle()
    ModalBottomSheet(cerrar, containerColor = C.tarjeta) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text("Practicar", color = C.blanco, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Para sacar la canción en el piano: más lenta sin que cambie el tono, y repite el pedazo que estás aprendiendo.",
                color = C.tenue, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            Text("Velocidad", color = C.texto, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f to "50 %", 0.75f to "75 %", 0.9f to "90 %", 1f to "Normal").forEach { (x, nombre) ->
                    FilterChip(v == x, { vm.ponerVelocidad(x) }, { Text(nombre) }, border = null,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.morado, selectedLabelColor = C.blanco,
                            containerColor = C.campo, labelColor = C.suave))
                }
            }
            Text("Repetir un pedazo", color = C.texto, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
            Text(when {
                t.first < 0 -> "Toca «Marcar el inicio» donde empieza el pedazo."
                t.second < 0 -> "Inicio en ${tiempo(t.first)}. Ahora toca «Marcar el final» donde termina."
                else -> "Se repite de ${tiempo(t.first)} a ${tiempo(t.second)}."
            }, color = C.tenue, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Button(vm::marcarTramo, Modifier.padding(top = 10.dp), colors = ButtonDefaults.buttonColors(
                containerColor = if (t.second >= 0) C.campo else C.morado, contentColor = C.blanco)) {
                Text(when {
                    t.first < 0 -> "Marcar el inicio (${tiempo(s.posicion)})"
                    t.second < 0 -> "Marcar el final (${tiempo(s.posicion)})"
                    else -> "Dejar de repetir"
                })
            }
        }
    }
}

/** La barra de la canción que suena: una tarjeta del color de la portada, como en Spotify. */
@Composable
fun MiniReproductor(s: Sonando, vm: VistaModelo, abrir: () -> Unit) {
    val color = colorAnimado(s.portada)
    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(color).clickable(onClick = abrir)) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(s.uri, transitionSpec = { cambioDeCancion() }, modifier = Modifier.weight(1f), label = "mini") { _ ->
                Row(conDesenfoque(), verticalAlignment = Alignment.CenterVertically) {
                    Portada(s.portada, 40.dp, 4.dp)
                    Column(Modifier.padding(horizontal = 10.dp)) {
                        Text(s.titulo, color = C.blanco, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artista, color = C.blanco.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Corazon(vm, s.uri, 24.dp)
            val fuente = remember { MutableInteractionSource() }
            IconButton(vm::pausarOSeguir, Modifier.alPresionar(fuente), interactionSource = fuente) {
                Icon(if (s.tocando) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Tocar o pausar", tint = C.blanco, modifier = Modifier.size(30.dp))
            }
            IconButton({ vm.siguiente() }, enabled = s.haySiguiente) { Icon(Icons.Rounded.SkipNext, "Siguiente", tint = C.blanco) }
        }
        val avance by animateFloatAsState(if (s.duracion > 0) s.posicion.toFloat() / s.duracion else 0f, tween(250, easing = LinearEasing), label = "avance")
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(C.blanco.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(avance.coerceIn(0f, 1f)).fillMaxHeight().background(C.blanco))
        }
    }
}

@Composable
fun Reproductor(s: Sonando, vm: VistaModelo, cerrar: () -> Unit) {
    var arrastrando by remember { mutableStateOf<Float?>(null) }
    var letraGrande by rememberSaveable { mutableStateOf(false) }
    var verCola by remember { mutableStateOf(false) }
    var verDormir by remember { mutableStateOf(false) }
    var verPracticar by remember { mutableStateOf(false) }
    val velocidad by vm.velocidad.collectAsStateWithLifecycle()
    val tramo by vm.tramo.collectAsStateWithLifecycle()
    val dormir by vm.temporizador.collectAsStateWithLifecycle()
    val origen by vm.origen.collectAsStateWithLifecycle()
    val letra by vm.letra.collectAsStateWithLifecycle()
    val buscandoLetra by vm.buscandoLetra.collectAsStateWithLifecycle()
    val color = colorAnimado(s.portada)
    BackHandler(letraGrande) { letraGrande = false }

    Box(Modifier.fillMaxSize().background(C.fondo).background(Brush.verticalGradient(listOf(color, C.fondo)))) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val alto = maxHeight
            val ancho = maxWidth
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Column(Modifier.height(alto), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(cerrar) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", tint = C.blanco, modifier = Modifier.size(32.dp)) }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("TOCANDO DESDE", color = C.blanco.copy(alpha = 0.7f), fontSize = 11.sp, letterSpacing = 1.sp)
                            Text(origen.ifEmpty { "Tu música" }, color = C.blanco, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(48.dp))
                    }
                    Spacer(Modifier.weight(0.5f))
                    // La portada se encoge un poco en pausa y crece con resorte al tocar
                    val escala by animateFloatAsState(if (s.tocando) 1f else 0.88f, spring(dampingRatio = 0.6f, stiffness = 220f), label = "portada")
                    AnimatedContent(s.portada, transitionSpec = { cambioDeCancion() }, label = "portada grande") { url ->
                        Box(conDesenfoque().fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.graphicsLayer { scaleX = escala; scaleY = escala; shadowElevation = 24f; shape = RoundedCornerShape(8.dp); clip = true }) {
                                Portada(url.replace("=w226-h226", "=w600-h600"), minOf(ancho - 48.dp, 360.dp), 8.dp)
                            }
                        }
                    }
                    Spacer(Modifier.weight(0.5f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(s.uri, transitionSpec = { cambioDeCancion() }, modifier = Modifier.weight(1f), label = "título") { _ ->
                            Column(conDesenfoque().fillMaxWidth()) {
                                Text(s.titulo, color = C.blanco, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artista, color = C.blanco.copy(alpha = 0.7f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp))
                                Etiqueta(s.etiqueta, s.nivel)
                            }
                        }
                        Corazon(vm, s.uri, 30.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    val dur = s.duracion.coerceAtLeast(1)
                    BarraTiempo(arrastrando ?: (s.posicion.toFloat() / dur).coerceIn(0f, 1f), { arrastrando = it },
                        { arrastrando?.let { vm.ir((it * dur).toLong()) }; arrastrando = null })
                    Row(Modifier.fillMaxWidth()) {
                        Text(tiempo(arrastrando?.let { (it * dur).toLong() } ?: s.posicion), color = C.blanco.copy(alpha = 0.7f), fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(tiempo(s.duracion), color = C.blanco.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(vm::alternarAleatorio) {
                            Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = if (s.aleatorio) C.acento else C.blanco)
                        }
                        IconButton({ vm.anterior() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(40.dp), tint = C.blanco) }
                        BotonTocar(s.tocando, 68.dp) { vm.pausarOSeguir() }
                        IconButton({ vm.siguiente() }, Modifier.size(56.dp), enabled = s.haySiguiente) {
                            Icon(Icons.Rounded.SkipNext, "Siguiente", Modifier.size(40.dp), tint = if (s.haySiguiente) C.blanco else C.blanco.copy(alpha = 0.3f))
                        }
                        IconButton(vm::alternarRepetir) {
                            Icon(if (s.repetir == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                "Repetir", tint = if (s.repetir != androidx.media3.common.Player.REPEAT_MODE_OFF) C.acento else C.blanco)
                        }
                    }
                    // 1.3: el temporizador para dormir y la cola «A continuación», como abajo en Spotify
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ verDormir = true }) {
                            Icon(Icons.Rounded.Bedtime, "Temporizador para dormir", tint = if (dormir.activo) C.acento else C.blanco.copy(alpha = 0.8f))
                            if (dormir.activo) Text("  " + textoDormir(dormir), color = C.acento, fontSize = 13.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        // 1.5: practicar y compartir
                        val practicando = velocidad != 1f || tramo.first >= 0
                        TextButton({ verPracticar = true }) {
                            Icon(Icons.Rounded.Speed, "Practicar", tint = if (practicando) C.acento else C.blanco.copy(alpha = 0.8f))
                            if (velocidad != 1f) Text("  ${(velocidad * 100).toInt()} %", color = C.acento, fontSize = 13.sp)
                        }
                        IconButton({ vm.compartir(s.titulo, s.artista, com.vortexine.player.reproductor.Radio.idDe(s.uri)
                            ?: vm.pistas.value.firstOrNull { it.uri == s.uri }?.videoId.orEmpty()) }, enabled = s.uri.isNotEmpty()) {
                            Icon(Icons.Rounded.Share, "Compartir", tint = C.blanco.copy(alpha = 0.8f))
                        }
                        IconButton({ verCola = true }) { Icon(Icons.Rounded.QueueMusic, "A continuación", tint = C.blanco.copy(alpha = 0.8f)) }
                    }
                    Spacer(Modifier.weight(0.3f))
                    if (letra != null) Text("Desliza hacia arriba para ver la letra", color = C.blanco.copy(alpha = 0.5f), fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                }
                TarjetaLetra(letra, buscandoLetra, s, color, vm) { letraGrande = true }
                Spacer(Modifier.height(32.dp))
            }
        }
        if (verCola) Cola(vm, s) { verCola = false }
        if (verDormir) Dormir(vm, dormir) { verDormir = false }
        if (verPracticar) Practicar(vm, s) { verPracticar = false }
        AnimatedVisibility(letraGrande && letra != null, enter = fadeIn(tween(250)) + scaleIn(tween(300), 0.94f), exit = fadeOut(tween(200)) + scaleOut(tween(200), 0.96f)) {
            letra?.let { Box(conDesenfoque()) { LetraCompleta(it, s, color, vm) { letraGrande = false } } }
        }
    }
}

/** La tarjeta de la letra debajo del reproductor (como Spotify): sigue la canción línea por línea. */
@Composable
private fun TarjetaLetra(letra: Letras.Letra?, buscando: Boolean, s: Sonando, color: Color, vm: VistaModelo, abrir: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color).background(Color.Black.copy(alpha = 0.15f))
        .clickable(enabled = letra != null, onClick = abrir).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Letra", color = C.blanco, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (letra != null) Icon(Icons.Rounded.OpenInFull, "Ver toda la letra", tint = C.blanco)
        }
        Spacer(Modifier.height(10.dp))
        when {
            letra != null -> LineasLetra(letra, s, vm, Modifier.height(300.dp), 20.sp)
            buscando -> Text("Buscando la letra…", color = C.blanco.copy(alpha = 0.7f))
            else -> Text("No encontré la letra de esta canción.", color = C.blanco.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun LetraCompleta(letra: Letras.Letra, s: Sonando, color: Color, vm: VistaModelo, cerrar: () -> Unit) {
    Column(Modifier.fillMaxSize().background(color).background(Color.Black.copy(alpha = 0.15f)).systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(cerrar) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", tint = C.blanco, modifier = Modifier.size(32.dp)) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(s.titulo, color = C.blanco, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.artista, color = C.blanco.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val fuente = remember { MutableInteractionSource() }
            IconButton(vm::pausarOSeguir, Modifier.alPresionar(fuente), interactionSource = fuente) {
                Icon(if (s.tocando) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle, "Tocar o pausar", tint = C.blanco, modifier = Modifier.size(40.dp))
            }
        }
        LineasLetra(letra, s, vm, Modifier.weight(1f), 26.sp)
    }
}

/** Las líneas: la que suena en blanco, las que ya pasaron tenues y las que vienen oscuras. Tocar una salta ahí. */
@Composable
private fun LineasLetra(letra: Letras.Letra, s: Sonando, vm: VistaModelo, modifier: Modifier, tam: TextUnit) {
    val lista = rememberLazyListState()
    val actual = if (letra.sincronizada) Letras.actual(letra.lineas, s.posicion) else -1
    LaunchedEffect(actual) { if (actual >= 0) lista.animateScrollToItem((actual - 1).coerceAtLeast(0)) }
    LazyColumn(modifier, state = lista, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
        itemsIndexed(letra.lineas) { i, l ->
            val objetivo = when {
                !letra.sincronizada || i == actual -> C.blanco
                i < actual -> C.blanco.copy(alpha = 0.55f)
                else -> Color.Black.copy(alpha = 0.55f)
            }
            val tinta by animateColorAsState(objetivo, tween(350), label = "línea")
            val escala by animateFloatAsState(if (i == actual) 1.03f else 1f, spring(dampingRatio = 0.7f, stiffness = 300f), label = "escala")
            Text(l.texto.ifEmpty { if (letra.sincronizada) "♪" else "" }, color = tinta, fontSize = tam, fontWeight = FontWeight.Bold, lineHeight = tam * 1.25f,
                modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = escala; scaleY = escala; transformOrigin = TransformOrigin(0f, 0.5f) }
                    .clickable(enabled = letra.sincronizada && l.ms >= 0) { vm.ir(l.ms) })
        }
    }
}

// --- 1.3: ❤️, la cola y el temporizador -----------------------------------------------------------------

/** El corazón de Spotify: al tocarlo late y se pinta del morado de Obsidian. */
@Composable
fun Corazon(vm: VistaModelo, uri: String, tam: androidx.compose.ui.unit.Dp) {
    // 1.4: la de la radio todavía no es tuya: en vez del corazón, el botón para bajarla
    com.vortexine.player.reproductor.Radio.idDe(uri)?.let { id ->
        val bajadas by vm.bajadas.collectAsStateWithLifecycle()
        val cola by vm.cola.collectAsStateWithLifecycle()
        val lista = id in bajadas || cola.any { it.cancion.id == id && it.error.isEmpty() }
        IconButton({ vm.bajarDeLaRadio(vm.sonando.value) }, enabled = !lista) {
            Icon(if (lista) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (lista) "Bajando a tu biblioteca" else "Bajar a tu biblioteca",
                tint = if (lista) C.acento else C.blanco, modifier = Modifier.size(tam))
        }
        return
    }
    val meGusta by vm.meGusta.collectAsStateWithLifecycle()
    val gusta = uri in meGusta
    var latido by remember { mutableIntStateOf(0) }
    val escala = remember { Animatable(1f) }
    LaunchedEffect(latido) {
        if (latido > 0) { escala.animateTo(1.3f, tween(110)); escala.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
    }
    IconButton({ latido++; vm.alternarMeGusta(uri) }, enabled = uri.isNotEmpty()) {
        Icon(if (gusta) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (gusta) "Quitar de Tus me gusta" else "Guardar en Tus me gusta",
            tint = if (gusta) C.acento else C.blanco, modifier = Modifier.size(tam).graphicsLayer { scaleX = escala.value; scaleY = escala.value })
    }
}

/** Cuánto falta (se actualiza cada segundo mientras está abierto). */
@Composable
private fun textoDormir(e: com.vortexine.player.reproductor.Temporizador.Estado): String {
    if (e.alFinal) return "Al terminar la canción"
    var ahora by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(e.hasta) { while (true) { ahora = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(1000) } }
    val s = ((e.hasta - ahora) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dormir(vm: VistaModelo, e: com.vortexine.player.reproductor.Temporizador.Estado, cerrar: () -> Unit) {
    ModalBottomSheet(cerrar, containerColor = C.tarjeta) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Temporizador para dormir", color = C.blanco, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
            Text(if (e.activo) "La música se pausa en " + textoDormir(e).lowercase() else "La música se pausa sola (bajando el volumen) cuando digas.",
                color = C.tenue, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                Text("$m minutos", color = C.texto, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { vm.dormir(m); cerrar() }.padding(horizontal = 24.dp, vertical = 14.dp))
            }
            Text("Al terminar la canción", color = C.texto, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable { vm.dormir(-1); cerrar() }.padding(horizontal = 24.dp, vertical = 14.dp))
            if (e.activo) Text("Apagar el temporizador", color = C.peligro, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable { vm.dormir(0); cerrar() }.padding(horizontal = 24.dp, vertical = 14.dp))
        }
    }
}

/** «A continuación»: lo que suena y lo que viene. Tocar una salta ahí; la X la quita de la cola. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Cola(vm: VistaModelo, s: Sonando, cerrar: () -> Unit) {
    val siguientes by vm.siguientes.collectAsStateWithLifecycle()
    val origen by vm.origen.collectAsStateWithLifecycle()
    val radio by vm.radio.collectAsStateWithLifecycle()
    ModalBottomSheet(cerrar, containerColor = C.tarjeta, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("A continuación", color = C.blanco, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                Text("Sonando ahora", color = C.texto, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Portada(s.portada, 48.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(s.titulo, color = C.acento, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artista, color = C.tenue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (siguientes.isNotEmpty()) Text("Lo que sigue" + if (origen.isNotEmpty()) " · de $origen" else "", color = C.texto, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                else Text(if (radio) "Al acabarse, la radio sigue con canciones parecidas."
                    else "No viene nada después. En el menú de una canción: «Tocar después» o «Agregar a la cola».", color = C.tenue, fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp))
            }
            itemsIndexed(siguientes, key = { i, e -> "${e.indice}-${e.pista.uri}-$i" }) { i, e ->
                // 1.4: donde empieza la radio sin fin
                if (e.radio && (i == 0 || !siguientes[i - 1].radio)) Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Radio, null, tint = C.acento, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Radio", color = C.texto, fontWeight = FontWeight.Bold)
                    Text(" · parecidas a lo que escuchabas", color = C.tenue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.animateItem().fillMaxWidth().clickable { vm.saltarA(e.indice) }.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Portada(e.pista.portada, 48.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(e.pista.titulo, color = C.texto, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.pista.artista, color = C.tenue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton({ vm.quitarDeCola(e.indice) }) { Icon(Icons.Rounded.Close, "Quitar de la cola", tint = C.tenue) }
                }
            }
        }
    }
}
