package com.tomich.notificationlistener.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.ComponentName
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tomich.notificationlistener.R
import com.tomich.notificationlistener.data.ApiClient
import com.tomich.notificationlistener.data.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID     = "nl_keepalive_channel"
        const val NOTIF_ID       = 1001
        const val ACTION_RESTART = "com.tomich.notificationlistener.RESTART"
        // Reinicio cada 4 minutos (antes del timeout de Doze)
        private const val RESTART_INTERVAL_MS = 4 * 60 * 1000L
        // WakeLock más largo que el intervalo para cubrir retrasos de Doze
        private const val WAKELOCK_TIMEOUT_MS  = 8 * 60 * 1000L

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_RESTART
            }
            // Android 8+: usar getForegroundService para poder arrancar como foreground
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val triggerAt = System.currentTimeMillis() + RESTART_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        // WakeLock con timeout — no indefinido, se renueva vía AlarmManager
        wakeLock?.let { if (it.isHeld) it.release() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KeepAliveService::WakeLock"
        ).also { it.acquire(WAKELOCK_TIMEOUT_MS) }

        // Heartbeat: actualiza lastSeen en el servidor cada vez que se reinicia (cada ~4 min)
        scope.launch {
            val prefs = PrefsManager(applicationContext)
            if (prefs.deviceToken.isNotBlank() && prefs.serverUrl.isNotBlank()) {
                ApiClient.sendHeartbeat(prefs.serverUrl, prefs.deviceToken)
            }
        }

        // Si NLService está desconectado, pedirle al sistema que lo reconecte
        if (!NLService.isConnected) {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(applicationContext, NLService::class.java)
            )
        }

        // Programar próximo reinicio vía AlarmManager (sobrevive a Doze)
        schedule(applicationContext)

        // WorkManager como backup: si AlarmManager falla, WorkManager reinicia el servicio
        scheduleKeepAliveWorker()

        return START_STICKY
    }

    /**
     * WorkManager periódico como red de seguridad.
     * Si AlarmManager no dispara (batería agresiva, fabricante), WorkManager
     * arranca el servicio cada 15 minutos como mínimo.
     */
    private fun scheduleKeepAliveWorker() {
        val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "nl_keepalive_worker",
            ExistingPeriodicWorkPolicy.KEEP,  // No reemplazar si ya existe
            request
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Auto-reiniciar si fue matado por el sistema
        schedule(applicationContext)
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Servicio activo",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "NListener está escuchando cobros"
            setShowBadge(false)
            setSound(null, null)   // Sin sonido aunque sea DEFAULT
            enableVibration(false) // Sin vibración
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Escuchando cobros")
            .setContentText("Las notificaciones se envían automáticamente")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            // DEFAULT en vez de LOW — Android mata con menos agresividad servicios
            // con notificaciones de prioridad más alta
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
}
