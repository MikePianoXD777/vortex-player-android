package com.vortexine.player.interfaz

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortexine.player.motor.Cancion
import com.vortexine.player.motor.Pista
import com.vortexine.player.reproductor.Ecualizador

// --- Biblioteca ---------------------------------------------------------------------------------------

@Composable
fun FilaPista(p: Pista, sonando: Boolean, alTocar: () -> Unit, menu: @Composable ColumnScope.(cerrar: () -> Unit) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = alTocar).background(if (sonando) C.realce else C.fondo)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Portada(p.portada, 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.titulo, color = if (sonando) C.azul else C.texto, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Etiqueta(p.etiqueta, p.nivel)
                Text((if (p.etiqueta.isNotEmpty()) "  " else "") + p.artista, color = C.tenue, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton({ abierto = true }) { Icon(Icons.Rounded.MoreVert, "Más", tint = C.tenue) }
            DropdownMenu(abierto, { abierto = false }, containerColor = C.tarjeta) { menu { abierto = false } }
        }
    }
}

@Composable
fun PantallaBiblioteca(vm: VistaModelo) {
    val pistas by vm.pistas.collectAsStateWithLifecycle()
    val cargando by vm.cargando.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val sonando by vm.sonando.collectAsStateWithLifecycle()
    var filtro by rememberSaveable { mutableStateOf("") }
    var paraPlaylist by remember { mutableStateOf<Pista?>(null) }
    var paraBorrar by remember { mutableStateOf<Pista?>(null) }
    val vistas = remember(pistas, filtro) {
        val f = filtro.trim().lowercase()
        if (f.isEmpty()) pistas else pistas.filter { f in it.titulo.lowercase() || f in it.artista.lowercase() || f in it.album.lowercase() }
    }
    val hires = remember(pistas) { pistas.count { it.nivel == "hires" } }

    Column {
        Titulo("Tu música", if (cargando) "Leyendo tu música…" else cuantas(pistas.size) + if (hires > 0) " · $hires Hi-Res" else "")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filtro, { filtro = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Buscar en tu música") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton({ if (vistas.isNotEmpty()) vm.tocar(vistas, (vistas.indices).random(), aleatorio = true) }) {
                Icon(Icons.Rounded.Shuffle, "Aleatorio")
            }
        }
        if (!cargando && pistas.isEmpty()) Vacio(Icons.Rounded.LibraryMusic, "Todavía no hay música",
            "Busca canciones en «Buscar» o copia tus FLAC a la carpeta Música.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(vistas, key = { it.uri }) { p ->
                FilaPista(p, p.uri == sonando.uri, { vm.tocar(vistas, vistas.indexOf(p)) }) { cerrar ->
                    DropdownMenuItem({ Text("Agregar a una playlist") }, { cerrar(); paraPlaylist = p },
                        leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) })
                    if (p.videoId.isNotEmpty()) DropdownMenuItem({ Text("Borrar del teléfono") }, { cerrar(); paraBorrar = p },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = C.peligro) })
                }
            }
        }
    }
    paraPlaylist?.let { p ->
        ElegirPlaylist(playlists, { id, nueva -> vm.agregarA(id ?: vm.nuevaPlaylist(nueva!!), p); paraPlaylist = null }) { paraPlaylist = null }
    }
    paraBorrar?.let { p ->
        AlertDialog({ paraBorrar = null }, containerColor = C.tarjeta, title = { Text("¿Borrar «${p.titulo}»?") },
            text = { Text("Se quita del teléfono y de tus playlists.") },
            confirmButton = { TextButton({ vm.borrar(p); paraBorrar = null }) { Text("Borrar", color = C.peligro) } },
            dismissButton = { TextButton({ paraBorrar = null }) { Text("Cancelar") } })
    }
}

// --- Buscar -------------------------------------------------------------------------------------------

