package dev.droidserver.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DroidServer'ın görünümü: deniz yeşili (teal) marka rengi, açık/koyu tema ve isteğe bağlı Material You (Android 12+,
 * duvar kâğıdı renkleri). Seçimler [ThemeSettings]'te; değişiklik hemen uygulanır.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF006A65), onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2EA), onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6360), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E4), onSecondaryContainer = Color(0xFF051F1D),
    tertiary = Color(0xFF8A5100), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCBE), onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FBF9), onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF9), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    inverseSurface = Color(0xFF2B3231), inverseOnSurface = Color(0xFFECF2F0), inversePrimary = Color(0xFF82D5CE),
    surfaceTint = Color(0xFF006A65),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEEF5F3), surfaceContainer = Color(0xFFE8EFED),
    surfaceContainerHigh = Color(0xFFE3EAE8), surfaceContainerHighest = Color(0xFFDDE4E2),
    surfaceBright = Color(0xFFF4FBF9), surfaceDim = Color(0xFFD5DBD9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF82D5CE), onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504C), onPrimaryContainer = Color(0xFF9EF2EA),
    secondary = Color(0xFFB0CCC8), onSecondary = Color(0xFF1C3532),
    secondaryContainer = Color(0xFF324B48), onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFFFB870), onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00), onTertiaryContainer = Color(0xFFFFDCBE),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1514), onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899391), outlineVariant = Color(0xFF3F4947),
    inverseSurface = Color(0xFFDDE4E2), inverseOnSurface = Color(0xFF2B3231), inversePrimary = Color(0xFF006A65),
    surfaceTint = Color(0xFF82D5CE),
    surfaceContainerLowest = Color(0xFF090F0F), surfaceContainerLow = Color(0xFF161D1C), surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF252B2A), surfaceContainerHighest = Color(0xFF2F3635),
    surfaceBright = Color(0xFF343B3A), surfaceDim = Color(0xFF0E1514),
)

/** Başarı ve uyarı renkleri (Material'da yok): çalışıyor/hazır ve dikkat durumları. */
@Immutable
data class StatusColors(val success: Color, val onSuccess: Color, val successContainer: Color, val onSuccessContainer: Color, val warning: Color, val warningContainer: Color, val onWarningContainer: Color)

private val LightStatus = StatusColors(Color(0xFF1B6C2E), Color.White, Color(0xFFD3F2D4), Color(0xFF002107), Color(0xFF8A5100), Color(0xFFFFDCBE), Color(0xFF2C1600))
private val DarkStatus = StatusColors(Color(0xFF89D88E), Color(0xFF003910), Color(0xFF173B22), Color(0xFFC6EEC8), Color(0xFFFFB870), Color(0xFF693C00), Color(0xFFFFDCBE))

val LocalStatusColors = compositionLocalOf { LightStatus }

/** Tema seçimleri: uygulama ayarlarından okunur, ayar ekranı değiştirince hemen uygulanır. */
object ThemeSettings {
    var mode by mutableStateOf("system")
    var dynamic by mutableStateOf(false)
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodySmall = t.bodySmall.copy(lineHeight = 18.sp),
    )
}

@Composable
fun DroidServerTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeSettings.mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        ThemeSettings.dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
