package com.minimallabs.vsync.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.minimallabs.vsync.core.App

/**
 * Liquid glass (Kyant's Backdrop) for what floats: the tab bar, the Thumb
 * Dock, the OTP sheet. Cards stay solid, as in the Flutter app. Off in
 * Appearance, or on Android < 12, glass falls back to a solid surface.
 */
@Immutable
class GlassEnv(val enabled: Boolean, val background: Backdrop?)

val LocalGlass = staticCompositionLocalOf { GlassEnv(false, null) }

val glassSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
private val lensSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

enum class GlassKind { Card, Bar, Control }

/** The page background (recorded for glass) and the glass switch for [content]. */
@Composable
fun GlassRoot(content: @Composable BoxScope.() -> Unit) {
    val enabled = App.prefs.liquidGlass && glassSupported
    val bg = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AmbientBackground(Modifier.fillMaxSize().let { if (enabled) it.layerBackdrop(bg) else it })
        CompositionLocalProvider(LocalGlass provides GlassEnv(enabled, if (enabled) bg else null)) { content() }
    }
}

/** The plain background with a faint glow at the top, like the Flutter timetable header. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.background
    val glow = if (LocalTones.current.dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.025f)
    Canvas(modifier) {
        drawRect(base)
        val c = Offset(size.width * 0.45f, 0f)
        val r = size.width * 0.75f
        drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center = c, radius = r), radius = r, center = c)
    }
}

/**
 * A surface. Cards are solid (surfaceContainerLow). Bars and controls are
 * liquid glass over [backdrop] when glass is on.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    kind: GlassKind = GlassKind.Card,
    backdrop: Backdrop? = LocalGlass.current.background,
    tint: Color? = null,
    bordered: Boolean = false,
): Modifier {
    val env = LocalGlass.current
    val tones = LocalTones.current
    val solid = when (kind) {
        GlassKind.Card -> MaterialTheme.colorScheme.surfaceContainerLow
        GlassKind.Bar -> if (tones.dark) Color(0xF2121214) else Color(0xF7FFFFFF)
        GlassKind.Control -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    if (kind == GlassKind.Card || !env.enabled || backdrop == null) {
        val base = this.clip(shape).background(tint ?: solid, shape)
        return if (bordered || kind == GlassKind.Bar) base.border(1.dp, tones.separator, shape) else base
    }
    val surface = tint ?: if (kind == GlassKind.Bar) tones.glassStrongTint else tones.glassTint
    val blurDp: Dp = if (kind == GlassKind.Bar) 8.dp else 4.dp
    val lensHeight: Dp = if (kind == GlassKind.Bar) 20.dp else 12.dp
    val lensAmount: Dp = if (kind == GlassKind.Bar) 36.dp else 20.dp
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurDp.toPx())
            if (lensSupported) lens(lensHeight.toPx(), lensAmount.toPx())
        },
        highlight = { if (tones.dark) Highlight.Default.copy(alpha = 0.6f) else Highlight.Default },
        shadow = { Shadow(radius = 20.dp, color = Color.Black.copy(alpha = if (tones.dark) 0.45f else 0.10f)) },
        onDrawSurface = { drawRect(surface) },
    )
}

val CardShape = RoundedCornerShape(18.dp)
val GroupShape = RoundedCornerShape(22.dp)