@Composable
fun FilaCancion(c: Cancion, vm: VistaModelo, playlist: Long? = null, alPlaylist: (Cancion) -> Unit) {
    val bajadas by vm.bajadas.collectAsStateWithLifecycle()
    val escuchando by vm.escuchando.collectAsStateWithLifecycle()
    val cola by vm.cola.collectAsStateWithLifecycle()
    val bajando = cola.firstOrNull { it.cancion.id == c.id }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable { vm.escuchar(c) }) {
            Portada(c.portada, 52.dp)
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(C.fondo.copy(alpha = 0.45f)))
            Icon(if (escuchando == c.id) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, "Escuchar 30 segundos", tint = C.texto)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(c.titulo, color = C.texto, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(c.artistas, c.album, if (c.duracion > 0) tiempo(c.duracion * 1000L) else "").filter { it.isNotEmpty() }
                .joinToString(" · "), color = C.tenue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                bajando != null && bajando.error.isNotEmpty() -> Text(bajando.error, color = C.peligro, fontSize = 12.sp, maxLines = 2)
                bajando != null -> LinearProgressIndicator({ bajando.progreso }, Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = C.azul, trackColor = C.borde)
                c.id in bajadas -> Text("En tu biblioteca", color = C.exito, fontSize = 12.sp)
            }
        }
        if (c.id in bajadas) Icon(Icons.Rounded.CheckCircle, "Ya la tienes", tint = C.exito, modifier = Modifier.padding(12.dp))
        else if (bajando?.error?.isNotEmpty() == true) IconButton({ vm.olvidarError(c.id); vm.bajar(c, playlist) }) { Icon(Icons.Rounded.Refresh, "Reintentar", tint = C.azul) }
        else IconButton({ vm.bajar(c, playlist) }, enabled = bajando == null) { Icon(Icons.Rounded.Download, "Bajar", tint = if (bajando == null) C.azul else C.borde) }
        IconButton({ alPlaylist(c) }) { Icon(Icons.Rounded.PlaylistAdd, "Bajar a una playlist", tint = C.suave) }
    }
}

@Composable
fun PantallaBuscar(vm: VistaModelo) {
    val resultados by vm.resultados.collectAsStateWithLifecycle()
    val buscando by vm.buscando.collectAsStateWithLifecycle()
    val enlace by vm.enlace.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var texto by rememberSaveable { mutableStateOf("") }
    var paraPlaylist by remember { mutableStateOf<Cancion?>(null) }
    val foco = LocalFocusManager.current
    val teclado = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    Column {
        Titulo("Buscar", "Canciones de YouTube Music, o pega un enlace de YouTube o Spotify")
        OutlinedTextField(texto, { texto = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            placeholder = { Text("Canción, artista o enlace") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (texto.isNotEmpty()) IconButton({ texto = "" }) { Icon(Icons.Rounded.Close, "Borrar") } },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { teclado?.hide(); foco.clearFocus(); vm.buscar(texto) }))
        if (buscando) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = C.azul, trackColor = C.borde)
        LazyColumn(Modifier.fillMaxSize()) {
            enlace?.let { (nombre, lista) ->
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = C.tarjeta)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(nombre, color = C.texto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(cuantas(lista.size), color = C.tenue, fontSize = 13.sp)
                            if (lista.size > 1) Row(Modifier.padding(top = 12.dp)) {
                                Button({ vm.bajarTodas(lista, null) }) { Text("Bajar todas") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton({ vm.bajarTodas(lista, nombre) }) { Text("Como playlist") }
                            }
                        }
                    }
                }
                items(lista, key = { "e" + it.id }) { FilaCancion(it, vm) { c -> paraPlaylist = c } }
            }
            items(resultados, key = { it.id }) { FilaCancion(it, vm) { c -> paraPlaylist = c } }
            if (!buscando && resultados.isEmpty() && enlace == null) item {
                Vacio(Icons.Rounded.Headphones, "Busca lo que quieras oír",
                    "Toca la portada para escuchar 30 segundos antes de bajarla.")
            }
        }
    }
    paraPlaylist?.let { c ->
        ElegirPlaylist(playlists, { id, nueva -> vm.bajar(c, id ?: vm.nuevaPlaylist(nueva!!)); paraPlaylist = null }) { paraPlaylist = null }
    }
}

// --- Playlists ----------------------------------------------------------------------------------------

@Composable
fun PantallaPlaylists(vm: VistaModelo, abrir: (Long) -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var nueva by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Titulo("Playlists", if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists") }
            FilledTonalButton({ nueva = true }, Modifier.padding(end = 16.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Nueva")
            }
        }
        if (playlists.isEmpty()) Vacio(Icons.Rounded.QueueMusic, "Sin playlists", "Crea una y agrégale canciones desde tu música o Buscar.")
        LazyColumn {
            items(playlists, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.nombre, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(cuantas(p.cuantas), color = C.tenue) },
                    leadingContent = {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(C.realceFuerte), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.QueueMusic, null, tint = C.violeta)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = C.fondo),
                    modifier = Modifier.clickable { abrir(p.id) },
                )
            }
        }
    }
    if (nueva) PedirTexto("Nueva playlist", "", "Crear", { vm.nuevaPlaylist(it); nueva = false }) { nueva = false }
}

