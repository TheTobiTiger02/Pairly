package dev.smartphonelink.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object LinkTokens {
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp
    val Space8 = 32.dp
    val CardRadius = 12.dp
    val ControlRadius = 8.dp
    val MinTouchTarget = 48.dp
}

private val LinkBlue = Color(0xFF0F6CBD)
private val LinkBlueDark = Color(0xFF60A5E8)

private val LightColors = lightColorScheme(
    primary = LinkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E9FA),
    onPrimaryContainer = Color(0xFF092F4F),
    secondary = Color(0xFF4F6475),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3ECF3),
    onSecondaryContainer = Color(0xFF263946),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF1B1D1F),
    surface = Color(0xFFFCFCFD),
    onSurface = Color(0xFF1B1D1F),
    surfaceVariant = Color(0xFFE9EDF1),
    onSurfaceVariant = Color(0xFF50565C),
    outline = Color(0xFF767C82),
    outlineVariant = Color(0xFFD5D9DE),
    error = Color(0xFFB42318),
    errorContainer = Color(0xFFFFE3E0)
)

private val DarkColors = darkColorScheme(
    primary = LinkBlueDark,
    onPrimary = Color(0xFF00345B),
    primaryContainer = Color(0xFF164D73),
    onPrimaryContainer = Color(0xFFD6EAFA),
    secondary = Color(0xFFB7C8D5),
    onSecondary = Color(0xFF233640),
    secondaryContainer = Color(0xFF344A58),
    onSecondaryContainer = Color(0xFFD5E6F0),
    background = Color(0xFF17191C),
    onBackground = Color(0xFFF1F2F3),
    surface = Color(0xFF202327),
    onSurface = Color(0xFFF1F2F3),
    surfaceVariant = Color(0xFF2B3035),
    onSurfaceVariant = Color(0xFFC2C7CC),
    outline = Color(0xFF92989E),
    outlineVariant = Color(0xFF3A4046),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF6F2522)
)

private val LinkTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

@Composable
fun SmartphoneLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = LinkTypography,
        content = content
    )
}
