package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.procpulse.core.model.AccentPreset
import com.example.procpulse.core.model.ThemeMode

@Immutable
data class ProcPulsePalette(
    val isDark: Boolean,
    val background: Color,
    val card: Color,
    val innerSurface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val okGreen: Color,
    val warnAmber: Color,
    val critRed: Color
)

val LocalProcPulsePalette = staticCompositionLocalOf {
    ProcPulsePalette(
        isDark = true,
        background = DarkBackground,
        card = DarkSurfaceCard,
        innerSurface = DarkSurfaceInner,
        border = DarkBorder,
        textPrimary = DarkTextPrimary,
        textSecondary = DarkTextSecondary,
        textMuted = DarkTextMuted,
        accent = SignalAmber,
        okGreen = PhosphorGreen,
        warnAmber = SignalAmber,
        critRed = CriticalRed
    )
}

private val PrecisionShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

@Composable
fun ProcPulseTheme(
    themeMode: ThemeMode = ThemeMode.AMOLED_DARK,
    accentPreset: AccentPreset = AccentPreset.SIGNAL_AMBER,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.AMOLED_DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val useDynamic = accentPreset == AccentPreset.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val baseScheme = if (useDynamic) {
        val dyn = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val accent = dyn.primary
        if (isDark) {
            dyn.copy(
                primaryContainer = accent.copy(alpha = 0.16f),
                onPrimaryContainer = accent,
                secondaryContainer = accent.copy(alpha = 0.16f),
                onSecondaryContainer = accent,
                tertiaryContainer = DarkSurfaceInner,
                onTertiaryContainer = DarkTextPrimary,
                background = DarkBackground,
                onBackground = DarkTextPrimary,
                surface = DarkSurfaceCard,
                onSurface = DarkTextPrimary,
                surfaceVariant = DarkSurfaceInner,
                onSurfaceVariant = DarkTextSecondary,
                surfaceContainerLowest = DarkBackground,
                surfaceContainerLow = DarkSurfaceCard,
                surfaceContainer = DarkSurfaceInner,
                surfaceContainerHigh = Color(0xFF1E1E22),
                surfaceContainerHighest = DarkBorder,
                surfaceTint = Color.Transparent,
                outline = DarkBorder,
                outlineVariant = DarkBorder
            )
        } else {
            dyn.copy(
                primaryContainer = accent.copy(alpha = 0.16f),
                onPrimaryContainer = accent,
                secondaryContainer = accent.copy(alpha = 0.16f),
                onSecondaryContainer = accent,
                tertiaryContainer = LightSurfaceInner,
                onTertiaryContainer = LightTextPrimary,
                background = LightBackground,
                onBackground = LightTextPrimary,
                surface = LightSurfaceCard,
                onSurface = LightTextPrimary,
                surfaceVariant = LightSurfaceInner,
                onSurfaceVariant = LightTextSecondary,
                surfaceContainerLowest = LightSurfaceCard,
                surfaceContainerLow = LightBackground,
                surfaceContainer = LightSurfaceInner,
                surfaceContainerHigh = LightBorder,
                surfaceContainerHighest = LightBorder,
                surfaceTint = Color.Transparent,
                outline = LightBorder,
                outlineVariant = LightBorder
            )
        }
    } else {
        val accentColor = Color(accentPreset.hex)
        if (isDark) {
            darkColorScheme(
                primary = accentColor,
                onPrimary = Color(0xFF09090B),
                primaryContainer = accentColor.copy(alpha = 0.16f),
                onPrimaryContainer = accentColor,
                secondary = accentColor,
                onSecondary = Color(0xFF09090B),
                secondaryContainer = accentColor.copy(alpha = 0.16f),
                onSecondaryContainer = accentColor,
                tertiary = TechBlue,
                onTertiary = Color(0xFF09090B),
                tertiaryContainer = DarkSurfaceInner,
                onTertiaryContainer = DarkTextPrimary,
                background = DarkBackground,
                onBackground = DarkTextPrimary,
                surface = DarkSurfaceCard,
                onSurface = DarkTextPrimary,
                surfaceVariant = DarkSurfaceInner,
                onSurfaceVariant = DarkTextSecondary,
                surfaceContainerLowest = DarkBackground,
                surfaceContainerLow = DarkSurfaceCard,
                surfaceContainer = DarkSurfaceInner,
                surfaceContainerHigh = Color(0xFF1E1E22),
                surfaceContainerHighest = DarkBorder,
                surfaceTint = Color.Transparent,
                outline = DarkBorder,
                outlineVariant = DarkBorder,
                error = CriticalRed,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = accentColor,
                onPrimary = Color.White,
                primaryContainer = accentColor.copy(alpha = 0.16f),
                onPrimaryContainer = accentColor,
                secondary = accentColor,
                onSecondary = Color.White,
                secondaryContainer = accentColor.copy(alpha = 0.16f),
                onSecondaryContainer = accentColor,
                tertiary = TechBlue,
                onTertiary = Color.White,
                tertiaryContainer = LightSurfaceInner,
                onTertiaryContainer = LightTextPrimary,
                background = LightBackground,
                onBackground = LightTextPrimary,
                surface = LightSurfaceCard,
                onSurface = LightTextPrimary,
                surfaceVariant = LightSurfaceInner,
                onSurfaceVariant = LightTextSecondary,
                surfaceContainerLowest = LightSurfaceCard,
                surfaceContainerLow = LightBackground,
                surfaceContainer = LightSurfaceInner,
                surfaceContainerHigh = LightBorder,
                surfaceContainerHighest = LightBorder,
                surfaceTint = Color.Transparent,
                outline = LightBorder,
                outlineVariant = LightBorder,
                error = CriticalRed,
                onError = Color.White
            )
        }
    }

    val palette = if (isDark) {
        ProcPulsePalette(
            isDark = true,
            background = DarkBackground,
            card = DarkSurfaceCard,
            innerSurface = DarkSurfaceInner,
            border = DarkBorder,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            textMuted = DarkTextMuted,
            accent = baseScheme.primary,
            okGreen = PhosphorGreen,
            warnAmber = SignalAmber,
            critRed = CriticalRed
        )
    } else {
        ProcPulsePalette(
            isDark = false,
            background = LightBackground,
            card = LightSurfaceCard,
            innerSurface = LightSurfaceInner,
            border = LightBorder,
            textPrimary = LightTextPrimary,
            textSecondary = LightTextSecondary,
            textMuted = LightTextMuted,
            accent = baseScheme.primary,
            okGreen = PhosphorGreen,
            warnAmber = SignalAmber,
            critRed = CriticalRed
        )
    }

    CompositionLocalProvider(LocalProcPulsePalette provides palette) {
        MaterialTheme(
            colorScheme = baseScheme,
            typography = ProcPulseTypography,
            shapes = PrecisionShapes,
            content = content
        )
    }
}