@Composable
fun PantallaPlaylist(vm: VistaModelo, id: Long, volver: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val pistas by vm.pistas.collectAsStateWithLifecycle()
    val sonando by vm.sonando.collectAsStateWithLifecycle()
    val recomendadas by vm.recomendadas.collectAsStateWithLifecycle()
    val recomendando by vm.recomendando.collectAsStateWithLifecycle()
    val p = playlists.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { volver() }; return }
    val dentro = remember(pistas, playlists) { vm.pistasDe(id) }
    var renombrar by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf(false) }
    var paraPlaylist by remember { mutableStateOf<Cancion?>(null) }

    LaunchedEffect(id, dentro.isNotEmpty()) { if (dentro.isNotEmpty() && id !in recomendadas) vm.recomendar(id) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(volver) { Icon(Icons.Rounded.ArrowBack, "Volver") }
                Spacer(Modifier.weight(1f))
                IconButton({ renombrar = true }) { Icon(Icons.Rounded.Edit, "Renombrar", tint = C.tenue) }
                IconButton({ borrar = true }) { Icon(Icons.Rounded.Delete, "Borrar playlist", tint = C.peligro) }
            }
            Titulo(p.nombre, cuantas(dentro.size))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Button({ vm.tocar(dentro, 0) }, enabled = dentro.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Tocar")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton({ vm.tocar(dentro, dentro.indices.random(), aleatorio = true) }, enabled = dentro.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Aleatorio")
                }
            }
        }
        items(dentro, key = { "p" + it.uri }) { t ->
            FilaPista(t, t.uri == sonando.uri, { vm.tocar(dentro, dentro.indexOf(t)) }) { cerrar ->
                DropdownMenuItem({ Text("Quitar de la playlist") }, { cerrar(); vm.quitarDe(id, t) },
                    leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null) })
            }
        }
        if (dentro.isEmpty()) item { Vacio(Icons.Rounded.QueueMusic, "Está vacía", "Agrégale canciones desde tu música o desde Buscar.") }
        if (dentro.isNotEmpty()) item {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recomendadas para esta playlist", color = C.texto, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton({ vm.recomendar(id) }, enabled = recomendando == null) { Text("Otras") }
            }
            if (recomendando == id) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = C.violeta, trackColor = C.borde)
        }
        items(recomendadas[id].orEmpty(), key = { "r" + it.id }) { c -> FilaCancion(c, vm, id) { vm.bajar(it, id) } }
    }
    if (renombrar) PedirTexto("Renombrar", p.nombre, "Guardar", { vm.renombrar(id, it); renombrar = false }) { renombrar = false }
    if (borrar) AlertDialog({ borrar = false }, containerColor = C.tarjeta, title = { Text("¿Borrar «${p.nombre}»?") },
        text = { Text("Las canciones se quedan en tu música.") },
        confirmButton = { TextButton({ borrar = false; vm.borrarPlaylist(id); volver() }) { Text("Borrar", color = C.peligro) } },
        dismissButton = { TextButton({ borrar = false }) { Text("Cancelar") } })
}

// --- Ecualizador --------------------------------------------------------------------------------------

@Composable
fun PantallaEcualizador() {
    var estado by remember { mutableStateOf(Ecualizador.estado()) }
    LaunchedEffect(estado.disponible) {   // el reproductor puede arrancar con esta pantalla abierta
        while (!estado.disponible) { kotlinx.coroutines.delay(1000); estado = Ecualizador.estado() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Titulo("Ecualizador", "El de tu teléfono, sobre lo que suena en Vortex Player") }
            Switch(estado.encendido, { Ecualizador.encender(it); estado = Ecualizador.estado() }, Modifier.padding(end = 20.dp),
                enabled = estado.disponible)
        }
        if (!estado.disponible) {
            Vacio(Icons.Rounded.GraphicEq, "Toca una canción primero", "El ecualizador se prende junto con el reproductor.")
            return
        }
        if (estado.preajustes.isNotEmpty()) LazyRowPreajustes(estado.preajustes) { Ecualizador.preajuste(it); estado = Ecualizador.estado() }
        estado.bandas.forEachIndexed { i, b ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row {
                    Text(if (b.hz >= 1000) "${b.hz / 1000} kHz" else "${b.hz} Hz", color = C.suave, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("%+.1f dB".format(b.nivel / 100f), color = C.tenue, fontSize = 13.sp)
                }
                Slider(b.nivel.toFloat(), { v ->
                    Ecualizador.poner(i, v.toInt())
                    estado = estado.copy(bandas = estado.bandas.toMutableList().also { it[i] = b.copy(nivel = v.toInt()) })
                }, valueRange = estado.min.toFloat()..estado.max.toFloat(), enabled = estado.encendido,
                    colors = SliderDefaults.colors(thumbColor = C.violeta, activeTrackColor = C.violeta, inactiveTrackColor = C.borde))
            }
        }
    }
}

@Composable
private fun LazyRowPreajustes(nombres: List<String>, alElegir: (Int) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(nombres.size) { i -> AssistChip({ alElegir(i) }, { Text(nombres[i]) }) }
    }
}
