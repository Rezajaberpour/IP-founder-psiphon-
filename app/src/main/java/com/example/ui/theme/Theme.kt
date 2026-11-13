package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val MlmDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = Color(0xFFD6EAFF),
    secondary = EmeraldShield,
    onSecondary = OnEmerald,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = EmeraldShield,
    tertiary = PsiphonCrimson,
    onTertiary = Color.White,
    tertiaryContainer = PsiphonCrimsonContainer,
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = MidnightSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = MidnightSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLow = MidnightSurface,
    surfaceContainer = MidnightSurfaceElevated,
    surfaceContainerHigh = MidnightSurfaceHigh,
    outline = MidnightOutline,
    outlineVariant = MidnightOutlineSubtle,
    error = CoralError,
    onError = Color.White,
    errorContainer = CoralContainer,
    onErrorContainer = Color(0xFFFFDAD6)
)

private val MlmLightColorScheme = lightColorScheme(
    primary = DeepCyanPrimary,
    onPrimary = Color.White,
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = Color(0xFF001F26),
    secondary = DeepEmeraldSecondary,
    onSecondary = Color.White,
    secondaryContainer = DeepEmeraldContainer,
    onSecondaryContainer = Color(0xFF00210E),
    tertiary = DeepVioletTertiary,
    onTertiary = Color.White,
    tertiaryContainer = DeepVioletContainer,
    onTertiaryContainer = Color(0xFF1D0061),
    background = LightBg,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = LightOutline,
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

val MlmShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MlmVpnTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    isPersian: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MlmDarkColorScheme
        else -> MlmLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = if (isPersian) MlmPersianTypography else MlmTypography,
        shapes = MlmShapes,
        content = content
    )
}
