package dev.pockethost.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Brand {
    val Indigo = Color(0xFF7C9CFF)
    val Teal = Color(0xFF4FD1C5)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)
    val Grey = Color(0xFF6B7785)
}

private val DarkColors = darkColorScheme(
    primary = Brand.Indigo,
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF1F2A4D),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Brand.Teal,
    onSecondary = Color(0xFF00201D),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1A212B),
    onSurfaceVariant = Color(0xFF9AA7B4),
    surfaceContainer = Color(0xFF131921),
    surfaceContainerHigh = Color(0xFF19202A),
    surfaceContainerHighest = Color(0xFF202834),
    outline = Color(0xFF2A3441),
    outlineVariant = Color(0xFF222B36),
    error = Brand.Red,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF0B1A5C),
    secondary = Color(0xFF00897B),
    background = Color(0xFFF7F8FB),
    surface = Color(0xFFF7F8FB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F2F7),
    surfaceContainerHighest = Color(0xFFE8EBF2),
    onSurfaceVariant = Color(0xFF5B6675),
    outline = Color(0xFFD5DAE3),
    outlineVariant = Color(0xFFE3E7EE),
)

private val AppType = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun PocketHostTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppType,
        content = content,
    )
}
