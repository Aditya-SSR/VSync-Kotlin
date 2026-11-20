package com.minimallabs.vsync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.minimallabs.vsync.R
import com.minimallabs.vsync.data.Prefs

// ------------------------------------------------------------------ type

@OptIn(ExperimentalTextApi::class)
private fun instrument(weight: Int) = Font(
    R.font.instrument_sans, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Instrument Sans: the app's voice (headings and text). */
val Instrument = FontFamily(instrument(400), instrument(500), instrument(600))

/** Inter: small details (captions, labels, numbers). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.W400),
    Font(R.font.inter_medium, FontWeight.W500),
    Font(R.font.inter_semibold, FontWeight.W600),
)

private fun s(family: FontFamily, size: Int, line: Int, weight: Int, track: Double, color: Color) = TextStyle(
    fontFamily = family, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = track.sp, color = color,
)

/**
 * The Flutter app's scale (Material sizes, Instrument Sans, headings w500 in
 * the heading colour), with Inter for the small labels and captions.
 */
private fun typography(heading: Color, onSurface: Color, variant: Color) = Typography(
    displayLarge = s(Instrument, 57, 64, 500, -0.5, heading),
    displayMedium = s(Instrument, 45, 52, 500, -0.5, heading),
    displaySmall = s(Instrument, 36, 44, 500, -0.25, heading),
    headlineLarge = s(Instrument, 26, 34, 500, -0.3, heading),   // page titles
    headlineMedium = s(Instrument, 24, 32, 500, -0.25, heading),
    headlineSmall = s(Instrument, 22, 28, 500, 0.0, heading),    // section headings
    titleLarge = s(Instrument, 22, 28, 500, 0.0, heading),
    titleMedium = s(Instrument, 16, 24, 500, 0.1, onSurface),
    titleSmall = s(Instrument, 14, 20, 500, 0.1, onSurface),
    bodyLarge = s(Instrument, 16, 24, 400, 0.15, onSurface),
    bodyMedium = s(Instrument, 13, 18, 400, 0.2, onSurface),
    bodySmall = s(Inter, 11, 15, 400, 0.1, variant),
    labelLarge = s(Instrument, 14, 20, 500, 0.2, onSurface),
    labelMedium = s(Inter, 12, 16, 500, 0.3, variant),
    labelSmall = s(Inter, 11, 16, 500, 0.4, variant),
)

// ------------------------------------------------------------------ colour

data class StatusColors(
    val success: Color, val successContainer: Color,
    val warning: Color, val warningContainer: Color,
    val danger: Color, val dangerContainer: Color,
    val neutral: Color, val neutralContainer: Color,
)

// The Flutter app's AppStatusColors.
private val LightStatus = StatusColors(
    Color(0xFF2E7D32), Color(0xFFD8F3DC), Color(0xFFB45309), Color(0xFFFFF0D5),
    Color(0xFFB3261E), Color(0xFFFFDAD6), Color(0xFF5B5B63), Color(0xFFE7E7EA),
)
private val DarkStatus = StatusColors(
    Color(0xFF7BD8A0), Color(0xFF123A24), Color(0xFFF6C46A), Color(0xFF4A3210),
    Color(0xFFE05A4A), Color(0xFF5C1518), Color(0xFFC5C5CA), Color(0xFF3A3A3E),
)

val LocalStatus = staticCompositionLocalOf { DarkStatus }

/** Tones the Material scheme has no slot for. */
data class Tones(
    val separator: Color,
    val fill: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val heading: Color,
    val glassTint: Color,
    val glassStrongTint: Color,
    val dark: Boolean,
)

val LocalTones = staticCompositionLocalOf {
    Tones(Color.Gray, Color.Gray, Color.Gray, Color.Gray, Color.White, Color.White, Color.White, true)
}

/** Accent choices; the key is the saved `appTheme` value. Mono is the default. */
val AccentThemes: List<Pair<String, String>> = listOf(
    "blue" to "Mono", "sky" to "Sky", "gold" to "Gold", "emerald" to "Emerald", "red" to "Red", "pink" to "Pink",
)

private fun accentFor(name: String, dark: Boolean): Color? = when (name) {
    "sky" -> if (dark) Color(0xFFA9DCEB) else Color(0xFF2B7A99)
    "gold" -> if (dark) Color(0xFFD4AF37) else Color(0xFFB08D26)
    "emerald" -> if (dark) Color(0xFF4EA77D) else Color(0xFF2E7A52)
    "red" -> if (dark) Color(0xFFE05252) else Color(0xFFC62828)
    "pink" -> if (dark) Color(0xFFF48FB1) else Color(0xFFC2185B)
    else -> null // mono: headings and selections use the text colour
}

private class Palette(
    val bg: Color, val card: Color, val high: Color, val highest: Color, val container: Color,
    val on: Color, val variant: Color, val outline: Color, val outlineVariant: Color,
)

// The Flutter app's neutral surfaces.
private val DarkPalette = Palette(
    Color(0xFF0B0B0C), Color(0xFF111113), Color(0xFF1B1B1F), Color(0xFF242427), Color(0xFF151518),
    Color(0xFFFAFAFA), Color(0xFFB8B8BE), Color(0xFF6A6A72), Color(0xFF303036),
)
private val LightPalette = Palette(
    Color(0xFFF7F7F8), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFECECF0), Color(0xFFFBFBFC),
    Color(0xFF111113), Color(0xFF5B5B63), Color(0xFF777780), Color(0xFFE1E1E6),
)

