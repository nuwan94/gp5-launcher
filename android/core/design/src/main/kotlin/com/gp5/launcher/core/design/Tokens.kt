package com.gp5.launcher.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

// 1:1 port of design/tokens.css (1 px in the prototype = 1 dp on the 1024x600 head unit).

/** One complete set of colours. [Night] is design/tokens.css; [Day] is the light theme for sunlight. */
class Gp5Colors(
    val bg: Color, val rail: Color, val surface1: Color, val surface2: Color, val stage: Color,
    val line: Color, val lineStrong: Color, val text: Color, val text2: Color, val text3: Color,
    val ev: Color, val regen: Color, val warn: Color,
    val goldDim: Color, val goldMid: Color, val goldHi: Color,
    val road: Color, val roadPaint: Color,
) {
    companion object {
        val Night = Gp5Colors(
            bg = Color(0xFF121212), rail = Color(0xFF0C0C0C), surface1 = Color(0xFF1A1A1A), surface2 = Color(0xFF232323),
            stage = Color(0xFF151515), line = Color(0xFF2A2A2A), lineStrong = Color(0xFF3A3A3A),
            text = Color(0xFFF2F0EA), text2 = Color(0xFFA8A69E), text3 = Color(0xFF6E6C66),
            ev = Color(0xFF8FD14F), regen = Color(0xFF4EA8F2), warn = Color(0xFFEF9F27),
            // gold trim (design/gold.css), matching the car's gold Honda emblems
            goldDim = Color(0x4DD4A640), goldMid = Color(0xA6E2BA60), goldHi = Color(0xFFFFF1C4),
            road = Color(0xFF242424), roadPaint = Color(0xFFD8D8D8),
        )
        /** Warm paper-white (not pure white: less glare), darker accents and antique gold so they read in sunlight. */
        val Day = Gp5Colors(
            bg = Color(0xFFECEAE4), rail = Color(0xFFE1DED6), surface1 = Color(0xFFFAF9F6), surface2 = Color(0xFFEFEDE8),
            stage = Color(0xFFE4E1D9), line = Color(0xFFD6D2C8), lineStrong = Color(0xFFC3BFB5),
            text = Color(0xFF1A1A18), text2 = Color(0xFF55534D), text3 = Color(0xFF8A877F),
            ev = Color(0xFF3E8E1F), regen = Color(0xFF1F6FB8), warn = Color(0xFFB86E0A),
            goldDim = Color(0x99B08A2E), goldMid = Color(0xCC9C7A24), goldHi = Color(0xFFC9A24A),
            road = Color(0xFFCFCBC2), roadPaint = Color(0xFFFBFAF7),
        )
    }
}

/**
 * The colours in use. [night] runs 0 (day) .. 1 (night) and is animated by [Gp5Theme]; it is snapshot
 * state, so every composable and canvas that reads a colour follows a theme change by itself.
 */
object Gp5Palette {
    var night by mutableFloatStateOf(1f)
    val isDay: Boolean get() = night < 0.5f
    private fun mix(f: (Gp5Colors) -> Color): Color {
        val n = night
        return if (n >= 1f) f(Gp5Colors.Night) else if (n <= 0f) f(Gp5Colors.Day) else lerp(f(Gp5Colors.Day), f(Gp5Colors.Night), n)
    }

    val Bg: Color get() = mix { it.bg }
    val Rail: Color get() = mix { it.rail }
    val Surface1: Color get() = mix { it.surface1 }
    val Surface2: Color get() = mix { it.surface2 }
    val Stage: Color get() = mix { it.stage }
    val Line: Color get() = mix { it.line }
    val LineStrong: Color get() = mix { it.lineStrong }

    val Text: Color get() = mix { it.text }
    val Text2: Color get() = mix { it.text2 }
    val Text3: Color get() = mix { it.text3 }

    val Ev: Color get() = mix { it.ev }
    val Regen: Color get() = mix { it.regen }
    val Warn: Color get() = mix { it.warn }

    val GoldDim: Color get() = mix { it.goldDim }
    val GoldMid: Color get() = mix { it.goldMid }
    val GoldHi: Color get() = mix { it.goldHi }

    val Road: Color get() = mix { it.road }
    val RoadPaint: Color get() = mix { it.roadPaint }
}

object Gp5Dimens {
    val ScreenW = 1024.dp
    val ScreenH = 600.dp
    val Rail = 80.dp
    val Status = 40.dp
    val Gutter = 12.dp
    val RightCol = 300.dp
    val Smart = 72.dp
    val Dock = 72.dp
    val TouchMin = 64.dp

    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S5 = 20.dp
    val S6 = 24.dp

    val RadiusCard = 10.dp
    val RadiusCtl = 8.dp
}

/**
 * Motion tokens. Rules (driver safety): touch response <= Fast, state changes <= Base,
 * data fills use Slow and never bounce, ambient loops only when parked.
 */
object Gp5Motion {
    const val Fast = 150
    const val Base = 250
    const val Slow = 600
    const val Ambient = 2400
    const val Stagger = 50
    const val GoldOrbit = 9000
    const val GoldGlide = 6000
    const val LogoSheen = 7000

    val EaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val Spring = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
}
