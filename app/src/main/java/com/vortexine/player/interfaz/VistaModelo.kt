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
import com.vortexine.player.motor.Letras
import com.vortexine.player.motor.Pista
import com.vortexine.player.motor.Playlist
import com.vortexine.player.motor.YtMusic
import com.vortexine.player.motor.Biblioteca
import com.vortexine.player.motor.Resumen
import com.vortexine.player.reproductor.Despertador
import com.vortexine.player.reproductor.Fundido
import com.vortexine.player.reproductor.Mezcla
import com.vortexine.player.reproductor.Seguir
import com.vortexine.player.reproductor.Radio
import com.vortexine.player.reproductor.Jugador
import com.vortexine.player.reproductor.ServicioReproduccion
import com.vortexine.player.reproductor.Temporizador
import com.vortexine.player.motor.Actualizador
import com.vortexine.player.motor.Cuenta
import com.vortexine.player.motor.Sincro
import com.vortexine.player.segundo.Trabajo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

/** 1.3: una canción de la cola «A continuación»: su lugar en la cola del reproductor y qué es. */
data class EnCola(val indice: Int, val pista: Pista, val radio: Boolean = false)   // 1.4: radio = de la radio sin fin

/** 1.3: cuántas canciones ya tienen su letra guardada (para verlas sin internet). */
data class LetrasGuardadas(val con: Int = 0, val total: Int = 0, val trabajando: Boolean = false)

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

    // 1.2: portadas de las playlists (el mosaico), la letra, «para ti» y desde dónde suena
    val portadas = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    val origen = MutableStateFlow("")
    val letra = MutableStateFlow<Letras.Letra?>(null)
    val buscandoLetra = MutableStateFlow(false)
    val paraTi = MutableStateFlow<List<Cancion>>(emptyList())
    val paraTiDe = MutableStateFlow<List<String>>(emptyList())   // lo que escuchaste y de donde salen
    val cargandoParaTi = MutableStateFlow(false)
    private val sesion = app.getSharedPreferences("sesion", android.content.Context.MODE_PRIVATE)

    // 1.3: ❤️, la cola «A continuación», el temporizador, el crossfade y las letras sin internet
    val meGusta = MutableStateFlow<Set<String>>(emptySet())
    val siguientes = MutableStateFlow<List<EnCola>>(emptyList())
    val temporizador = Temporizador.estado
    val fundido = MutableStateFlow(Fundido.segundos)
    val letrasGuardadas = MutableStateFlow(LetrasGuardadas())
    private var despues = 0   // cuántas «tocar después» van detrás de la que suena

    // 1.4: la radio sin fin y «Tu resumen» (esta semana y la pasada; el aviso sale una vez por semana)
    val radio = MutableStateFlow(Radio.activa(app))
    val resumen = MutableStateFlow(Resumen() to Resumen())
    val avisoResumen = MutableStateFlow(false)
    private val ajustes = app.getSharedPreferences("ajustes", android.content.Context.MODE_PRIVATE)

    // 1.5: tu mezcla del día, seguir lo de la PC, lo nuevo de tus artistas, practicar y el despertador
    val mezcla = MutableStateFlow(Mezcla.leer(app))
    val seguir = MutableStateFlow<JSONObject?>(null)
    val novedades = MutableStateFlow<List<YtMusic.Lanzamiento>>(emptyList())
    val velocidad = MutableStateFlow(1f)
    val tramo = MutableStateFlow(-1L to -1L)   // practicar: de A a B (ms; -1 = sin marcar)
    val despertador = MutableStateFlow(Despertador.leer(app))
    private val novedadesPrefs = app.getSharedPreferences("novedades", android.content.Context.MODE_PRIVATE)

    private var control: MediaController? = null

    // 1.1: la cuenta de Google, la sincronización y las actualizaciones
    val cuenta = Cuenta.datos
    val sincro = Sincro.estado
    val nueva = Actualizador.hay
    val instalando = Actualizador.instalando

    init {
        recargar()
        conectar()
        cargarResumen()
        viewModelScope.launch {   // lo que baja la sincronización aparece en la biblioteca
            Sincro.estado.map { it.vuelta }.distinctUntilChanged().drop(1).collect { recargar() }
        }
        novedades.value = leerNovedades()
        viewModelScope.launch {
            var vueltas = 0
            while (isActive) {
                control?.let { c -> if (c.isPlaying) sonando.update { it.copy(posicion = c.currentPosition) } }
                // 1.5: practicar: al llegar a B vuelve a A
                val (a, b) = tramo.value
                control?.let { c -> if (b > a && a >= 0 && c.isPlaying && c.currentPosition >= b) c.seekTo(a) }
                if (++vueltas % 20 == 0 && control?.isPlaying == true) guardarSesion()   // cada 5 s
                delay(250)   // 4 veces por segundo: la letra cambia de línea a tiempo
            }
        }
    }

    fun recargar() = viewModelScope.launch {
        val (p, l, b) = withContext(Dispatchers.IO) { Triple(bib.pistas(), bib.playlists(), bib.idsBajados()) }
        meGusta.value = withContext(Dispatchers.IO) { bib.meGusta() }
        portadas.value = withContext(Dispatchers.IO) {
            val porUri = p.associateBy { it.uri }
            l.associate { pl -> pl.id to bib.urisDe(pl.id).mapNotNull { porUri[it]?.portada?.takeIf(String::isNotEmpty) }.distinct().take(4) }
        }
        pistas.value = p
        playlists.value = l
        bajadas.value = b
        cargando.value = false
        actualizarSonando()
        restaurar()
        actualizarCola()
        if (!letrasPedidas) { letrasPedidas = true; bajarLetras() }
        if (p.isNotEmpty()) { armarMezcla(); revisarNovedades() }
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
                override fun onEvents(player: Player, events: Player.Events) {
                    actualizarSonando()
                    if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) actualizarCola()
                    if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                            Player.EVENT_REPEAT_MODE_CHANGED)) guardarSesion()
                }
            })
            actualizarSonando()
            restaurar()
        }, MoreExecutors.directExecutor())
    }

    private fun actualizarSonando() {
        val c = control ?: return
        val item = c.currentMediaItem
        val uri = item?.mediaId.orEmpty()
        val p = pistas.value.firstOrNull { it.uri == uri }
        // 1.5: los datos del reproductor de verdad (la sesión pone la línea de la letra en lugar del artista)
        val md = Jugador.actual?.currentMediaItem?.takeIf { it.mediaId == uri }?.mediaMetadata ?: item?.mediaMetadata
        sonando.value = Sonando(
            uri = uri,
            titulo = p?.titulo ?: md?.title?.toString().orEmpty(),
            artista = p?.artista ?: md?.artist?.toString().orEmpty(),
            portada = p?.portada ?: md?.artworkUri?.toString().orEmpty(),
            etiqueta = if (Radio.idDe(uri) != null) "Radio" else p?.etiqueta.orEmpty(), nivel = p?.nivel.orEmpty(),
            tocando = c.isPlaying, posicion = c.currentPosition, duracion = c.duration.coerceAtLeast(0),
            aleatorio = c.shuffleModeEnabled, repetir = c.repeatMode,
            hayAnterior = c.hasPreviousMediaItem() || c.currentPosition > 3000, haySiguiente = c.hasNextMediaItem(),
        )
        if (Despertador.sono) { Despertador.sono = false; origen.value = "Despertador" }
        if (uri != ultimaUri) {
            ultimaUri = uri; despues = (despues - 1).coerceAtLeast(0); cambioDeCancion(sonando.value)
            if (tramo.value.first >= 0) tramo.value = -1L to -1L   // el tramo era de la otra canción
        }
    }

    // --- 1.2: seguir donde te quedaste -----------------------------------------------------------------

    private var ultimaUri = ""
    private var restaurada = false

    /** La cola, la canción, el segundo y de qué playlist venía: para abrir la app y seguir ahí. */
    private fun guardarSesion() {
        val c = control ?: return
        if (c.mediaItemCount == 0 || !restaurada) return
        sesion.edit()
            .putString("cola", JSONArray((0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }).toString())
            .putInt("indice", c.currentMediaItemIndex).putLong("posicion", c.currentPosition)
            .putBoolean("aleatorio", c.shuffleModeEnabled).putInt("repetir", c.repeatMode)
            .putString("origen", origen.value).apply()
    }

    /** Al abrir la app (si el reproductor no seguía vivo): la última canción, en pausa, donde ibas. */
    private fun restaurar() {
        val c = control ?: return
        if (restaurada || cargando.value || pistas.value.isEmpty()) return
        restaurada = true
        if (c.mediaItemCount > 0) return
        val guardadas = runCatching { JSONArray(sesion.getString("cola", "[]")) }.getOrNull()
            ?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: return
        val todas = pistas.value.associateBy { it.uri }
        val lista = guardadas.mapNotNull { todas[it] }
        if (lista.isEmpty()) return
        val actual = guardadas.getOrNull(sesion.getInt("indice", 0))
        val i = lista.indexOfFirst { it.uri == actual }.coerceAtLeast(0)
        c.setMediaItems(lista.map(::item), i, if (lista[i].uri == actual) sesion.getLong("posicion", 0) else 0)
        c.shuffleModeEnabled = sesion.getBoolean("aleatorio", false)
        c.repeatMode = sesion.getInt("repetir", Player.REPEAT_MODE_OFF)
        origen.value = sesion.getString("origen", "").orEmpty()
        c.prepare()
    }

    // --- 1.2: letras e historial -----------------------------------------------------------------------

    private var trabajoLetra: Job? = null

    private fun cambioDeCancion(s: Sonando) {
        trabajoLetra?.cancel()
        letra.value = null
        buscandoLetra.value = false
        viewModelScope.launch { delay(1500); cargarResumen() }   // la que acaba de terminar ya cuenta
        if (s.uri.isEmpty()) return
        val p = pistas.value.firstOrNull { it.uri == s.uri }
        if (p != null && restaurada) anotarEscuchada(p)
        val segundos = ((p?.duracionMs ?: s.duracion) / 1000).toInt()
        buscandoLetra.value = true
        trabajoLetra = viewModelScope.launch {
            letra.value = runCatching {
                withContext(Dispatchers.IO) { Letras.de(File(getApplication<Application>().filesDir, "letras"), s.titulo, s.artista, segundos, p?.videoId.orEmpty()) }
            }.getOrNull()
            buscandoLetra.value = false
        }
    }

    private fun historial(): List<YtMusic.Semilla> = runCatching {
        val a = JSONArray(sesion.getString("historial", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> YtMusic.Semilla(o.optString("id"), o.optString("titulo"), o.optString("artista")) } }
    }.getOrDefault(emptyList())

    /** Las últimas 25 que escuchaste (sin repetir), para recomendarte en «Buscar». */
    private fun anotarEscuchada(p: Pista) {
        val nueva = YtMusic.Semilla(p.videoId, p.titulo, p.artista)
        val h = (listOf(nueva) + historial().filterNot { it.titulo == p.titulo && it.artista == p.artista }).take(25)
        sesion.edit().putString("historial", JSONArray(h.map { JSONObject().put("id", it.id).put("titulo", it.titulo).put("artista", it.artista) }).toString()).apply()
    }

    /** «Para ti»: radios de lo último que escuchaste, sin lo que ya tienes. `otras` cambia las semillas. */
    fun recomendarParaTi(otras: Boolean = false) {
        if (cargandoParaTi.value) return
        val h = historial()
        val semillas = (if (otras) h.take(10).shuffled() else h).take(3).ifEmpty {
            pistas.value.shuffled().take(3).map { YtMusic.Semilla(it.videoId, it.titulo, it.artista) }
        }
        if (semillas.isEmpty()) return
        val de = semillas.map { it.titulo }
        if (!otras && paraTi.value.isNotEmpty() && de == paraTiDe.value) return
        cargandoParaTi.value = true
        viewModelScope.launch {
            val tienes = pistas.value.map { YtMusic.Semilla(it.videoId, it.titulo, it.artista) } +
                if (otras) paraTi.value.map { YtMusic.Semilla(it.id, it.titulo, it.artistas) } else emptyList()
            try {
                paraTi.value = withContext(Dispatchers.IO) { YtMusic.recomendar(semillas, tienes, 25) }
                paraTiDe.value = de
            } catch (e: Exception) {
                if (paraTi.value.isEmpty()) aviso.value = mensaje(e)
            } finally {
                cargandoParaTi.value = false
            }
        }
    }

    private fun item(p: Pista) = Radio.itemPista(p)

    /** Toca `lista` empezando en `desde` (la cola es la lista entera, como en la PC). */
    fun tocar(lista: List<Pista>, desde: Int, aleatorio: Boolean = false, de: String = "Tu música") {
        tocarItems(lista.map(::item), desde.coerceIn(0, (lista.size - 1).coerceAtLeast(0)), aleatorio, de)
    }

    private fun tocarItems(items: List<MediaItem>, desde: Int, aleatorio: Boolean, de: String, ms: Long = 0) {
        pararAvance()
        val c = control ?: return
        if (items.isEmpty()) return
        Radio.forzada = de.startsWith("Radio de ")
        origen.value = de
        restaurada = true
        despues = 0
        c.setMediaItems(items, desde, ms)
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

    // --- 1.3: ❤️ «Tus me gusta» ------------------------------------------------------------------------

    fun alternarMeGusta(uri: String) {
        if (uri.isEmpty()) return
        viewModelScope.launch {
            val gusta = withContext(Dispatchers.IO) { bib.alternarMeGusta(uri) }
            aviso.value = if (gusta) "Agregada a Tus me gusta" else "Quitada de Tus me gusta"
            recargar()
            cambio()
        }
    }

    // --- 1.3: la cola «A continuación» ---------------------------------------------------------------
    // Se usa el ExoPlayer del servicio (está en este mismo proceso): con aleatorio, ExoPlayer metería la nueva
    // en cualquier lugar, y aquí se acomoda justo después de la que suena.

    /** Lo que viene, en el orden en que va a sonar (con aleatorio, el orden revuelto). */
    private fun actualizarCola() {
        val j = Jugador.actual ?: return
        val t = j.currentTimeline
        if (t.isEmpty) { siguientes.value = emptyList(); return }
        val porUri = pistas.value.associateBy { it.uri }
        val lista = mutableListOf<EnCola>()
        var i = t.getNextWindowIndex(j.currentMediaItemIndex, Player.REPEAT_MODE_OFF, j.shuffleModeEnabled)
        while (i != androidx.media3.common.C.INDEX_UNSET && lista.size < 300) {
            val m = j.getMediaItemAt(i)
            val md = m.mediaMetadata
            if (Radio.idDe(m.mediaId) != null) lista += EnCola(i, Pista(m.mediaId, md.title?.toString().orEmpty(),
                md.artist?.toString().orEmpty(), md.albumTitle?.toString().orEmpty(), md.durationMs ?: 0, 0,
                md.artworkUri?.toString().orEmpty(), Radio.idDe(m.mediaId).orEmpty()), radio = true)
            else porUri[m.mediaId]?.let { lista += EnCola(i, it) }
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, j.shuffleModeEnabled)
        }
        siguientes.value = lista
    }

    /** Suena justo después de la actual (y de las que ya pusiste con «tocar después»). */
    fun tocarDespues(p: Pista) = meterEnCola(p, alFinal = false)

    fun agregarACola(p: Pista) = meterEnCola(p, alFinal = true)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun meterEnCola(p: Pista, alFinal: Boolean) {
        val j = Jugador.actual
        if (j == null || j.mediaItemCount == 0) { tocar(listOf(p), 0, de = origen.value.ifEmpty { "Tu música" }); return }
        // en el orden en que suenan: dónde va la nueva
        val orden = mutableListOf<Int>()
        val t = j.currentTimeline
        var i = t.getFirstWindowIndex(j.shuffleModeEnabled)
        while (i != androidx.media3.common.C.INDEX_UNSET) { orden += i; i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, j.shuffleModeEnabled) }
        val aqui = orden.indexOf(j.currentMediaItemIndex)
        val lugar = if (alFinal) orden.size else (aqui + 1 + despues).coerceAtMost(orden.size)
        val nuevo = j.mediaItemCount   // se agrega al final de la lista; el orden de sonar se acomoda aparte
        j.addMediaItem(item(p))
        orden.add(lugar, nuevo)
        if (j.shuffleModeEnabled) j.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(orden.toIntArray(), System.nanoTime()))
        else if (!alFinal) j.moveMediaItem(nuevo, j.currentMediaItemIndex + 1 + despues)
        if (!alFinal) despues++
        aviso.value = if (alFinal) "Agregada a la cola" else "Suena después de esta"
        actualizarCola()
        guardarSesion()
    }

    fun saltarA(indice: Int) { control?.let { despues = 0; it.seekTo(indice, 0); it.play() } }

    fun quitarDeCola(indice: Int) {
        val j = Jugador.actual ?: return
        if (indice == j.currentMediaItemIndex) return
        if (siguientes.value.indexOfFirst { it.indice == indice } in 0 until despues) despues--
        j.removeMediaItem(indice)
        actualizarCola()
        guardarSesion()
    }

    // --- 1.3: temporizador y crossfade -----------------------------------------------------------------

    /** Minutos; -1: al terminar la canción; 0: apagado. */
    fun dormir(minutos: Int) {
        Temporizador.poner(minutos)
        aviso.value = when {
            minutos > 0 -> "La música se pausa en $minutos minutos."
            minutos < 0 -> "La música se pausa al terminar esta canción."
            else -> "Temporizador apagado."
        }
    }

    fun ponerFundido(segundos: Int) { Fundido.poner(getApplication(), segundos); fundido.value = segundos }

    // --- 1.4: la radio sin fin y «Tu resumen» ----------------------------------------------------------

    fun ponerRadio(activa: Boolean) { Radio.poner(getApplication(), activa); radio.value = activa }

    /** La de la radio que suena, a tu biblioteca (luego ya le puedes dar ❤️). */
    fun bajarDeLaRadio(s: Sonando) {
        val id = Radio.idDe(s.uri) ?: return
        bajar(Cancion(id, s.titulo, s.artista, "", (s.duracion / 1000).toInt(), s.portada))
        aviso.value = "Bajando a tu biblioteca"
    }

    fun cargarResumen() = viewModelScope.launch {
        val esta = Biblioteca.semana(0)
        resumen.value = withContext(Dispatchers.IO) {
            bib.resumen(esta.first, esta.second) to Biblioteca.semana(1).let { bib.resumen(it.first, it.second) }
        }
        avisoResumen.value = resumen.value.second.escuchas > 0 && ajustes.getLong("resumen_visto", 0) < esta.first
    }

    fun resumenVisto() {
        ajustes.edit().putLong("resumen_visto", Biblioteca.semana(0).first).apply()
        avisoResumen.value = false
    }

    // --- 1.5: «Iniciar radio» y compartir --------------------------------------------------------------

    /** Suena esa canción y luego, sin fin, parecidas a ella (aunque la radio sin fin esté apagada). */
    fun iniciarRadio(p: Pista) {
        tocar(listOf(p), 0, de = "Radio de ${p.titulo}")
        aviso.value = "Radio de «${p.titulo}»: siguen parecidas"
    }

    /** El enlace de YouTube Music, por WhatsApp o lo que elijas (las tuyas sin id se buscan primero). */
    fun compartir(titulo: String, artista: String, videoId: String) = viewModelScope.launch {
        val id = videoId.ifEmpty {
            runCatching { withContext(Dispatchers.IO) { YtMusic.buscar("$artista $titulo", 1).firstOrNull()?.id } }.getOrNull().orEmpty()
        }
        if (id.isEmpty()) { aviso.value = "No encontré esta canción en YouTube Music."; return@launch }
        val texto = (if (artista.isNotEmpty()) "$titulo · $artista" else titulo) + " https://music.youtube.com/watch?v=$id"
        val enviar = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, texto)
        getApplication<Application>().startActivity(android.content.Intent.createChooser(enviar, "Compartir canción")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun compartir(p: Pista) = compartir(p.titulo, p.artista, p.videoId)

    /** 1.6: la playlist como enlace de YouTube (lo abre cualquiera; Vortex Player la baja con su nombre). */
    fun compartirPlaylist(nombre: String, pistas: List<Pista>) = viewModelScope.launch {
        if (pistas.isEmpty()) return@launch
        aviso.value = "Armando el enlace…"
        val io = Dispatchers.IO.limitedParallelism(8)
        val ids = pistas.map { p ->
            async(io) {
                p.videoId.ifEmpty {   // las tuyas sin id se buscan en YouTube Music
                    runCatching { YtMusic.buscar("${p.artista} ${p.titulo}", 1).firstOrNull()?.id }.getOrNull().orEmpty()
                }
            }
        }.awaitAll()
        val enlaces = YtMusic.enlacesPlaylist(nombre, ids)
        if (enlaces.isEmpty()) { aviso.value = "No encontré estas canciones en YouTube Music."; return@launch }
        val enviar = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "$nombre\n" + enlaces.joinToString("\n"))
        getApplication<Application>().startActivity(android.content.Intent.createChooser(enviar, "Compartir playlist")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // --- 1.5: tu mezcla del día ----------------------------------------------------------------------

    private var armandoMezcla = false

    fun armarMezcla(forzar: Boolean = false) {
        if (armandoMezcla || (!forzar && mezcla.value.fecha == Mezcla.hoy())) return
        val todas = pistas.value
        if (todas.isEmpty()) return
        armandoMezcla = true
        vapp.alcance.launch {
            val d = runCatching { Mezcla.armar(bib, todas) }.getOrNull()
            withContext(Dispatchers.Main) {
                armandoMezcla = false
                if (d != null && d.nuevas.isNotEmpty()) { Mezcla.guardar(getApplication(), d); mezcla.value = d }   // sin internet: la próxima vez
            }
        }
    }

    fun tocarMezcla() = tocarItems(Mezcla.items(mezcla.value, pistas.value), 0, false, "Tu mezcla del día")

    // --- 1.5: seguir en el otro aparato ----------------------------------------------------------------

    private var revisadoOtro = 0L

    /** Al abrir la app o volver a ella: ¿sonaba algo en la PC? */
    fun revisarOtroAparato() {
        val ahora = System.currentTimeMillis()
        if (!Cuenta.datos.value.conectada || ahora - revisadoOtro < 45_000) return
        revisadoOtro = ahora
        viewModelScope.launch {
            val d = runCatching { withContext(Dispatchers.IO) { Seguir.leer(getApplication()) } }.getOrNull()
            if (!Seguir.ofrecer(getApplication(), d)) return@launch
            val enEsta = Jugador.actual?.currentMediaItem?.let { it.mediaMetadata.extras?.getString("id") ?: Radio.idDe(it.mediaId) }
            if (enEsta == d!!.optString("id") && kotlin.math.abs((control?.currentPosition ?: 0) / 1000.0 - d.optDouble("posicion")) < 30) return@launch
            seguir.value = d
        }
    }

    fun seguirAqui() {
        val d = seguir.value ?: return
        seguir.value = null
        Seguir.marcarVisto(getApplication(), d.optLong("cuando"))
        val id = d.optString("id")
        val tuya = pistas.value.firstOrNull { it.videoId == id }
        val it = tuya?.let(::item) ?: Radio.item(Cancion(id, d.optString("titulo"), d.optString("artista"), d.optString("album"),
            d.optDouble("duracion", 0.0).toInt(), d.optString("portada")))
        tocarItems(listOf(it), 0, false, "Desde " + d.optString("nombre", "tu PC"), (d.optDouble("posicion", 0.0) * 1000).toLong())
    }

    fun descartarSeguir() {
        seguir.value?.let { Seguir.marcarVisto(getApplication(), it.optLong("cuando")) }
        seguir.value = null
    }

    // --- 1.5: lo nuevo de tus artistas -----------------------------------------------------------------

    private fun leerNovedades(): List<YtMusic.Lanzamiento> = runCatching {
        val a = JSONArray(novedadesPrefs.getString("lista", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            YtMusic.Lanzamiento(o.getString("id"), o.optString("titulo"), o.optString("artista"), o.optString("tipo"), o.optInt("anio"), o.optString("portada"))
        } }
    }.getOrDefault(emptyList())

    /** Una vez al día: los álbumes y singles de este año de tus 5 artistas más escuchados que no tienes. */
    fun revisarNovedades(forzar: Boolean = false) {
        val hoy = Mezcla.hoy()
        if (!forzar && novedadesPrefs.getString("fecha", "") == hoy) return
        novedadesPrefs.edit().putString("fecha", hoy).apply()
        val todas = pistas.value
        vapp.alcance.launch {
            val artistas = bib.artistasMasEscuchados(System.currentTimeMillis() - 60 * 86_400_000L, 5, todas)
            val claves = todas.map { YtMusic.clave(it.album.ifEmpty { it.titulo }, it.artista) }.toSet() + todas.map { YtMusic.clave(it.titulo, it.artista) }
            val ids = runCatching { JSONObject(novedadesPrefs.getString("ids", "{}")!!) }.getOrDefault(JSONObject())
            val cal = java.util.Calendar.getInstance()
            val desde = cal.get(java.util.Calendar.YEAR) - if (cal.get(java.util.Calendar.MONTH) <= 1) 1 else 0
            val lista = mutableListOf<YtMusic.Lanzamiento>()
            try {
                for (a in artistas) {
                    val canal = ids.optString(a).ifEmpty { YtMusic.artistaId(a) }.ifEmpty { continue }
                    ids.put(a, canal)
                    lista += YtMusic.lanzamientos(canal, a).take(8).filter { it.anio >= desde && YtMusic.clave(it.titulo, a) !in claves }
                }
            } catch (e: Exception) {
                novedadesPrefs.edit().remove("fecha").apply()   // sin internet: la próxima vez que abras la app
                return@launch
            }
            val final = lista.sortedByDescending { it.anio }.take(12)
            val vistos = novedadesPrefs.getStringSet("vistos", emptySet()).orEmpty()
            val nuevas = final.filter { it.id !in vistos }
            novedadesPrefs.edit().putString("ids", ids.toString()).putStringSet("vistos", vistos + final.map { it.id })
                .putString("lista", JSONArray(final.map {
                    JSONObject().put("id", it.id).put("titulo", it.titulo).put("artista", it.artista).put("tipo", it.tipo)
                        .put("anio", it.anio).put("portada", it.portada)
                }).toString()).apply()
            withContext(Dispatchers.Main) {
                novedades.value = final
                if (nuevas.isNotEmpty()) avisarNovedades(nuevas)
            }
        }
    }

    private fun avisarNovedades(nuevas: List<YtMusic.Lanzamiento>) {
        val x = nuevas.first()
        val texto = if (nuevas.size == 1) "Nuevo de ${x.artista}: «${x.titulo}»"
            else "${nuevas.size} lanzamientos nuevos de tus artistas, como «${x.titulo}» de ${x.artista}"
        aviso.value = texto
        val ctx = getApplication<Application>()
        val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel("novedades",
            "Lo nuevo de tus artistas", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val abrir = android.app.PendingIntent.getActivity(ctx, 9, android.content.Intent(ctx, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(9, androidx.core.app.NotificationCompat.Builder(ctx, "novedades").setSmallIcon(com.vortexine.player.R.drawable.ic_estado)
                .setContentTitle("Lo nuevo de tus artistas").setContentText(texto).setContentIntent(abrir).setAutoCancel(true).build())
        }
    }

    /** Suena entero sin bajarlo (como la radio). */
    fun tocarLanzamiento(l: YtMusic.Lanzamiento) = viewModelScope.launch {
        val canciones = runCatching { withContext(Dispatchers.IO) { YtMusic.cancionesDe(l) } }.getOrElse { aviso.value = mensaje(it); return@launch }
        tocarItems(canciones.map(Radio::item), 0, false, l.titulo)
    }

    fun bajarLanzamiento(l: YtMusic.Lanzamiento) = viewModelScope.launch {
        val canciones = runCatching { withContext(Dispatchers.IO) { YtMusic.cancionesDe(l) } }.getOrElse { aviso.value = mensaje(it); return@launch }
        canciones.forEach { bajar(it) }
        aviso.value = "Bajando «${l.titulo}» a tu biblioteca"
    }

    // --- 1.5: practicar una canción ----------------------------------------------------------------------

    /** Más lenta (o rápida) sin que cambie el tono. */
    fun ponerVelocidad(v: Float) {
        control?.playbackParameters = androidx.media3.common.PlaybackParameters(v, 1f)
        velocidad.value = v
    }

    /** La 1.ª vez marca A, la 2.ª B (y se repite de A a B), la 3.ª lo quita. */
    fun marcarTramo() {
        val pos = control?.currentPosition ?: return
        val (a, b) = tramo.value
        tramo.value = when {
            a < 0 -> pos to -1L
            b < 0 && pos > a + 500 -> { control?.seekTo(a); a to pos }
            else -> -1L to -1L
        }
    }

    // --- 1.5: el despertador -----------------------------------------------------------------------------

    fun ponerDespertador(a: Despertador.Ajuste) {
        Despertador.poner(getApplication(), a)
        despertador.value = a
        if (a.activo) aviso.value = "El despertador suena a las %d:%02d".format(a.hora, a.minuto)
    }

    // --- 1.3: las letras de toda tu música, para verlas sin internet -----------------------------------

    private var letrasPedidas = false

    /** Busca (despacito, en segundo plano) la letra de cada canción que todavía no la tiene. */
    fun bajarLetras() {
        if (letrasGuardadas.value.trabajando) return
        val todas = pistas.value
        val dir = File(getApplication<Application>().filesDir, "letras")
        letrasGuardadas.value = letrasGuardadas.value.copy(trabajando = true)
        vapp.alcance.launch {
            var con = 0
            val faltan = todas.filter { p ->
                Letras.guardada(dir, p.titulo, p.artista, (p.duracionMs / 1000).toInt()).also { if (it) con++ }.not()
            }
            letrasGuardadas.value = LetrasGuardadas(con, todas.size, faltan.isNotEmpty())
            for (p in faltan) {
                try {
                    Letras.de(dir, p.titulo, p.artista, (p.duracionMs / 1000).toInt(), p.videoId)
                } catch (e: IOException) {
                    break   // sin internet (o LRCLIB caído): la próxima vez que abras la app
                } catch (e: Exception) {
                    // una canción rara no detiene a las demás
                }
                con++
                letrasGuardadas.value = LetrasGuardadas(con, todas.size, true)
                delay(1500)   // sin prisa: LRCLIB es gratis y de una persona
            }
            letrasGuardadas.value = LetrasGuardadas(con, todas.size, false)
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

    /** Al borrar lo que escribiste en «Buscar» vuelve «Para ti». */
    fun limpiarBusqueda() { resultados.value = emptyList(); enlace.value = null }

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
        e.clase == "compartida" -> {   // 1.6: los ids vienen en el enlace; los datos de cada una, 8 a la vez
            val (nombre, ids) = e.id.split("\n", limit = 2)
            val io = Dispatchers.IO.limitedParallelism(8)
            nombre to coroutineScope {
                ids.split(",").filter { it.isNotBlank() }.distinct()
                    .map { async(io) { runCatching { Descargas.datosDe(it) }.getOrNull() } }.awaitAll().filterNotNull()
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
                withContext(Dispatchers.Main) { letrasPedidas = false; recargar(); cambio() }   // 1.3: y su letra, para verla sin internet
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
            // 1.3: «Otras» no repite las que ya te había enseñado
            val tienes = pistas.value.map { YtMusic.Semilla(it.videoId, it.titulo, it.artista) } +
                recomendadas.value[id].orEmpty().map { YtMusic.Semilla(it.id, it.titulo, it.artistas) }
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
