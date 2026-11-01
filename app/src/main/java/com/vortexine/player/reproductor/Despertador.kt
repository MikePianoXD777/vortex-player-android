package com.vortexine.player.reproductor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import com.vortexine.player.VortexApp
import com.vortexine.player.interfaz.MainActivity
import java.util.Calendar

/**
 * 1.5: el despertador con tu música. Es una alarma de verdad de Android (sale el reloj en la barra y suena
 * aunque el teléfono esté dormido): a esa hora suena tu mezcla del día (o una playlist) y el volumen sube
 * poco a poco durante un minuto.
 */
object Despertador {
    const val DESPERTAR = "com.vortexine.player.DESPERTAR"

    data class Ajuste(val activo: Boolean = false, val hora: Int = 7, val minuto: Int = 0, val playlist: Long = -1)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun leer(ctx: Context) = prefs(ctx).let {
        Ajuste(it.getBoolean("despertador", false), it.getInt("despertador_hora", 7), it.getInt("despertador_minuto", 0),
            it.getLong("despertador_playlist", -1))
    }

    fun poner(ctx: Context, a: Ajuste) {
        prefs(ctx).edit().putBoolean("despertador", a.activo).putInt("despertador_hora", a.hora)
            .putInt("despertador_minuto", a.minuto).putLong("despertador_playlist", a.playlist).apply()
        programar(ctx)
    }

    /** La próxima vez que toca sonar (ms), o null si está apagado. */
    fun proxima(a: Ajuste, ahora: Long = System.currentTimeMillis()): Long? {
        if (!a.activo) return null
        val c = Calendar.getInstance().apply {
            timeInMillis = ahora
            set(Calendar.HOUR_OF_DAY, a.hora); set(Calendar.MINUTE, a.minuto); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= ahora) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pendiente(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, DespertadorReceptor::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Pone (o quita) la alarma en Android. Se llama al cambiarla, al sonar (para mañana) y al prender el teléfono. */
    fun programar(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cuando = proxima(leer(ctx))
        if (cuando == null) { am.cancel(pendiente(ctx)); return }
        val abrir = PendingIntent.getActivity(ctx, 8, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(cuando, abrir), pendiente(ctx)) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pendiente(ctx)) }   // sin permiso de alarmas exactas
    }

    /** En el servicio: arma la cola, toca y sube el volumen de 5 % a 100 % en un minuto. */
    fun sonar(ctx: Context, j: ExoPlayer) {
        val app = ctx.applicationContext as VortexApp
        val a = leer(ctx)
        val pistas = app.biblioteca.pistas()
        val porUri = pistas.associateBy { it.uri }
        var items = if (a.playlist >= 0) app.biblioteca.urisDe(a.playlist).mapNotNull { porUri[it] }.map(Radio::itemPista)
            else Mezcla.items(Mezcla.leer(ctx), pistas)
        if (items.isEmpty()) items = pistas.shuffled().map(Radio::itemPista)
        sono = true   // la pantalla dice «Tocando desde Despertador»
        j.setMediaItems(items, 0, 0)
        j.volume = 0.05f
        j.prepare()
        j.play()
        val h = Handler(Looper.getMainLooper())
        for (k in 1..30) h.postDelayed({ j.volume = 0.05f + 0.95f * k / 30 }, k * 2000L)
        programar(ctx)   // mañana otra vez
    }

    /** Acaba de sonar: la pantalla lo toma para «Tocando desde». */
    @Volatile var sono = false

    fun hayMusica(ctx: Context) = runCatching { (ctx.applicationContext as VortexApp).biblioteca.pistas().isNotEmpty() }.getOrDefault(false)
}

/** La alarma sonó (o se prendió el teléfono): despierta al servicio para que toque. */
class DespertadorReceptor : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            Despertador.programar(ctx)
            return
        }
        if (!Despertador.hayMusica(ctx)) { Despertador.programar(ctx); return }
        ContextCompat.startForegroundService(ctx, Intent(ctx, ServicioReproduccion::class.java).setAction(Despertador.DESPERTAR))
    }
}
