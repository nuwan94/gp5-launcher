package com.gp5.launcher.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 1:1 port of design/tokens.css (1 px in the prototype = 1 dp on the 1024x600 head unit).

object Gp5Palette {
    val Bg = Color(0xFF121212)
    val Rail = Color(0xFF0C0C0C)
    val Surface1 = Color(0xFF1A1A1A)
    val Surface2 = Color(0xFF232323)
    val Stage = Color(0xFF151515)
    val Line = Color(0xFF2A2A2A)
    val LineStrong = Color(0xFF3A3A3A)

    val Text = Color(0xFFF2F0EA)
    val Text2 = Color(0xFFA8A69E)
    val Text3 = Color(0xFF6E6C66)

    val Ev = Color(0xFF8FD14F)
    val Regen = Color(0xFF4EA8F2)
    val Warn = Color(0xFFEF9F27)

    // Gold trim (design/gold.css), matching the car's gold Honda emblems
    val GoldDim = Color(0x4DD4A640)      // rgba(212,166,64,.30)
    val GoldMid = Color(0xA6E2BA60)      // rgba(226,186,96,.65)
    val GoldHi = Color(0xFFFFF1C4)
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
