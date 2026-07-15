package com.tomich.notificationlistener.service

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tomich.notificationlistener.data.ApiClient
import com.tomich.notificationlistener.data.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FcmService : FirebaseMessagingService() {

    /**
     * Llamado cuando FCM entrega un nuevo token (primer inicio o reset).
     * Lo guardamos en el servidor para poder enviar mensajes a este dispositivo.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = PrefsManager(applicationContext)
        prefs.fcmToken = token
        // Enviar al servidor si ya está configurado
        if (prefs.isSetupComplete) {
            CoroutineScope(Dispatchers.IO).launch {
                ApiClient.updateFcmToken(prefs.serverUrl, prefs.deviceToken, token)
            }
        }
    }

    /**
     * Llamado cuando llega un mensaje FCM (incluso con pantalla apagada).
     * Acción "reconnect": reinicia el servicio y manda heartbeat.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val action = message.data["action"] ?: return

        if (action == "reconnect") {
            val prefs = PrefsManager(applicationContext)
            if (!prefs.isSetupComplete || !prefs.isServiceEnabled) return

            // Reiniciar KeepAliveService.
            // Android 12+ prohíbe iniciar foreground services desde un mensaje FCM
            // recibido en background. Si falla, el AlarmManager se encarga del reinicio.
            try {
                val intent = Intent(applicationContext, KeepAliveService::class.java)
                applicationContext.stopService(intent)
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (e: Exception) {
                android.util.Log.w("FcmService", "No se pudo iniciar KeepAliveService: ${e.message}")
            }

            // Heartbeat inmediato
            CoroutineScope(Dispatchers.IO).launch {
                ApiClient.sendHeartbeat(prefs.serverUrl, prefs.deviceToken)
            }
        }
    }
}
