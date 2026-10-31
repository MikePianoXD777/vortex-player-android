package com.vortexine.player.interfaz

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.vortexine.player.VortexApp
import com.vortexine.player.motor.Cancion
import com.vortexine.player.motor.Descargas
import com.vortexine.player.motor.Pista
import com.vortexine.player.motor.Playlist
import com.vortexine.player.motor.YtMusic
import com.vortexine.player.reproductor.ServicioReproduccion
import com.vortexine.player.motor.Actualizador
import com.vortexine.player.motor.Cuenta
import com.vortexine.player.motor.Sincro
import com.vortexine.player.segundo.Trabajo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Lo que suena ahora, para la barra de abajo y la pantalla del reproductor. */
data class Sonando(
    val uri: String = "",
    val titulo: String = "",
    val artista: String = "",
    val portada: String = "",
    val etiqueta: String = "",
    val nivel: String = "",
    val tocando: Boolean = false,
    val posicion: Long = 0,
    val duracion: Long = 0,
    val aleatorio: Boolean = false,
    val repetir: Int = Player.REPEAT_MODE_OFF,
    val hayAnterior: Boolean = false,
    val haySiguiente: Boolean = false,
)

data class Bajando(val cancion: Cancion, val progreso: Float, val error: String = "")

class VistaModelo(app: Application) : AndroidViewModel(app) {
    private val vapp = app as VortexApp
    private val bib = vapp.biblioteca

    val pistas = MutableStateFlow<List<Pista>>(emptyList())
    val cargando = MutableStateFlow(true)
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val bajadas = MutableStateFlow<Set<String>>(emptySet())
    val sonando = MutableStateFlow(Sonando())
    val aviso = MutableStateFlow<String?>(null)

    val resultados = MutableStateFlow<List<Cancion>>(emptyList())
    val buscando = MutableStateFlow(false)
    val enlace = MutableStateFlow<Pair<String, List<Cancion>>?>(null)   // lo que trae un enlace pegado
    val recomendadas = MutableStateFlow<Map<Long, List<Cancion>>>(emptyMap())
    val recomendando = MutableStateFlow<Long?>(null)
    val escuchando = MutableStateFlow<String?>(null)   // la canción que suena 30 s antes de bajarla

    private var control: MediaController? = null

    // 1.1: la cuenta de Google, la sincronización y las actualizaciones
    val cuenta = Cuenta.datos
    val sincro = Sincro.estado
    val nueva = Actualizador.hay
    val instalando = Actualizador.instalando

    init {
        recargar()
        conectar()
        viewModelScope.launch {   // lo que baja la sincronización aparece en la biblioteca
            Sincro.estado.map { it.vuelta }.distinctUntilChanged().drop(1).collect { recargar() }
        }
        viewModelScope.launch {
            while (isActive) {
                control?.let { c -> if (c.isPlaying) sonando.update { it.copy(posicion = c.currentPosition) } }
                delay(500)
            }
        }
    }

    fun recargar() = viewModelScope.launch {
        val (p, l, b) = withContext(Dispatchers.IO) { Triple(bib.pistas(), bib.playlists(), bib.idsBajados()) }
        pistas.value = p
        playlists.value = l
        bajadas.value = b
        cargando.value = false
        actualizarSonando()
    }

    // --- reproducir ----------------------------------------------------------------------------------

