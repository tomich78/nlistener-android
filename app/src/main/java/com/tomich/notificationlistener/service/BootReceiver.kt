package com.tomich.notificationlistener.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tomich.notificationlistener.data.PrefsManager

/**
 * Reinicia el KeepAliveService en los siguientes eventos:
 * - Reinicio del dispositivo (BOOT_COMPLETED, LOCKED_BOOT_COMPLETED)
 * - Actualización de la app (MY_PACKAGE_REPLACED)
 * - Alarma periódica del AlarmManager (ACTION_RESTART)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,        // Android 7+ boot directo
            Intent.ACTION_MY_PACKAGE_REPLACED,          // Después de actualizar la app
            KeepAliveService.ACTION_RESTART,            // Alarma periódica
            "android.net.conn.CONNECTIVITY_CHANGE"      // Vuelve la red
        )
        if (intent.action !in validActions) return

        val prefs = PrefsManager(context)
        if (!prefs.isSetupComplete || !prefs.isServiceEnabled) return

        // Android 12+ prohíbe iniciar foreground services desde contextos de background
        // (ej: CONNECTIVITY_CHANGE con la app cerrada). Si falla, AlarmManager/WorkManager
        // lo reintentan en su próximo ciclo — no debe crashear la app.
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveService::class.java)
            )
        } catch (e: Exception) {
            android.util.Log.w("BootReceiver", "No se pudo iniciar KeepAliveService: ${e.message}")
        }
    }
}
