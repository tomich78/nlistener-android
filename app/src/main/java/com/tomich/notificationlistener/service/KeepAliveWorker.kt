package com.tomich.notificationlistener.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tomich.notificationlistener.data.ApiClient
import com.tomich.notificationlistener.data.PrefsManager

/**
 * Worker periódico de WorkManager — actúa como red de seguridad del AlarmManager.
 * Si el fabricante bloquea las alarmas exactas (Samsung, Huawei, etc.) este worker
 * garantiza que el servicio se reinicie cada ~15 minutos como mínimo.
 */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = PrefsManager(applicationContext)
        if (!prefs.isSetupComplete || !prefs.isServiceEnabled) return Result.success()

        // Reiniciar KeepAliveService si está muerto.
        // Android 12+ prohíbe iniciar foreground services desde un Worker en background.
        // Si falla, el AlarmManager (ciclo de 4 min) se encarga del reinicio.
        try {
            val serviceIntent = Intent(applicationContext, KeepAliveService::class.java)
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        } catch (e: Exception) {
            android.util.Log.w("KeepAliveWorker", "No se pudo iniciar KeepAliveService: ${e.message}")
        }

        // Pedir reconexión de NLService si está desconectado
        if (!NLService.isConnected) {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(applicationContext, NLService::class.java)
            )
        }

        // Heartbeat al servidor
        if (prefs.deviceToken.isNotBlank() && prefs.serverUrl.isNotBlank()) {
            ApiClient.sendHeartbeat(prefs.serverUrl, prefs.deviceToken)
        }

        return Result.success()
    }
}
