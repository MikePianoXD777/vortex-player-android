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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
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
fun FilaPista(p: Pista, sonando: Boolean, alTocar: () -> Unit, gusta: Boolean = false, menu: @Composable ColumnScope.(cerrar: () -> Unit) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = alTocar).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Portada(p.portada, 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            val tinta by androidx.compose.animation.animateColorAsState(if (sonando) C.acento else C.texto, label = "sonando")
            Text(p.titulo, color = tinta, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (gusta) Icon(Icons.Rounded.Favorite, "Te gusta", tint = C.acento, modifier = Modifier.padding(end = 4.dp).size(14.dp))
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

/** 1.3: lo que va en el menú de cualquier canción: ❤️ y la cola «A continuación». */
@Composable
fun ColumnScope.OpcionesCancion(vm: VistaModelo, p: Pista, gusta: Boolean, cerrar: () -> Unit) {
    DropdownMenuItem({ Text(if (gusta) "Quitar de Tus me gusta" else "Guardar en Tus me gusta") }, { cerrar(); vm.alternarMeGusta(p.uri) },
        leadingIcon = { Icon(if (gusta) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (gusta) C.acento else C.suave) })
    DropdownMenuItem({ Text("Tocar después") }, { cerrar(); vm.tocarDespues(p) },
        leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, null) })
    DropdownMenuItem({ Text("Agregar a la cola") }, { cerrar(); vm.agregarACola(p) },
        leadingIcon = { Icon(Icons.Rounded.AddToQueue, null) })
    // 1.5: «Iniciar radio» y compartir (por WhatsApp o lo que elijas)
    DropdownMenuItem({ Text("Iniciar radio") }, { cerrar(); vm.iniciarRadio(p) },
        leadingIcon = { Icon(Icons.Rounded.Radio, null) })
    DropdownMenuItem({ Text("Compartir") }, { cerrar(); vm.compartir(p) },
        leadingIcon = { Icon(Icons.Rounded.Share, null) })
}

@Composable
fun PantallaBiblioteca(vm: VistaModelo, verResumen: () -> Unit = {}) {
    val pistas by vm.pistas.collectAsStateWithLifecycle()
    val cargando by vm.cargando.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val sonando by vm.sonando.collectAsStateWithLifecycle()
    val meGusta by vm.meGusta.collectAsStateWithLifecycle()
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
        AvisoNube(vm)
        AvisoResumen(vm, verResumen)
        TarjetaMezcla(vm)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filtro, { filtro = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Buscar en tu música") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton({ if (vistas.isNotEmpty()) vm.tocar(vistas, (vistas.indices).random(), aleatorio = true, de = "Tu música") }) {
                Icon(Icons.Rounded.Shuffle, "Aleatorio")
            }
        }
        if (!cargando && pistas.isEmpty()) Vacio(Icons.Rounded.LibraryMusic, "Todavía no hay música",
            "Busca canciones en «Buscar» o copia tus FLAC a la carpeta Música.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(vistas, key = { it.uri }) { p ->
                Box(Modifier.animateItem()) { FilaPista(p, p.uri == sonando.uri, { vm.tocar(vistas, vistas.indexOf(p), de = "Tu música") }, p.uri in meGusta) { cerrar ->
                    OpcionesCancion(vm, p, p.uri in meGusta, cerrar)
                    DropdownMenuItem({ Text("Agregar a una playlist") }, { cerrar(); paraPlaylist = p },
                        leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) })
                    if (p.videoId.isNotEmpty()) DropdownMenuItem({ Text("Borrar del teléfono") }, { cerrar(); paraBorrar = p },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = C.peligro) })
                } }
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

