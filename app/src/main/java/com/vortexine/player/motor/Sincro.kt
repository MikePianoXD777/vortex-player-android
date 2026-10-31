package com.vortexine.player.motor

import android.content.Context
import com.vortexine.player.VortexApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Deja este teléfono igual que tu biblioteca en la nube (y la nube con lo nuevo de aquí): sube lo que
 * cambió, quita lo que borraste en otro lado, arma las playlists y baja las canciones que faltan.
 */
object Sincro {
    data class Estado(val trabajando: Boolean = false, val texto: String = "", val hechas: Int = 0, val total: Int = 0,
                      val ultima: Long = 0, val error: String = "", val vuelta: Int = 0)

    val estado = MutableStateFlow(Estado())

    /** Ids que se están bajando ahora (aquí o desde Buscar), para no bajar dos veces la misma. */
    val enCurso: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val fallidas = ConcurrentHashMap<String, Long>()
    private const val REINTENTAR_EN = 6 * 3600_000L
    private val candado = Mutex()

    fun cargar(ctx: Context) {
        estado.update { it.copy(ultima = ctx.getSharedPreferences("cuenta", Context.MODE_PRIVATE).getLong("ultima", 0)) }
    }

    /** `avisar` recibe lo que va haciendo (para la notificación). */
    suspend fun sincronizar(ctx: Context, token: String, avisar: suspend (String) -> Unit = {}) = candado.withLock {
        withContext(Dispatchers.IO) {
            try {
                trabajar(ctx, token, avisar)
            } catch (e: Exception) {
                estado.update { it.copy(trabajando = false, texto = "", error = e.message ?: "No se pudo sincronizar") }
                throw e
            }
        }
    }

    private suspend fun trabajar(ctx: Context, token: String, avisar: suspend (String) -> Unit) {
        val bib = (ctx.applicationContext as VortexApp).biblioteca
        val drive = Drive(token)
        fun poner(texto: String, hechas: Int = 0, total: Int = 0) =
            estado.update { it.copy(trabajando = true, texto = texto, hechas = hechas, total = total, error = "") }

        poner("Revisando tu biblioteca en la nube…")
        val archivo = drive.buscar()
        val remoto = archivo?.let { Nube.leer(drive.leer(it)) } ?: Nube.vacio()
        var pistas = bib.pistas()
        val nuevo = Nube.mezclar(remoto, Nube.desde(bib.estadoNube(pistas)))
        if (archivo == null) drive.crear(nuevo.toString())
        else if (!Nube.iguales(nuevo, remoto)) drive.escribir(archivo, nuevo.toString())

        val plan = Nube.plan(nuevo, bib.estadoNube(pistas))
        for (id in plan.borrar) pistas.firstOrNull { it.videoId == id }?.let { bib.borrar(it, anotar = false) }
        for (u in plan.quitarPlaylists) bib.quitarPlaylistNube(u)
        if (plan.borrar.isNotEmpty()) pistas = bib.pistas()
        armarPlaylists(bib, nuevo, pistas)

        val ahora = System.currentTimeMillis()
        val faltan = plan.bajar.filter { (fallidas[it.id] ?: 0) < ahora - REINTENTAR_EN }
        for ((i, c) in faltan.withIndex()) {
            poner("Bajando ${i + 1} de ${faltan.size}: ${c.titulo}", i, faltan.size)
            avisar("Bajando ${i + 1} de ${faltan.size}: ${c.titulo}")
            if (!enCurso.add(c.id)) continue
            try {
                if (bib.uriDeBajada(c.id) == null) {
                    var cancion = Cancion(c.id, c.titulo, c.artista, c.album, c.duracion, "https://i.ytimg.com/vi/${c.id}/hqdefault.jpg")
                    if (cancion.titulo.isBlank()) cancion = Descargas.datosDe(c.id)
                    bib.recordarBajada(cancion, Descargas.bajar(ctx, cancion))
                }
            } catch (e: Exception) {
                fallidas[c.id] = System.currentTimeMillis()
            } finally {
                enCurso.remove(c.id)
            }
            estado.update { it.copy(vuelta = it.vuelta + 1) }   // la pantalla vuelve a leer la biblioteca
            if ((i + 1) % 10 == 0) armarPlaylists(bib, nuevo, bib.pistas())
        }
        if (faltan.isNotEmpty()) armarPlaylists(bib, nuevo, bib.pistas())

        val fin = System.currentTimeMillis()
        ctx.getSharedPreferences("cuenta", Context.MODE_PRIVATE).edit().putLong("ultima", fin).apply()
        val fallaron = faltan.count { fallidas.containsKey(it.id) }
        estado.update {
            Estado(ultima = fin, vuelta = it.vuelta + 1,
                error = if (fallaron > 0) "No se pudieron bajar $fallaron; se reintentan más tarde." else "")
        }
    }

    private fun armarPlaylists(bib: Biblioteca, doc: org.json.JSONObject, pistas: List<Pista>) {
        val conId = pistas.filter { it.videoId.isNotEmpty() }
        val uriDeId = conId.associate { it.videoId to it.uri }
        val idDeUri = conId.associate { it.uri to it.videoId }
        for (p in Nube.plan(doc, bib.estadoNube(pistas)).playlists) bib.ponerPlaylist(p, uriDeId, idDeUri)
    }
}
