package com.vortexine.player.segundo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vortexine.player.BuildConfig
import com.vortexine.player.R
import com.vortexine.player.interfaz.MainActivity
import com.vortexine.player.motor.Actualizador
import com.vortexine.player.motor.Cuenta
import com.vortexine.player.motor.Drive
import com.vortexine.player.motor.Sincro
import java.util.concurrent.TimeUnit

/**
 * Lo que Vortex Player hace solo, aunque no lo abras: cada 6 horas sincroniza tu biblioteca con la nube
 * (y baja lo que falte) y revisa si hay una versión nueva. También corre al abrir la app y poco después
 * de que cambies algo.
 */
class Trabajo(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Cuenta.cargar(ctx)
        var token = Cuenta.token(ctx)
        if (token != null) {
            try {
                Sincro.sincronizar(ctx, token) { texto -> runCatching { setForeground(primerPlano(texto)) } }
            } catch (e: Drive.SinPermiso) {
                Cuenta.olvidarToken(ctx, token)
                token = Cuenta.token(ctx)
                if (token != null) runCatching { Sincro.sincronizar(ctx, token) }
            } catch (e: Exception) {
                if (runAttemptCount < 3) return Result.retry()
            }
        }
        if (inputData.getBoolean("actualizar", true)) runCatching { Actualizador.revisar(ctx) }
        return Result.success()
    }

    override suspend fun getForegroundInfo() = primerPlano("Sincronizando tu música…")

    private fun primerPlano(texto: String): ForegroundInfo {
        Avisos.canales(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Avisos.SINCRONIZAR)
            .setSmallIcon(R.drawable.ic_estado)
            .setContentTitle("Vortex Player")
            .setContentText(texto)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Avisos.abrirApp(applicationContext))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Avisos.ID_SINCRONIZAR, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Avisos.ID_SINCRONIZAR, n)
    }

    companion object {
        private val conRed = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Cada 6 horas, aunque la app esté cerrada. */
        fun programar(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("revisar", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Trabajo>(6, TimeUnit.HOURS).setConstraints(conRed).build())
        }

        /** Ya (al abrir la app o con «Sincronizar ahora»). */
        fun ahora(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork("ahora", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<Trabajo>().setConstraints(conRed).build())
        }

        /** Un rato después de que cambiaste algo (si cambias más cosas, se espera a que termines). */
        fun cambio(ctx: Context) {
            if (!Cuenta.datos.value.conectada) return
            WorkManager.getInstance(ctx).enqueueUniqueWork("cambio", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<Trabajo>().setConstraints(conRed).setInitialDelay(20, TimeUnit.SECONDS)
                    .setInputData(androidx.work.workDataOf("actualizar" to false)).build())
        }
    }
}

object Avisos {
    const val SINCRONIZAR = "sincronizar"
    const val ACTUALIZAR = "actualizar"
    const val ID_SINCRONIZAR = 2001
    const val ID_ACTUALIZAR = 2002

    fun canales(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SINCRONIZAR, "Sincronizar", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ACTUALIZAR, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun abrirApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun mostrar(ctx: Context, titulo: String, texto: String, toque: PendingIntent = abrirApp(ctx)) {
        canales(ctx)
        val n = NotificationCompat.Builder(ctx, ACTUALIZAR).setSmallIcon(R.drawable.ic_estado)
            .setContentTitle(titulo).setContentText(texto).setContentIntent(toque).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_ACTUALIZAR, n) }   // sin permiso de avisos no pasa nada
    }
}

/** Lo que contesta Android al instalar la actualización. */
class ResultadoInstalacion : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android 6 a 11 (o el primer permiso): hace falta un toque en «Actualizar»
                @Suppress("DEPRECATION")
                val confirmar = (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (MainActivity.visible) runCatching { ctx.startActivity(confirmar) }
                else Avisos.mostrar(ctx, "Hay una versión nueva de Vortex Player", "Toca para actualizar",
                    PendingIntent.getActivity(ctx, 1, confirmar, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // Android cierra la app vieja y abre paso a la nueva
            else -> Actualizador.instalando.value = false
        }
    }
}

/** Después de actualizarse: avisa (para que se note) y vuelve a programar lo de cada 6 horas. */
class Actualizada : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Avisos.mostrar(ctx, "Vortex Player se actualizó", "Ya tienes la versión ${BuildConfig.VERSION_NAME}.")
        Trabajo.programar(ctx)
    }
}