/** 1.5: «Tu mezcla del día»: lo que más escuchas revuelto con parecidas que no tienes. */
@Composable
private fun TarjetaMezcla(vm: VistaModelo) {
    val m by vm.mezcla.collectAsStateWithLifecycle()
    val pistas by vm.pistas.collectAsStateWithLifecycle()
    val portadas = remember(m, pistas) { com.vortexine.player.reproductor.Mezcla.portadas(m, pistas) }
    val cuantas = remember(m, pistas) { com.vortexine.player.reproductor.Mezcla.items(m, pistas).size }
    if (cuantas == 0) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(C.tarjeta)
        .clickable { vm.tocarMezcla() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp))) {
            if (portadas.size >= 4) Column {
                Row { Portada(portadas[0], 28.dp, 0.dp); Portada(portadas[1], 28.dp, 0.dp) }
                Row { Portada(portadas[2], 28.dp, 0.dp); Portada(portadas[3], 28.dp, 0.dp) }
            } else Portada(portadas.firstOrNull().orEmpty(), 56.dp, 4.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Tu mezcla del día", color = C.blanco, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("$cuantas canciones · lo que más escuchas y ${m.nuevas.size} parecidas que no tienes", color = C.tenue,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(C.morado), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Tocar tu mezcla del día", tint = C.blanco)
        }
    }
}

/** 1.5: «Lo nuevo de tus artistas»: álbumes y singles de este año; se escuchan sin bajarlos o se bajan enteros. */
@Composable
private fun NovedadesArtistas(vm: VistaModelo) {
    val lista by vm.novedades.collectAsStateWithLifecycle()
    if (lista.isEmpty()) return
    Text("Lo nuevo de tus artistas", color = C.blanco, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lista.size) { i ->
            val l = lista[i]
            Column(Modifier.width(140.dp).clickable { vm.tocarLanzamiento(l) }) {
                Box {
                    Portada(l.portada.replace("=w226-h226", "=w320-h320"), 140.dp, 6.dp)
                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(34.dp).clip(RoundedCornerShape(17.dp))
                        .background(C.fondo.copy(alpha = 0.7f)).clickable { vm.bajarLanzamiento(l) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Download, "Bajarlo a tu biblioteca", tint = C.acento, modifier = Modifier.size(20.dp))
                    }
                }
                Text(l.titulo, color = C.texto, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
                Text(listOf(l.tipo, if (l.anio > 0) "${l.anio}" else "", l.artista).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = C.tenue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
                    color = C.acento, trackColor = C.borde)
                c.id in bajadas -> Text("En tu biblioteca", color = C.exito, fontSize = 12.sp)
            }
        }
        if (c.id in bajadas) Icon(Icons.Rounded.CheckCircle, "Ya la tienes", tint = C.exito, modifier = Modifier.padding(12.dp))
        else if (bajando?.error?.isNotEmpty() == true) IconButton({ vm.olvidarError(c.id); vm.bajar(c, playlist) }) { Icon(Icons.Rounded.Refresh, "Reintentar", tint = C.acento) }
        else IconButton({ vm.bajar(c, playlist) }, enabled = bajando == null) { Icon(Icons.Rounded.Download, "Bajar", tint = if (bajando == null) C.acento else C.borde) }
        IconButton({ alPlaylist(c) }) { Icon(Icons.Rounded.PlaylistAdd, "Bajar a una playlist", tint = C.suave) }
    }
}

