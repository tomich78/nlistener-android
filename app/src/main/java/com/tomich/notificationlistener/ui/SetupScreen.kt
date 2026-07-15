package com.tomich.notificationlistener.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tomich.notificationlistener.data.PrefsManager
import org.json.JSONObject

@Composable
fun SetupScreen(prefs: PrefsManager, onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    var showScanner by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) showScanner = true
        else error = "Se necesita permiso de cámara para escanear el QR."
    }

    if (showScanner) {
        QrSetupScanner(
            onQrScanned = { raw ->
                try {
                    val json  = JSONObject(raw)
                    val url   = json.getString("url")
                    val token = json.getString("token")
                    prefs.serverUrl   = url
                    prefs.deviceToken = token
                    onSetupComplete()
                } catch (_: Exception) {
                    error = "QR inválido. Generalo desde el dashboard web."
                    showScanner = false
                }
            },
            onBack = { showScanner = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("🔗", fontSize = 32.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Conectar dispositivo",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Escaneá el QR desde tu cuenta en el dashboard web para conectar este celular.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(36.dp))

        Button(
            onClick = {
                if (hasCameraPermission) showScanner = true
                else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("📷  Escanear código QR", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Instrucciones
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "¿Cómo obtener el QR?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                listOf(
                    "1. Abrí tu dashboard en el navegador",
                    "2. Ir a Dispositivos → Agregar dispositivo",
                    "3. Poné un nombre y tocá Agregar",
                    "4. Escaneá el QR que aparece"
                ).forEach { step ->
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "¿No tenés cuenta? ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://nlistener.com.ar")
                    )
                    context.startActivity(intent)
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "Registrate en nlistener.com.ar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                )
            }
        }

        if (error.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = { showManual = !showManual }) {
            Text(
                if (showManual) "Ocultar ingreso manual" else "Ingresar datos manualmente",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }

        if (showManual) {
            ManualSetupFields(prefs = prefs, onSetupComplete = onSetupComplete)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ManualSetupFields(prefs: PrefsManager, onSetupComplete: () -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    var deviceToken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = serverUrl,
        onValueChange = { serverUrl = it },
        label = { Text("URL del servidor") },
        placeholder = { Text("https://tu-app.vercel.app") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = deviceToken,
        onValueChange = { deviceToken = it },
        label = { Text("Token del dispositivo") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
    if (error.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(modifier = Modifier.height(12.dp))
    FilledTonalButton(
        onClick = {
            when {
                serverUrl.isBlank()  -> error = "Ingresá la URL del servidor"
                deviceToken.isBlank() -> error = "Ingresá el token"
                !serverUrl.startsWith("http") -> error = "La URL debe empezar con https://"
                else -> {
                    prefs.serverUrl   = serverUrl.trim()
                    prefs.deviceToken = deviceToken.trim()
                    onSetupComplete()
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Guardar y continuar")
    }
}

@Composable
private fun QrSetupScanner(onQrScanned: (String) -> Unit, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        QrScannerView(
            modifier = Modifier.fillMaxSize(),
            onQrScanned = onQrScanned
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                TextButton(onClick = onBack) {
                    Text("← Volver", color = Color.White)
                }
                Text(
                    "Escanear QR",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .border(3.dp, Color.White, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Apuntá al QR de tu dashboard web",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
