package com.tomich.notificationlistener.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PendingNotification(
    val id: String = java.util.UUID.randomUUID().toString(),
    val app: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    // Clave de la notificación en la barra de estado (para descartarla tras envío exitoso)
    val sbnKey: String? = null,
)

object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val EMPTY_BODY: RequestBody = "{}".toRequestBody(JSON_MEDIA)

    /**
     * ID determinista para deduplicar en el servidor. Se calcula a partir de la
     * clave estable de la notificación (sbnKey) + su texto, de modo que reenvíos
     * de la MISMA notificación (reintentos, recaptura al reconectar, re-posts del
     * OEM) produzcan siempre el mismo ID y el servidor sobrescriba el doc en vez
     * de crear un duplicado. Las notificaciones sin sbnKey (prueba/manual) no se
     * deduplican: devuelve null y el servidor les asigna un ID nuevo.
     */
    private fun dedupeId(n: PendingNotification): String? {
        val key = n.sbnKey?.takeIf { it.isNotBlank() } ?: return null
        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest("$key|${n.text}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Envía un lote de notificaciones. Devuelve true si el servidor respondió 2xx. */
    suspend fun sendBatch(
        serverUrl: String,
        deviceToken: String,
        notifications: List<PendingNotification>
    ): Boolean = withContext(Dispatchers.IO) {
        if (notifications.isEmpty()) return@withContext true
        try {
            val array = JSONArray().apply {
                notifications.forEach { n ->
                    put(JSONObject().apply {
                        put("app", n.app)
                        put("text", n.text)
                        put("timestamp", n.timestamp)
                        dedupeId(n)?.let { put("dedupeId", it) }
                    })
                }
            }
            val body = JSONObject()
                .put("notifications", array)
                .toString()
                .toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/notifications")
                .addHeader("Authorization", "Bearer $deviceToken")
                .post(body)
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Obtiene el uid del dueño del dispositivo, para armar el link público
     * /view/{uid}. Devuelve null si falla.
     */
    suspend fun fetchUid(
        serverUrl: String,
        deviceToken: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/devices/me")
                .addHeader("Authorization", "Bearer $deviceToken")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                json.optString("uid").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) { null }
    }

    /** Registra el token FCM en el servidor para habilitar reconexión remota. */
    suspend fun updateFcmToken(
        serverUrl: String,
        deviceToken: String,
        fcmToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("fcmToken", fcmToken)
                .toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/devices/fcm-token")
                .addHeader("Authorization", "Bearer $deviceToken")
                .post(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    /** Avisa al servidor que el servicio está activo. Actualiza lastSeen del dispositivo. */
    suspend fun sendHeartbeat(
        serverUrl: String,
        deviceToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/heartbeat")
                .addHeader("Authorization", "Bearer $deviceToken")
                .post(EMPTY_BODY)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
