package com.tomich.notificationlistener.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("nl_prefs", Context.MODE_PRIVATE)

    var deviceToken: String
        get() = prefs.getString("device_token", "") ?: ""
        set(value) = prefs.edit { putString("device_token", value) }

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) = prefs.edit { putString("server_url", value) }

    val isSetupComplete: Boolean
        get() = deviceToken.isNotBlank() && serverUrl.isNotBlank()

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit { putBoolean("service_enabled", value) }

    var lastSyncTimestamp: Long
        get() = prefs.getLong("last_sync_ts", 0L)
        set(value) = prefs.edit { putLong("last_sync_ts", value) }

    var fcmToken: String
        get() = prefs.getString("fcm_token", "") ?: ""
        set(value) = prefs.edit { putString("fcm_token", value) }

    // uid del dueño de la cuenta — se usa para armar el link público /view/{uid}
    var userUid: String
        get() = prefs.getString("user_uid", "") ?: ""
        set(value) = prefs.edit { putString("user_uid", value) }

    var autoStartDialogShown: Boolean
        get() = prefs.getBoolean("autostart_dialog_shown", false)
        set(value) = prefs.edit { putBoolean("autostart_dialog_shown", value) }

    var selectedApps: Set<String>
        get() = prefs.getStringSet("selected_apps", setOf("com.mercadopago.wallet"))
            ?: setOf("com.mercadopago.wallet")
        set(value) = prefs.edit { putStringSet("selected_apps", value) }

    // ── Queue persistente ─────────────────────────────────────────────────────
    // Cada notificación tiene un ID único. Así podemos eliminar solo las enviadas
    // sin afectar las que todavía no se mandaron.

    fun addToPersistentQueue(notifications: List<PendingNotification>) {
        val existing = loadPersistentQueue().toMutableList()
        // Evitar duplicar por ID
        val existingIds = existing.map { it.id }.toSet()
        existing.addAll(notifications.filter { it.id !in existingIds })
        savePersistentQueue(existing)
    }

    fun loadPersistentQueue(): List<PendingNotification> {
        val json = prefs.getString("pending_queue", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PendingNotification(
                    id        = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    app       = obj.getString("app"),
                    text      = obj.getString("text"),
                    timestamp = obj.getLong("timestamp"),
                    sbnKey    = obj.optString("sbnKey").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Elimina de la cola solo las notificaciones con los IDs indicados. */
    fun removeFromPersistentQueue(ids: Set<String>) {
        val remaining = loadPersistentQueue().filter { it.id !in ids }
        savePersistentQueue(remaining)
    }

    fun clearPersistentQueue() {
        prefs.edit { putString("pending_queue", "[]") }
    }

    private fun savePersistentQueue(notifications: List<PendingNotification>) {
        val arr = JSONArray()
        notifications.forEach { n ->
            arr.put(JSONObject().apply {
                put("id",        n.id)
                put("app",       n.app)
                put("text",      n.text)
                put("timestamp", n.timestamp)
                if (n.sbnKey != null) put("sbnKey", n.sbnKey)
            })
        }
        prefs.edit { putString("pending_queue", arr.toString()) }
    }
}
