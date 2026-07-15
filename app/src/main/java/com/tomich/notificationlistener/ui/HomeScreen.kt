package com.tomich.notificationlistener.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.tomich.notificationlistener.data.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tomich.notificationlistener.data.SUPPORTED_APPS
import com.tomich.notificationlistener.data.PrefsManager
import com.tomich.notificationlistener.service.KeepAliveService
import com.tomich.notificationlistener.ui.theme.Blue20
import com.tomich.notificationlistener.ui.theme.Blue40
import com.tomich.notificationlistener.ui.theme.Green40
import com.tomich.notificationlistener.ui.theme.Green90

@Composable
fun HomeScreen(prefs: PrefsManager, onReset: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isEnabled by remember { mutableStateOf(prefs.isServiceEnabled) }
    var selectedApps by remember { mutableStateOf(prefs.selectedApps) }
    var hasNotifPermission by remember { mutableStateOf(hasNotificationPermission(context)) }
    var isBatteryOptimized by remember { mutableStateOf(isBatteryOptimized(context)) }
    var lastSyncTs by remember { mutableStateOf(prefs.lastSyncTimestamp) }

    // Dialog AutoStart para Xiaomi — se muestra una vez y bloquea hasta que el usuario lo ve
    var showAutoStartDialog by remember {
        mutableStateOf(isXiaomiDevice() && !prefs.autoStartDialogShown)
    }
    if (showAutoStartDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("⚠️ Acción requerida", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Tu celular es Xiaomi. MIUI/HyperOS apaga apps en segundo plano " +
                    "aunque estén activas.\n\n" +
                    "Para que NListener funcione con la pantalla apagada necesitás:\n\n" +
                    "1. Habilitar AutoStart para NListener\n" +
                    "2. En Batería → Sin restricciones para NListener\n\n" +
                    "Sin esto las notificaciones no llegarán.",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    prefs.autoStartDialogShown = true
                    showAutoStartDialog = false
                    openXiaomiAutoStart(context)
                }) { Text("Ir a AutoStart") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.autoStartDialogShown = true
                    showAutoStartDialog = false
                }) { Text("Lo haré después") }
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotifPermission = hasNotificationPermission(context)
                isBatteryOptimized = isBatteryOptimized(context)
                lastSyncTs = prefs.lastSyncTimestamp
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Header con gradiente azul
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Blue20, Blue40)))
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                Column {
                    Text(
                        "NListener",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        prefs.serverUrl.removePrefix("https://").removePrefix("http://"),
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        // Estado del servicio
        item {
            Spacer(modifier = Modifier.height(20.dp))
            ServiceStatusCard(
                isEnabled = isEnabled,
                hasPermission = hasNotifPermission,
                lastSyncTs = lastSyncTs,
                onToggle = { enabled ->
                    isEnabled = enabled
                    prefs.isServiceEnabled = enabled
                    val intent = Intent(context, KeepAliveService::class.java)
                    if (enabled) {
                        try {
                            ContextCompat.startForegroundService(context, intent)
                        } catch (e: Exception) {
                            android.util.Log.w("HomeScreen", "No se pudo iniciar KeepAliveService: ${e.message}")
                        }
                    } else context.stopService(intent)
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Link público para compartir con empleados
        item {
            PublicLinkCard(context = context, prefs = prefs)
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Aviso Xiaomi AutoStart (MIUI/HyperOS mata servicios sin esto)
        if (isXiaomiDevice()) {
            item {
                XiaomiAutoStartCard(context)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Aviso Samsung (modo de ahorro mata servicios en background)
        if (isSamsungDevice()) {
            item {
                BrandBatteryCard(
                    brand = "Samsung",
                    steps = "Ajustes → Batería → Uso de batería de la app → NListener → Sin restricciones",
                    onClick = { openAppBatterySettings(context) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Aviso Oppo / Realme / OnePlus (ColorOS mata servicios agresivamente)
        if (isOppoDevice()) {
            item {
                BrandBatteryCard(
                    brand = "OPPO / Realme / OnePlus",
                    steps = "Ajustes → Batería → Ahorro de energía de la app → NListener → No restringir",
                    onClick = { openAppBatterySettings(context) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Aviso Huawei (EMUI mata servicios en background)
        if (isHuaweiDevice()) {
            item {
                BrandBatteryCard(
                    brand = "Huawei",
                    steps = "Ajustes → Batería → Inicio de aplicaciones → NListener → Gestión manual → habilitá todo",
                    onClick = { openAppBatterySettings(context) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Avisos
        if (!hasNotifPermission) {
            item {
                AlertCard(
                    emoji = "🔔",
                    title = "Permiso de notificaciones requerido",
                    message = "Sin este permiso la app no puede escuchar cobros.",
                    actionLabel = "Dar permiso"
                ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (isBatteryOptimized) {
            item {
                AlertCard(
                    emoji = "🔋",
                    title = "Optimización de batería activa",
                    message = "Desactivala para que funcione con la pantalla bloqueada.",
                    actionLabel = "Desactivar"
                ) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Cartel enchufado
        item {
            PluggedInTip()
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Apps
        item {
            Text(
                "Apps a escuchar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    SUPPORTED_APPS.forEachIndexed { index, (pkg, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = pkg in selectedApps,
                                onCheckedChange = { checked ->
                                    selectedApps = if (checked) selectedApps + pkg else selectedApps - pkg
                                    prefs.selectedApps = selectedApps
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                        if (index < SUPPORTED_APPS.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Reconectar + Probar + Reconfigurar
        item {
            Spacer(modifier = Modifier.height(20.dp))

            // Botón reconectar
            Button(
                onClick = {
                    val intent = Intent(context, KeepAliveService::class.java)
                    context.stopService(intent)
                    try {
                        ContextCompat.startForegroundService(context, intent)
                    } catch (e: Exception) {
                        android.util.Log.w("HomeScreen", "No se pudo iniciar KeepAliveService: ${e.message}")
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        val p = PrefsManager(context)
                        if (p.deviceToken.isNotBlank() && p.serverUrl.isNotBlank()) {
                            ApiClient.sendHeartbeat(p.serverUrl, p.deviceToken)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("🔄  Reconectar servicio")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Botón de notificación de prueba
            TestNotificationButton(context = context)

            Spacer(modifier = Modifier.height(8.dp))

            // Botón registrar FCM
            FcmRegisterButton(context = context, prefs = prefs)

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cambiar servidor o token", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(
    isEnabled: Boolean,
    hasPermission: Boolean,
    lastSyncTs: Long,
    onToggle: (Boolean) -> Unit
) {
    val bgColor  = if (isEnabled && hasPermission) Green90 else MaterialTheme.colorScheme.surfaceVariant
    val dotColor = if (isEnabled && hasPermission) Green40 else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            !hasPermission -> "Sin permiso"
                            isEnabled      -> "Activo"
                            else           -> "Desactivado"
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = if (isEnabled && hasPermission) Green40
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = when {
                        !hasPermission -> "Otorgá acceso a notificaciones"
                        isEnabled      -> "Escuchando cobros en tiempo real"
                        else           -> "Activá el servicio para empezar"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 18.dp)
                )
                if (isEnabled && hasPermission) {
                    Text(
                        text = if (lastSyncTs == 0L) "Sin envíos aún"
                               else "Último envío: ${formatSyncTime(lastSyncTs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp, start = 18.dp)
                    )
                }
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                enabled = hasPermission
            )
        }
    }
}

@Composable
private fun PublicLinkCard(context: Context, prefs: PrefsManager) {
    var uid by remember { mutableStateOf(prefs.userUid) }
    var loading by remember { mutableStateOf(uid.isBlank()) }

    // Cargar el uid del servidor si todavía no lo tenemos guardado
    LaunchedEffect(Unit) {
        if (uid.isBlank() && prefs.serverUrl.isNotBlank() && prefs.deviceToken.isNotBlank()) {
            loading = true
            val fetched = ApiClient.fetchUid(prefs.serverUrl, prefs.deviceToken)
            if (fetched != null) {
                prefs.userUid = fetched
                uid = fetched
            }
            loading = false
        }
    }

    val publicUrl = if (uid.isNotBlank())
        "${prefs.serverUrl.trimEnd('/')}/view/$uid" else ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔗", fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
                Text(
                    "Link para tus empleados",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "Compartí este link para que vean los cobros en tiempo real sin entrar a tu cuenta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Generando link...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                publicUrl.isBlank() -> {
                    Text(
                        "No se pudo generar el link. Verificá la conexión y reabrí la app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    // Caja con el link
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            publicUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Link NListener", publicUrl))
                                Toast.makeText(context, "Link copiado", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("📋  Copiar")
                        }
                        Button(
                            onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Seguí los cobros en tiempo real: $publicUrl"
                                    )
                                }
                                context.startActivity(
                                    Intent.createChooser(share, "Compartir link")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Compartir")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(
    emoji: String,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(emoji, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )
                FilledTonalButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) { Text(actionLabel, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun PluggedInTip() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚡", fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
            Column {
                Text(
                    "Mantené el dispositivo enchufado",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    "Con la pantalla bloqueada Android puede retrasar notificaciones. Enchufado funciona siempre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 2.dp),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun TestNotificationButton(context: Context) {
    // idle | sending | ok | error
    var state by remember { mutableStateOf("idle") }

    val containerColor = when (state) {
        "ok"    -> Green40
        "error" -> MaterialTheme.colorScheme.error
        else    -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (state) {
        "ok", "error" -> Color.White
        else          -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Button(
        onClick = {
            if (state == "sending") return@Button
            state = "sending"
            CoroutineScope(Dispatchers.IO).launch {
                val p = PrefsManager(context)
                val ok = if (p.deviceToken.isNotBlank() && p.serverUrl.isNotBlank()) {
                    ApiClient.sendBatch(
                        serverUrl   = p.serverUrl,
                        deviceToken = p.deviceToken,
                        notifications = listOf(
                            com.tomich.notificationlistener.data.PendingNotification(
                                app  = "NListener Test",
                                text = "✅ Notificación de prueba — conexión OK"
                            )
                        )
                    )
                } else false
                state = if (ok) "ok" else "error"
                delay(3000)
                state = "idle"
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor   = contentColor
        )
    ) {
        Text(
            text = when (state) {
                "sending" -> "Enviando..."
                "ok"      -> "✅  ¡Llegó al dashboard!"
                "error"   -> "❌  Sin conexión con el servidor"
                else      -> "🧪  Enviar notificación de prueba"
            }
        )
    }
}

@Composable
private fun XiaomiAutoStartCard(context: Context) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = androidx.compose.ui.graphics.Color(0xFFFFF3CD)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text("⚠️", fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Xiaomi detectado — habilitá AutoStart",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color(0xFF856404)
                )
                Text(
                    "MIUI/HyperOS mata la app en segundo plano. Sin AutoStart las notificaciones no se envían con la pantalla apagada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color(0xFF856404),
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    lineHeight = 18.sp
                )
                FilledTonalButton(
                    onClick = { openXiaomiAutoStart(context) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFFFE08A)
                    )
                ) {
                    Text(
                        "Abrir configuración AutoStart",
                        fontSize = 13.sp,
                        color = androidx.compose.ui.graphics.Color(0xFF856404)
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandBatteryCard(brand: String, steps: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = androidx.compose.ui.graphics.Color(0xFFFFF3CD)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text("⚠️", fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$brand detectado — configurá la batería",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color(0xFF856404)
                )
                Text(
                    steps,
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color(0xFF856404),
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    lineHeight = 18.sp
                )
                FilledTonalButton(
                    onClick = onClick,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFFFE08A)
                    )
                ) {
                    Text(
                        "Abrir ajustes de la app",
                        fontSize = 13.sp,
                        color = androidx.compose.ui.graphics.Color(0xFF856404)
                    )
                }
            }
        }
    }
}

@Composable
private fun FcmRegisterButton(context: Context, prefs: PrefsManager) {
    var state by remember { mutableStateOf("idle") } // idle | sending | ok | error

    OutlinedButton(
        onClick = {
            if (state == "sending") return@OutlinedButton
            state = "sending"
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { fcmToken ->
                    prefs.fcmToken = fcmToken
                    CoroutineScope(Dispatchers.IO).launch {
                        val ok = ApiClient.updateFcmToken(prefs.serverUrl, prefs.deviceToken, fcmToken)
                        state = if (ok) "ok" else "error"
                        delay(4000)
                        state = "idle"
                    }
                }
                .addOnFailureListener {
                    state = "error"
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(4000)
                        state = "idle"
                    }
                }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = when (state) {
                "sending" -> "Registrando..."
                "ok"      -> "✅  FCM registrado correctamente"
                "error"   -> "❌  Error al registrar FCM"
                else      -> "📡  Registrar dispositivo para reconexión"
            },
            color = when (state) {
                "ok"    -> Green40
                "error" -> MaterialTheme.colorScheme.error
                else    -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun isXiaomiDevice(): Boolean =
    android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
    android.os.Build.MANUFACTURER.equals("Redmi", ignoreCase = true) ||
    android.os.Build.MANUFACTURER.equals("POCO", ignoreCase = true)

private fun isSamsungDevice(): Boolean =
    android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)

private fun isOppoDevice(): Boolean =
    android.os.Build.MANUFACTURER.let {
        it.equals("OPPO", ignoreCase = true) ||
        it.equals("realme", ignoreCase = true) ||
        it.equals("OnePlus", ignoreCase = true)
    }

private fun isHuaweiDevice(): Boolean =
    android.os.Build.MANUFACTURER.equals("Huawei", ignoreCase = true) ||
    android.os.Build.MANUFACTURER.equals("Honor", ignoreCase = true)

private fun openAppBatterySettings(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
    for (intent in intents) {
        try { context.startActivity(intent); return } catch (_: Exception) {}
    }
}

private fun openXiaomiAutoStart(context: Context) {
    val intents = listOf(
        // HyperOS / MIUI 14+
        Intent().setClassName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HideAppsContainerManagementActivity"
        ),
        // MIUI anterior
        Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        // Fallback: configuración de batería general
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {}
    }
}

private fun hasNotificationPermission(ctx: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

private fun isBatteryOptimized(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun formatSyncTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val mins = diff / 60_000
    val hours = mins / 60
    return when {
        mins < 1    -> "hace un momento"
        mins < 60   -> "hace ${mins} min"
        hours < 24  -> "hace ${hours} h"
        else        -> "hace más de un día"
    }
}
