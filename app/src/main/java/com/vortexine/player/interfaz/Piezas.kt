package com.vortexine.player.interfaz

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.vortexine.player.motor.Pista
import com.vortexine.player.motor.Playlist

/** La portada, o una nota musical si no hay. */
@Composable
fun Portada(url: String, tam: Dp, radio: Dp = 8.dp) {
    val forma = RoundedCornerShape(radio)
    val vacia: @Composable () -> Unit = {
        Box(Modifier.size(tam).clip(forma).background(C.realce), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = C.tenue, modifier = Modifier.size(tam / 2))
        }
    }
    if (url.isEmpty()) vacia() else SubcomposeAsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(tam).clip(forma), error = { vacia() }, loading = { vacia() },
    )
}

/** «Hi-Res 24/96», «CD 16/44,1», «Opus 160 kbps», con el color de su nivel. */
@Composable
fun Etiqueta(texto: String, nivel: String) {
    if (texto.isEmpty()) return
    val color = C.deNivel(nivel)
    Text(texto, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

fun cuantas(n: Int) = if (n == 1) "1 canción" else "$n canciones"

fun tiempo(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun Titulo(texto: String, sub: String = "") {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
        Text(texto, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.texto)
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = C.tenue)
    }
}

@Composable
fun Vacio(icono: androidx.compose.ui.graphics.vector.ImageVector, texto: String, sub: String = "") {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icono, null, tint = C.tenue, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(texto, color = C.suave, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (sub.isNotEmpty()) Text(sub, color = C.tenue, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Elegir a qué playlist va (o crear una nueva). */
@Composable
fun ElegirPlaylist(playlists: List<Playlist>, alElegir: (Long?, String?) -> Unit, alCerrar: () -> Unit) {
    var nueva by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = alCerrar,
        containerColor = C.tarjeta,
        title = { Text(if (nueva == null) "Agregar a una playlist" else "Nueva playlist") },
        text = {
            if (nueva != null) {
                OutlinedTextField(nueva!!, { nueva = it }, singleLine = true, placeholder = { Text("Nombre") })
            } else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    ListItem(headlineContent = { Text("Nueva playlist…") },
                        leadingContent = { Icon(Icons.Rounded.Add, null, tint = C.acento) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { nueva = "" })
                }
                items(playlists, key = { it.id }) { p ->
                    ListItem(headlineContent = { Text(p.nombre) }, supportingContent = { Text(cuantas(p.cuantas)) },
                        leadingContent = { Icon(Icons.Rounded.QueueMusic, null, tint = C.lila) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { alElegir(p.id, null) })
                }
            }
        },
        confirmButton = {
            if (nueva != null) TextButton({ alElegir(null, nueva!!.ifBlank { "Mi playlist" }) }) { Text("Crear") }
        },
        dismissButton = { TextButton(alCerrar) { Text("Cancelar") } },
    )
}

@Composable
fun PedirTexto(titulo: String, inicial: String, boton: String, alAceptar: (String) -> Unit, alCerrar: () -> Unit) {
    var t by remember { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = alCerrar, containerColor = C.tarjeta,
        title = { Text(titulo) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ alAceptar(t) }) { Text(boton) } },
        dismissButton = { TextButton(alCerrar) { Text("Cancelar") } },
    )
}


/** 1.6.2: agregarle canciones a una playlist desde la misma playlist: buscar en tu música y «Agregar». */
@Composable
fun AgregarCanciones(nombre: String, pistas: List<Pista>, dentro: Set<String>, alAgregar: (Pista) -> Unit, alBuscarFuera: (String) -> Unit, alCerrar: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val lista = remember(pistas, dentro, q) { com.vortexine.player.motor.Biblioteca.paraAgregar(pistas, dentro, q) }
    AlertDialog(
        onDismissRequest = alCerrar,
        containerColor = C.tarjeta,
        title = { Text("Agregar a «$nombre»", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Busca en tu música") })
                if (lista.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (q.isBlank()) "Todavía no hay música en tu teléfono" else "No la tienes en tu música", color = C.suave)
                    TextButton({ alBuscarFuera(q) }) { Text("Buscar en YouTube Music") }
                } else LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(lista, key = { it.first.uri }) { (t, ya) ->
                        ListItem(
                            headlineContent = { Text(t.titulo, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(t.artista, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Portada(t.portada, 42.dp, 6.dp) },
                            trailingContent = {
                                if (ya) Text("Ya está", color = C.tenue, fontSize = 13.sp)
                                else FilledTonalButton({ alAgregar(t) }) { Text("Agregar") }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(alCerrar) { Text("Listo") } },
    )
}
