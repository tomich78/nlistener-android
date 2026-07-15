package com.tomich.notificationlistener.service

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import com.google.firebase.messaging.FirebaseMessaging
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.*
import com.tomich.notificationlistener.data.ApiClient
import com.tomich.notificationlistener.data.PendingNotification
import com.tomich.notificationlistener.data.PrefsManager
import com.tomich.notificationlistener.data.packageToName
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class NLService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PrefsManager
    private lateinit var powerManager: PowerManager

    companion object {
        var isConnected = false
            private set
        const val WORK_TAG = "nl_send_work"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    override fun onListenerConnected() {
        isConnected = true
        scope.launch {
            val token = prefs.deviceToken
            val url   = prefs.serverUrl
            if (token.isNotBlank() && url.isNotBlank()) {
                ApiClient.sendHeartbeat(url, token)
                // Enviar lo que quedó en cola persistente (fallos previos)
                flushPersistentQueue()
                // Capturar notificaciones visibles que no fueron enviadas
                // (las enviadas se descartan del panel, así que estas son solo las pendientes)
                captureAndSendActiveNotifications()
                // Registrar/actualizar token FCM
                FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
                    if (fcmToken != prefs.fcmToken) {
                        prefs.fcmToken = fcmToken
                        scope.launch { ApiClient.updateFcmToken(url, token, fcmToken) }
                    }
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        isConnected = false
        requestRebind(ComponentName(this, NLService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!prefs.isServiceEnabled) return
        if (sbn.packageName !in prefs.selectedApps) return

        val content = extractContent(sbn) ?: return

        val notif = PendingNotification(
            app    = packageToName(sbn.packageName),
            text   = content,
            sbnKey = sbn.key,
        )

        // Guardar en disco inmediatamente — sobrevive a process death
        prefs.addToPersistentQueue(listOf(notif))

        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NLService::NotifWakeLock"
        )
        wakeLock.acquire(60_000L)

        scope.launch {
            try {
                val ok = ApiClient.sendBatch(prefs.serverUrl, prefs.deviceToken, listOf(notif))
                if (ok) {
                    // Eliminar solo ESTE ítem de la cola, no toda la cola
                    prefs.removeFromPersistentQueue(setOf(notif.id))
                    prefs.lastSyncTimestamp = System.currentTimeMillis()
                    // Descartar del panel: indica que fue procesada
                    // Al reconectar, getActiveNotifications() no la verá
                    tryCancel(sbn.key)
                } else {
                    // Quedó en la cola persistente — WorkManager la reintenta
                    scheduleRetryWork()
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    /**
     * Al reconectar, envía las notificaciones que siguen visibles en el panel.
     * Estas son exactamente las que fallaron antes (las exitosas fueron descartadas).
     * Evita duplicados: si una clave ya está en la cola persistente, la saltea
     * porque ya será enviada por flushPersistentQueue.
     */
    private suspend fun captureAndSendActiveNotifications() {
        try {
            val active = getActiveNotifications() ?: return
            val queuedKeys = prefs.loadPersistentQueue().mapNotNull { it.sbnKey }.toSet()

            val toSend = active.mapNotNull { sbn ->
                if (sbn.packageName !in prefs.selectedApps) return@mapNotNull null
                // Si ya está en la cola persistente, flushPersistentQueue la manda
                if (sbn.key in queuedKeys) return@mapNotNull null
                val content = extractContent(sbn) ?: return@mapNotNull null
                PendingNotification(
                    app       = packageToName(sbn.packageName),
                    text      = content,
                    timestamp = sbn.postTime,
                    sbnKey    = sbn.key,
                )
            }

            if (toSend.isEmpty()) return

            val ok = ApiClient.sendBatch(prefs.serverUrl, prefs.deviceToken, toSend)
            if (ok) {
                prefs.lastSyncTimestamp = System.currentTimeMillis()
                toSend.forEach { n -> n.sbnKey?.let { tryCancel(it) } }
            } else {
                // No pudieron enviarse tampoco ahora — guardar en cola para WorkManager
                prefs.addToPersistentQueue(toSend)
                scheduleRetryWork()
            }
        } catch (_: Exception) { }
    }

    /**
     * Envía la cola persistente. Elimina solo los ítems que se enviaron con éxito,
     * dejando intactos los que fallaron.
     */
    private suspend fun flushPersistentQueue() {
        val pending = prefs.loadPersistentQueue()
        if (pending.isEmpty()) return
        val token = prefs.deviceToken
        val url   = prefs.serverUrl
        if (token.isBlank() || url.isBlank()) return

        val ok = ApiClient.sendBatch(url, token, pending)
        if (ok) {
            prefs.removeFromPersistentQueue(pending.map { it.id }.toSet())
            prefs.lastSyncTimestamp = System.currentTimeMillis()
            // Descartar del panel las que estaban en cola y ya se enviaron
            pending.forEach { n -> n.sbnKey?.let { tryCancel(it) } }
        }
        // Si falla, los ítems quedan en disco — WorkManager los reintenta
    }

    private fun tryCancel(key: String) {
        try { cancelNotification(key) } catch (_: Exception) { }
    }

    private fun extractContent(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras
        val title  = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text   = extras.getCharSequence("android.text")?.toString().orEmpty()
        val content = buildString {
            if (title.isNotBlank()) append(title)
            if (title.isNotBlank() && text.isNotBlank()) append(" - ")
            if (text.isNotBlank()) append(text)
        }
        return content.takeIf { it.isNotBlank() }
    }

    private fun scheduleRetryWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<NotificationSendWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(WORK_TAG, ExistingWorkPolicy.REPLACE, request)
    }

    override fun onDestroy() {
        // Si hay pendientes en cola, WorkManager los reintenta cuando haya red
        if (prefs.loadPersistentQueue().isNotEmpty()) {
            scheduleRetryWork()
        }
        scope.cancel()
        isConnected = false
        super.onDestroy()
    }
}
