package com.vortexine.player.reproductor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.media3.common.Player
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.vortexine.player.R
import com.vortexine.player.interfaz.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1.3: el widget de la pantalla de inicio (4x1): la portada, la canción y anterior / tocar / siguiente.
 * Lo pinta el servicio cada vez que cambia la canción o se pausa. Con el reproductor cerrado, tocarlo abre la app.
 */
class WidgetReproductor : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = Widget.pintar(ctx, Jugador.actual)

    override fun onReceive(ctx: Context, intent: Intent) {
        val j = Jugador.actual
        when (intent.action) {
            ANTERIOR -> j?.seekToPrevious()
            TOCAR -> j?.let { if (it.isPlaying) it.pause() else it.play() }
            SIGUIENTE -> j?.seekToNext()
            else -> { super.onReceive(ctx, intent); return }
        }
        if (j == null) ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val ANTERIOR = "com.vortexine.player.widget.ANTERIOR"
        const val TOCAR = "com.vortexine.player.widget.TOCAR"
        const val SIGUIENTE = "com.vortexine.player.widget.SIGUIENTE"
    }
}

object Widget {
    private val alcance = CoroutineScope(Dispatchers.Main)
    private var trabajo: Job? = null
    private var ultimaPortada = ""
    private var bitmap: Bitmap? = null

    private fun ids(ctx: Context) = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WidgetReproductor::class.java))

    /** Pinta todos los widgets con lo que suena (o «Vortex Player» si no hay reproductor). */
    fun pintar(ctx: Context, j: Player?) {
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        val md = j?.currentMediaItem?.mediaMetadata
        val portada = md?.artworkUri?.toString().orEmpty()
        val v = RemoteViews(ctx.packageName, R.layout.widget_reproductor)
        v.setTextViewText(R.id.w_titulo, md?.title ?: "Vortex Player")
        v.setTextViewText(R.id.w_artista, md?.artist ?: "Toca para abrir")
        v.setImageViewResource(R.id.w_tocar, if (j?.isPlaying == true) R.drawable.ic_w_pausa else R.drawable.ic_w_tocar)
        if (portada == ultimaPortada && bitmap != null) v.setImageViewBitmap(R.id.w_portada, bitmap)
        else v.setImageViewResource(R.id.w_portada, R.drawable.ic_estado)
        val abrir = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.w_raiz, abrir)
        v.setOnClickPendingIntent(R.id.w_portada, abrir)
        fun accion(a: String, n: Int) = if (j == null) abrir else PendingIntent.getBroadcast(ctx, n,
            Intent(ctx, WidgetReproductor::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.w_anterior, accion(WidgetReproductor.ANTERIOR, 1))
        v.setOnClickPendingIntent(R.id.w_tocar, accion(WidgetReproductor.TOCAR, 2))
        v.setOnClickPendingIntent(R.id.w_siguiente, accion(WidgetReproductor.SIGUIENTE, 3))
        AppWidgetManager.getInstance(ctx).updateAppWidget(ids, v)
        if (portada.isNotEmpty() && portada != ultimaPortada) {
            trabajo?.cancel()
            trabajo = alcance.launch {
                val b = withContext(Dispatchers.IO) {
                    runCatching {
                        val r = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(portada).size(192).allowHardware(false).build())
                        (r as? SuccessResult)?.image?.toBitmap()
                    }.getOrNull()
                } ?: return@launch
                ultimaPortada = portada
                bitmap = b
                pintar(ctx, Jugador.actual)
            }
        }
    }
}