@Composable
fun PantallaBuscar(vm: VistaModelo) {
    val resultados by vm.resultados.collectAsStateWithLifecycle()
    val buscando by vm.buscando.collectAsStateWithLifecycle()
    val enlace by vm.enlace.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val paraTi by vm.paraTi.collectAsStateWithLifecycle()
    val paraTiDe by vm.paraTiDe.collectAsStateWithLifecycle()
    val cargandoParaTi by vm.cargandoParaTi.collectAsStateWithLifecycle()
    val bajadas by vm.bajadas.collectAsStateWithLifecycle()
    var texto by rememberSaveable { mutableStateOf("") }
    var paraPlaylist by remember { mutableStateOf<Cancion?>(null) }
    val sinBusqueda = !buscando && resultados.isEmpty() && enlace == null
    LaunchedEffect(sinBusqueda) { if (sinBusqueda) vm.recomendarParaTi() }
    val foco = LocalFocusManager.current
    val teclado = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    Column {
        Titulo("Buscar", "Canciones de YouTube Music, o pega un enlace de YouTube o Spotify")
        OutlinedTextField(texto, { texto = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            placeholder = { Text("Canción, artista o enlace") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (texto.isNotEmpty()) IconButton({ texto = ""; vm.limpiarBusqueda() }) { Icon(Icons.Rounded.Close, "Borrar") } },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { teclado?.hide(); foco.clearFocus(); vm.buscar(texto) }))
        if (buscando) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = C.acento, trackColor = C.borde)
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
            if (sinBusqueda) {
                item { Column { NovedadesArtistas(vm) } }
                item {
                    Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Para ti", color = C.blanco, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(if (paraTiDe.isEmpty()) "Según lo último que escuchaste" else "Porque escuchaste " + paraTiDe.joinToString(", "),
                                color = C.tenue, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton({ vm.recomendarParaTi(otras = true) }, enabled = !cargandoParaTi) { Text("Otras") }
                    }
                    if (cargandoParaTi) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = C.acento, trackColor = C.borde)
                }
                // 1.3: la que bajas se quita de la lista (ya la tienes)
                items(paraTi.filter { it.id !in bajadas }, key = { "t" + it.id }) { c -> Box(Modifier.animateItem()) { FilaCancion(c, vm) { paraPlaylist = it } } }
                if (paraTi.isEmpty() && !cargandoParaTi) item {
                    Vacio(Icons.Rounded.Headphones, "Busca lo que quieras oír",
                        "Toca la portada para escuchar 30 segundos antes de bajarla.")
                }
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
    val portadas by vm.portadas.collectAsStateWithLifecycle()
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
                Row(Modifier.animateItem().fillMaxWidth().clickable { abrir(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Mosaico(portadas[p.id].orEmpty(), 64.dp, meGusta = p.meGusta)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(p.nombre, color = C.texto, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Playlist · " + cuantas(p.cuantas), color = C.tenue, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    if (nueva) PedirTexto("Nueva playlist", "", "Crear", { vm.nuevaPlaylist(it); nueva = false }) { nueva = false }
}

@Composable
fun PantallaPlaylist(vm: VistaModelo, id: Long, buscarFuera: (String) -> Unit = {}, volver: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val pistas by vm.pistas.collectAsStateWithLifecycle()
    val sonando by vm.sonando.collectAsStateWithLifecycle()
    val recomendadas by vm.recomendadas.collectAsStateWithLifecycle()
    val recomendando by vm.recomendando.collectAsStateWithLifecycle()
    val portadas by vm.portadas.collectAsStateWithLifecycle()
    val meGusta by vm.meGusta.collectAsStateWithLifecycle()
    val bajadas by vm.bajadas.collectAsStateWithLifecycle()
    val p = playlists.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { volver() }; return }
    val mosaico = portadas[id].orEmpty()
    val color = if (p.meGusta) C.morado else colorAnimado(mosaico.firstOrNull().orEmpty())
    val dentro = remember(pistas, playlists) { vm.pistasDe(id) }
    var renombrar by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf(false) }
    var paraPlaylist by remember { mutableStateOf<Cancion?>(null) }
    var agregar by remember { mutableStateOf(false) }

    LaunchedEffect(id, dentro.isNotEmpty()) { if (dentro.isNotEmpty() && id !in recomendadas) vm.recomendar(id) }

    val sonandoAqui = sonando.tocando && dentro.any { it.uri == sonando.uri }
    LazyColumn(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color, C.fondo), endY = 1400f))) {
        item {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(volver) { Icon(Icons.Rounded.ArrowBack, "Volver", tint = C.blanco) }
                Spacer(Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.graphicsLayerSombra()) { Mosaico(mosaico, 220.dp, 4.dp, p.meGusta) }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(p.nombre, color = C.blanco, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(cuantas(dentro.size) + duracionTotal(dentro), color = C.suave, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.tocar(dentro, dentro.indices.random(), aleatorio = true, de = p.nombre) }, enabled = dentro.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = C.suave)
                }
                // 1.6: la playlist como enlace, para mandársela a alguien
                IconButton({ vm.compartirPlaylist(p.nombre, dentro) }, enabled = dentro.isNotEmpty()) {
                    Icon(Icons.Rounded.Share, "Compartir", tint = C.suave)
                }
                // 1.6.2: agregarle canciones desde aquí (antes solo desde tu música o Buscar)
                IconButton({ agregar = true }) { Icon(Icons.Rounded.Add, "Agregar canciones", tint = C.suave) }
                if (!p.meGusta) {
                    IconButton({ renombrar = true }) { Icon(Icons.Rounded.Edit, "Renombrar", tint = C.suave) }
                    IconButton({ borrar = true }) { Icon(Icons.Rounded.DeleteOutline, "Borrar playlist", tint = C.suave) }
                }
                Spacer(Modifier.weight(1f))
                BotonTocar(sonandoAqui, 56.dp, dentro.isNotEmpty()) {
                    if (dentro.any { it.uri == sonando.uri }) vm.pausarOSeguir() else vm.tocar(dentro, 0, de = p.nombre)
                }
            }
        }
        items(dentro, key = { "p" + it.uri }) { t ->
            Box(Modifier.animateItem()) { FilaPista(t, t.uri == sonando.uri, { vm.tocar(dentro, dentro.indexOf(t), de = p.nombre) }, t.uri in meGusta) { cerrar ->
                OpcionesCancion(vm, t, t.uri in meGusta, cerrar)
                if (!p.meGusta) DropdownMenuItem({ Text("Quitar de la playlist") }, { cerrar(); vm.quitarDe(id, t) },
                    leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null) })
            } }
        }
        if (dentro.isEmpty()) item {
            Vacio(Icons.Rounded.QueueMusic, "Está vacía", "Busca en tu música y agrégale canciones.")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalButton({ agregar = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Agregar canciones") }
            }
        }
        if (dentro.isNotEmpty()) item {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recomendadas para esta playlist", color = C.texto, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton({ vm.recomendar(id) }, enabled = recomendando == null) { Text("Otras") }
            }
            if (recomendando == id) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = C.lila, trackColor = C.borde)
        }
        items(recomendadas[id].orEmpty().filter { it.id !in bajadas }, key = { "r" + it.id }) { c -> Box(Modifier.animateItem()) { FilaCancion(c, vm, id) { vm.bajar(it, id) } } }
    }
    if (agregar) AgregarCanciones(p.nombre, pistas, dentro.map { it.uri }.toSet(), { vm.agregarA(id, it) },
        { agregar = false; buscarFuera(it) }) { agregar = false }
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
        Text("Perfiles", color = C.texto, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp))
        Perfiles(estado.perfiles, estado.perfil) { Ecualizador.perfil(it); estado = Ecualizador.estado() }
        Text(if (estado.perfil == Ecualizador.PERSONALIZADO) "Personalizado" else estado.perfil, color = C.tenue, fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
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
                    colors = SliderDefaults.colors(thumbColor = C.lila, activeTrackColor = C.lila, inactiveTrackColor = C.borde))
            }
        }
    }
}

/** Los perfiles en dos filas que se deslizan de lado; el elegido se marca en morado. */
@Composable
private fun Perfiles(nombres: List<String>, actual: String, alElegir: (String) -> Unit) {
    val filas = nombres.chunked((nombres.size + 1) / 2)
    Column(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        filas.forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fila.forEach { n ->
                    FilterChip(n == actual, { alElegir(n) }, { Text(n) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.morado, selectedLabelColor = C.blanco,
                            containerColor = C.tarjeta, labelColor = C.suave),
                        border = null)
                }
            }
        }
    }
}

private fun duracionTotal(l: List<Pista>): String {
    val min = l.sumOf { it.duracionMs } / 60_000
    return when {
        min <= 0 -> ""
        min < 60 -> " · $min min"
        else -> " · ${min / 60} h ${min % 60} min"
    }
}

/** Sombra suave bajo la portada grande de la playlist. */
private fun Modifier.graphicsLayerSombra() = graphicsLayer {
    shadowElevation = 32f; shape = RoundedCornerShape(4.dp); clip = false
}
