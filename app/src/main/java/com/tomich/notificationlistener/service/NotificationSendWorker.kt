package com.tomich.notificationlistener.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tomich.notificationlistener.data.ApiClient
import com.tomich.notificationlistener.data.PrefsManager

/**
 * WorkManager worker que envía las notificaciones pendientes guardadas en SharedPrefs.
 * Se ejecuta cuando hay red disponible, incluso si el proceso fue matado por el SO.
 * WorkManager garantiza reintentos con backoff exponencial.
 */
class NotificationSendWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = PrefsManager(applicationContext)
        val token = prefs.deviceToken
        val url   = prefs.serverUrl

        if (token.isBlank() || url.isBlank()) return Result.success()

        val pending = prefs.loadPersistentQueue()
        if (pending.isEmpty()) return Result.success()

        val ok = ApiClient.sendBatch(url, token, pending)
        return if (ok) {
            prefs.removeFromPersistentQueue(pending.map { it.id }.toSet())
            prefs.lastSyncTimestamp = System.currentTimeMillis()
            Result.success()
        } else {
            Result.retry()
        }
    }
}
