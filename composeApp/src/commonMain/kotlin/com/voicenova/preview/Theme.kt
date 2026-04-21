package com.voicenova.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PrimaryBlue = Color(0xFF2563EB)
val AccentBlue = Color(0xFF38BDF8)
val SuccessGreen = Color(0xFF16A34A)
val WarningAmber = Color(0xFFF59E0B)
val ErrorRed = Color(0xFFDC2626)
val AppBackground = Color(0xFFF3F6FB)
val SidebarBackground = Color(0xFFFFFFFF)
val CardSurface = Color(0xFFFFFFFF)
val HeroSurface = Color(0xFFEAF2FF)
val SelectedSurface = Color(0xFFEAF1FF)
val PrimaryText = Color(0xFF0F172A)
val SecondaryText = Color(0xFF64748B)
val AccentText = Color(0xFF1D4ED8)
val DividerColor = Color(0xFFD9E2EC)

private val VoiceNovaColors = lightColorScheme(
    primary = PrimaryBlue,
    secondary = AccentBlue,
    background = AppBackground,
    surface = CardSurface,
    onPrimary = Color.White,
    onBackground = PrimaryText,
    onSurface = PrimaryText
)

@Composable
fun VoiceNovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VoiceNovaColors,
        content = content
    )
}
