package com.tomich.notificationlistener

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.tomich.notificationlistener.data.ApiClient
import com.tomich.notificationlistener.data.PrefsManager
import com.tomich.notificationlistener.service.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.tomich.notificationlistener.ui.HomeScreen
import com.tomich.notificationlistener.ui.SetupScreen
import com.tomich.notificationlistener.ui.SplashScreen
import com.tomich.notificationlistener.ui.theme.NotificationListenerTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()

        val prefs = PrefsManager(this)

        // Si el servicio estaba activo antes de que MIUI lo matara, lo reiniciamos
        if (prefs.isSetupComplete && prefs.isServiceEnabled) {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, KeepAliveService::class.java)
                )
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "No se pudo iniciar KeepAliveService: ${e.message}")
            }
        }

        // Registrar token FCM al abrir la app — siempre, no solo cuando cambia
        if (prefs.isSetupComplete) {
            registerFcmToken(prefs)
        }

        setContent {
            NotificationListenerTheme {
                var showSplash by remember { mutableStateOf(true) }
                var setupDone  by remember { mutableStateOf(prefs.isSetupComplete) }

                when {
                    showSplash -> SplashScreen(onFinished = { showSplash = false })
                    setupDone  -> HomeScreen(
                        prefs = prefs,
                        onReset = {
                            prefs.deviceToken = ""
                            prefs.serverUrl = ""
                            prefs.isServiceEnabled = false
                            stopService(Intent(this, KeepAliveService::class.java))
                            setupDone = false
                        }
                    )
                    else -> SetupScreen(
                        prefs = prefs,
                        onSetupComplete = { setupDone = true }
                    )
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun registerFcmToken(prefs: PrefsManager) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { fcmToken ->
                prefs.fcmToken = fcmToken
                CoroutineScope(Dispatchers.IO).launch {
                    ApiClient.updateFcmToken(prefs.serverUrl, prefs.deviceToken, fcmToken)
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("FCM", "Error obteniendo token FCM: ${e.message}")
            }
    }
}
