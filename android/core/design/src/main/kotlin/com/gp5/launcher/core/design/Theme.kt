package com.gp5.launcher.core.design

import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Theme values that change at runtime (Settings > Display) or with the car's state. */
@Immutable
data class Gp5Look(
    val accent: Color = Color(0xFFE24B4A),
    val goldTrim: Boolean = true,
    /** Ambient animation allowed: parked, calm motion off, system animations on. */
    val ambient: Boolean = true,
    /** km/L or L/100 km, for every economy figure. */
    val economy: com.gp5.launcher.core.model.EconomyUnit = com.gp5.launcher.core.model.EconomyUnit.KmPerL,
) {
    val accentSoft: Color get() = accent.copy(alpha = 0.14f)
}

val LocalGp5Look = staticCompositionLocalOf { Gp5Look() }

// TODO(M1): bundle Barlow + Barlow Semi Condensed (SIL OFL) as res/font and swap these in.
val Gp5Sans: FontFamily = FontFamily.SansSerif
val Gp5Num: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))

object Gp5Type {
    val Hero = TextStyle(fontFamily = Gp5Num, fontWeight = FontWeight.SemiBold, fontSize = 72.sp, lineHeight = 72.sp)
    val Display = TextStyle(fontFamily = Gp5Num, fontWeight = FontWeight.SemiBold, fontSize = 34.sp)
    val Title = TextStyle(fontFamily = Gp5Sans, fontWeight = FontWeight.Medium, fontSize = 20.sp)
    val Body = TextStyle(fontFamily = Gp5Sans, fontSize = 16.sp)
    val Label: TextStyle get() = TextStyle(fontFamily = Gp5Sans, fontSize = 13.sp, color = Gp5Palette.Text2)
    val Caption: TextStyle get() = TextStyle(fontFamily = Gp5Sans, fontSize = 12.sp, color = Gp5Palette.Text2)
}

/** True when the user (or a battery saver) turned system animations off: treat as reduced motion. */
@Composable
fun systemAnimationsOff(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
fun Gp5Theme(look: Gp5Look = Gp5Look(), night: Boolean = true, content: @Composable () -> Unit) {
    val reduced = systemAnimationsOff()
    // day / night: set at once on start, then a 600 ms cross-fade of every colour
    remember { Gp5Palette.night = if (night) 1f else 0f }
    LaunchedEffect(night) {
        val to = if (night) 1f else 0f
        if (Gp5Palette.night != to) {
            if (reduced) Gp5Palette.night = to
            else animate(Gp5Palette.night, to, animationSpec = tween(600, easing = Gp5Motion.EaseOut)) { v, _ -> Gp5Palette.night = v }
        }
    }
    // the accent a shade darker in daylight so it holds its contrast on the light surfaces
    val accent = lerp(look.accent, Color.Black, 0.14f * (1f - Gp5Palette.night))
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        background = Gp5Palette.Bg,
        onBackground = Gp5Palette.Text,
        surface = Gp5Palette.Surface1,
        onSurface = Gp5Palette.Text,
        surfaceVariant = Gp5Palette.Surface2,
        onSurfaceVariant = Gp5Palette.Text2,
        outline = Gp5Palette.Line,
    )
    CompositionLocalProvider(LocalGp5Look provides look.copy(accent = accent, ambient = look.ambient && !reduced)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