private fun scheme(dark: Boolean, amoled: Boolean, accentName: String): ColorScheme {
    val p = if (dark) DarkPalette else LightPalette
    val accent = accentFor(accentName, dark) ?: p.on
    val onAccent = if (accentFor(accentName, dark) == null) (if (dark) Color.Black else Color.White) else (if (dark) Color(0xFF0B0B0C) else Color.White)
    val bg = if (dark && amoled) Color.Black else p.bg
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = p.high, onPrimaryContainer = p.on,
        secondary = p.variant, onSecondary = onAccent,
        secondaryContainer = p.high, onSecondaryContainer = p.on,
        tertiary = accent, onTertiary = onAccent, tertiaryContainer = p.high,
        background = bg, onBackground = p.on,
        surface = bg, onSurface = p.on,
        surfaceVariant = p.high, onSurfaceVariant = p.variant,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = if (dark) bg else Color(0xFFF0F0F3),
        surfaceContainerLow = p.card,
        surfaceContainer = p.container,
        surfaceContainerHigh = p.high,
        surfaceContainerHighest = p.highest,
        inverseSurface = p.on, inverseOnSurface = p.bg,
        outline = p.outline, outlineVariant = p.outlineVariant,
        error = if (dark) Color(0xFFE6A0A0) else Color(0xFFB3261E), onError = if (dark) Color(0xFF3A0908) else Color.White,
    )
}

private fun tones(dark: Boolean, accentName: String): Tones {
    val p = if (dark) DarkPalette else LightPalette
    return Tones(
        separator = p.outlineVariant,
        fill = if (dark) Color(0xFF1B1B1F) else Color(0xFFECECF0),
        secondaryLabel = p.variant,
        tertiaryLabel = p.outline,
        heading = accentFor(accentName, dark) ?: p.on,
        glassTint = if (dark) Color(0x5C111113) else Color(0x8CFFFFFF),
        glassStrongTint = if (dark) Color(0x8C111113) else Color(0xB3FFFFFF),
        dark = dark,
    )
}

/** Dark until the student picks a mode (Appearance can also follow the system). */
fun isDark(prefs: Prefs, systemDark: Boolean): Boolean = when {
    prefs.followSystemTheme -> systemDark
    prefs.hasUserChosenTheme -> prefs.isDarkModeEnabled
    else -> true
}

@Composable
fun VSyncTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val dark = isDark(prefs, isSystemInDarkTheme())
    val density = LocalDensity.current
    val t = tones(dark, prefs.appTheme)
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(
        LocalStatus provides if (dark) DarkStatus else LightStatus,
        LocalTones provides t,
        // Like the Flutter app: the saved text size (default 1.2) replaces the system text scale.
        LocalDensity provides Density(density.density, prefs.fontScale.toFloat()),
    ) {
        MaterialTheme(
            colorScheme = scheme(dark, prefs.isAmoledEnabled, prefs.appTheme),
            typography = typography(t.heading, p.on, p.variant),
        ) {
            // Every Text gets the right colour by default (it was black on black).
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides p.on, content = content)
        }
    }
}
