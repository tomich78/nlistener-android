package com.tomich.notificationlistener.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColorScheme = lightColorScheme(
    primary          = Blue40,
    onPrimary        = Color.White,
    primaryContainer = Blue90,
    onPrimaryContainer = Blue10,
    secondary        = Slate40,
    onSecondary      = Color.White,
    secondaryContainer = Slate90,
    onSecondaryContainer = Blue10,
    background       = Slate95,
    onBackground     = Blue10,
    surface          = Color.White,
    onSurface        = Blue10,
    surfaceVariant   = Slate90,
    onSurfaceVariant = Slate40,
    error            = Red40,
    onError          = Color.White,
    errorContainer   = Red90,
    onErrorContainer = Red40,
)

@Composable
fun NotificationListenerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography  = Typography,
        content     = content
    )
}