    private fun conectar() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, ServicioReproduccion::class.java))
        val futuro = MediaController.Builder(ctx, token).buildAsync()
        futuro.addListener({
            val c = runCatching { futuro.get() }.getOrNull() ?: return@addListener
            control = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = actualizarSonando()
            })
            actualizarSonando()
        }, MoreExecutors.directExecutor())
    }

    private fun actualizarSonando() {
        val c = control ?: return
        val item = c.currentMediaItem
        val uri = item?.mediaId.orEmpty()
        val p = pistas.value.firstOrNull { it.uri == uri }
        val md = item?.mediaMetadata
        sonando.value = Sonando(
            uri = uri,
            titulo = p?.titulo ?: md?.title?.toString().orEmpty(),
            artista = p?.artista ?: md?.artist?.toString().orEmpty(),
            portada = p?.portada ?: md?.artworkUri?.toString().orEmpty(),
            etiqueta = p?.etiqueta.orEmpty(), nivel = p?.nivel.orEmpty(),
            tocando = c.isPlaying, posicion = c.currentPosition, duracion = c.duration.coerceAtLeast(0),
            aleatorio = c.shuffleModeEnabled, repetir = c.repeatMode,
            hayAnterior = c.hasPreviousMediaItem() || c.currentPosition > 3000, haySiguiente = c.hasNextMediaItem(),
        )
    }

    private fun item(p: Pista) = MediaItem.Builder().setMediaId(p.uri).setUri(p.uri.toUri())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(p.titulo).setArtist(p.artista).setAlbumTitle(p.album)
            .setArtworkUri(p.portada.takeIf { it.isNotEmpty() }?.let(Uri::parse)).build()).build()

    /** Toca `lista` empezando en `desde` (la cola es la lista entera, como en la PC). */
    fun tocar(lista: List<Pista>, desde: Int, aleatorio: Boolean = false) {
        pararAvance()
        val c = control ?: return
        c.setMediaItems(lista.map(::item), desde.coerceIn(0, (lista.size - 1).coerceAtLeast(0)), 0)
        c.shuffleModeEnabled = aleatorio
        c.prepare()
        c.play()
    }

    fun pausarOSeguir() { control?.let { if (it.isPlaying) it.pause() else { pararAvance(); it.play() } } }
    fun siguiente() = control?.seekToNext()
    fun anterior() = control?.seekToPrevious()
    fun ir(ms: Long) { control?.seekTo(ms); sonando.update { it.copy(posicion = ms) } }
    fun alternarAleatorio() { control?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun alternarRepetir() {
        control?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // --- escuchar 30 s antes de bajar ----------------------------------------------------------------

    private var avance: ExoPlayer? = null

    fun escuchar(c: Cancion) {
        if (escuchando.value == c.id) { pararAvance(); return }
        pararAvance()
        control?.pause()
        escuchando.value = c.id
        viewModelScope.launch {
            val audio = runCatching { withContext(Dispatchers.IO) { Descargas.mejorAudio(c.id) } }.getOrElse {
                escuchando.value = null
                aviso.value = "No se pudo escuchar: ${mensaje(it)}"
                return@launch
            }
            if (escuchando.value != c.id) return@launch
            val j = ExoPlayer.Builder(getApplication()).build()
            avance = j
            j.setMediaItem(MediaItem.Builder().setUri(audio.url)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(30_000).build()).build())
            j.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) pararAvance() }
            })
            j.prepare()
            j.play()
        }
    }

    fun pararAvance() {
        avance?.release()
        avance = null
        escuchando.value = null
    }

    // --- buscar y bajar -------------------------------------------------------------------------------

    fun buscar(texto: String) {
        val t = texto.trim()
        if (t.isEmpty()) return
        buscando.value = true
        enlace.value = null
        viewModelScope.launch {
            try {
                val e = YtMusic.tipoDe(t)
                if (e == null) {
                    resultados.value = withContext(Dispatchers.IO) { YtMusic.buscar(t, 25) }
                    if (resultados.value.isEmpty()) aviso.value = "No encontré nada con «$t»."
                } else {
                    resultados.value = emptyList()
                    enlace.value = withContext(Dispatchers.IO) { leerEnlace(e) }
                }
            } catch (ex: Exception) {
                aviso.value = mensaje(ex)
            } finally {
                buscando.value = false
            }
        }
    }

    private suspend fun leerEnlace(e: YtMusic.Enlace): Pair<String, List<Cancion>> = when {
        e.fuente == "spotify" -> {
            val (nombre, pistasSp) = YtMusic.pistasSpotify(e.clase, e.id)
            // De Spotify solo se leen los nombres: cada una se busca en YouTube Music, 8 a la vez
            val io = Dispatchers.IO.limitedParallelism(8)
            nombre to coroutineScope {
                pistasSp.map { async(io) { runCatching { YtMusic.buscar(YtMusic.busquedaDe(it), 1).firstOrNull() }.getOrNull() } }
                    .awaitAll().filterNotNull().distinctBy { it.id }
            }
        }
        e.clase == "playlist" -> Descargas.cancionesDePlaylist(e.id)
        else -> "Canción" to listOf(Descargas.datosDe(e.id))
    }

    val cola = MutableStateFlow<List<Bajando>>(emptyList())
    private val pendientes = ArrayDeque<Pair<Cancion, Long?>>()
    private var trabajando = false

    /** Baja la canción (y la mete a la playlist, si se eligió una). Una a la vez, en orden. */
    fun bajar(c: Cancion, playlist: Long? = null) {
        if (c.id in bajadas.value) {
            if (playlist != null) viewModelScope.launch(Dispatchers.IO) {
                bib.uriDeBajada(c.id)?.let { bib.agregarA(playlist, it) }
                withContext(Dispatchers.Main) { recargar(); cambio(); aviso.value = "Agregada a la playlist." }
            }
            return
        }
        if (cola.value.any { it.cancion.id == c.id && it.error.isEmpty() }) return
        cola.update { it.filterNot { b -> b.cancion.id == c.id } + Bajando(c, 0f) }
        pendientes.addLast(c to playlist)
        if (!trabajando) siguienteDescarga()
    }

    fun bajarTodas(lista: List<Cancion>, nombrePlaylist: String?) {
        val id = nombrePlaylist?.let { n -> bib.nuevaPlaylist(n) }
        lista.forEach { bajar(it, id) }
        if (id != null) recargar()
    }

    private fun siguienteDescarga() {
        val (c, playlist) = pendientes.removeFirstOrNull() ?: run { trabajando = false; return }
        trabajando = true
        vapp.alcance.launch {
            try {
                while (c.id in Sincro.enCurso) delay(1000)   // la está bajando la sincronización
                Sincro.enCurso.add(c.id)
                val uri = try {
                    bib.uriDeBajada(c.id) ?: Descargas.bajar(getApplication(), c, progreso = { p ->
                        cola.update { l -> l.map { if (it.cancion.id == c.id) it.copy(progreso = p) else it } }
                    }).also { bib.recordarBajada(c, it) }
                } finally {
                    Sincro.enCurso.remove(c.id)
                }
                playlist?.let { bib.agregarA(it, bib.normal(uri)) }
                cola.update { l -> l.filterNot { it.cancion.id == c.id } }
                withContext(Dispatchers.Main) { recargar(); cambio() }
            } catch (e: Exception) {
                cola.update { l -> l.map { if (it.cancion.id == c.id) it.copy(error = mensaje(e)) else it } }
            }
            withContext(Dispatchers.Main) { siguienteDescarga() }
        }
    }

    fun olvidarError(id: String) = cola.update { l -> l.filterNot { it.cancion.id == id } }

    // --- playlists ------------------------------------------------------------------------------------

    fun pistasDe(id: Long): List<Pista> {
        val todas = pistas.value.associateBy { it.uri }
        return bib.urisDe(id).mapNotNull { todas[it] }
    }

    fun nuevaPlaylist(nombre: String): Long {
        val id = bib.nuevaPlaylist(nombre.ifBlank { "Mi playlist" })
        recargar()
        cambio()
        return id
    }

    fun renombrar(id: Long, nombre: String) { if (nombre.isNotBlank()) { bib.renombrarPlaylist(id, nombre); recargar(); cambio() } }
    fun borrarPlaylist(id: Long) { bib.borrarPlaylist(id); recargar(); cambio() }
    fun agregarA(id: Long, p: Pista) { bib.agregarA(id, p.uri); recargar(); cambio(); aviso.value = "Agregada." }
    fun quitarDe(id: Long, p: Pista) { bib.quitarDe(id, p.uri); recargar(); cambio() }

    fun borrar(p: Pista) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { bib.borrar(p) }
        aviso.value = if (ok) "Borrada del teléfono." else "Android no dejó borrarla."
        recargar()
        if (ok) cambio()
    }

    // --- cuenta de Google, sincronizar y actualizar (1.1) ---------------------------------------------

    /** Algo cambió aquí: en un rato se sube a la nube. */
    private fun cambio() = Trabajo.cambio(getApplication())

    fun conectarCuenta(token: String) = viewModelScope.launch {
        try {
            Cuenta.conectar(getApplication(), token)
            aviso.value = "Listo: tu música se va a sincronizar con la de la compu."
            Trabajo.ahora(getApplication())
        } catch (e: Exception) {
            aviso.value = mensaje(e)
        }
    }

    fun sincronizarAhora() {
        Trabajo.ahora(getApplication())
        aviso.value = "Sincronizando…"
    }

    fun cerrarSesion() = viewModelScope.launch {
        Cuenta.cerrar(getApplication())
        aviso.value = "Sesión cerrada. Tus canciones se quedan en el teléfono."
    }

    fun revisarActualizacion() = viewModelScope.launch {
        try {
            val v = withContext(Dispatchers.IO) { Actualizador.revisar(getApplication(), instalar = false) }
            if (v == null) aviso.value = "Ya tienes la versión más nueva."
        } catch (e: Exception) {
            aviso.value = mensaje(e)
        }
    }

    fun actualizarAhora() {
        val v = Actualizador.hay.value ?: return
        vapp.alcance.launch {
            try {
                Actualizador.instalar(getApplication(), v)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { aviso.value = mensaje(e) }
            }
        }
    }

    /** «Recomendadas para esta playlist»: la radio de YouTube Music de hasta 3 canciones al azar. */
    fun recomendar(id: Long) {
        recomendando.value = id
        viewModelScope.launch {
            val dentro = pistasDe(id)
            val semillas = dentro.shuffled().sortedByDescending { it.videoId.isNotEmpty() }.take(3)
                .map { YtMusic.Semilla(it.videoId, it.titulo, it.artista) }
            val tienes = pistas.value.map { YtMusic.Semilla(it.videoId, it.titulo, it.artista) }
            try {
                val r = withContext(Dispatchers.IO) { YtMusic.recomendar(semillas, tienes, 20) }
                recomendadas.update { it + (id to r) }
            } catch (e: Exception) {
                aviso.value = mensaje(e)
            } finally {
                recomendando.value = null
            }
        }
    }

    private fun mensaje(e: Throwable): String = when (e) {
        is IOException -> if (e.message.isNullOrBlank() || e is java.net.UnknownHostException) "Sin conexión a internet." else e.message!!
        is org.schabi.newpipe.extractor.exceptions.ReCaptchaException -> "YouTube pidió verificar que no eres un robot; intenta en un rato."
        is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException -> "Esa canción no está disponible."
        else -> e.message ?: "Algo salió mal."
    }

    override fun onCleared() {
        pararAvance()
        control?.release()
        super.onCleared()
    }
}
