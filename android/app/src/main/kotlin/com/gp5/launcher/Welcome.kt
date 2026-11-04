package com.gp5.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gp5.launcher.core.design.Gp5Motion
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.design.R as DesignR
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Greeting for the time of day. */
internal fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning,"
    in 12..16 -> "Good afternoon,"
    in 17..21 -> "Good evening,"
    else -> "Welcome back,"
}

/** 0..1 progress of [t] through the window [from, to] (fractions of the timeline). */
private fun phase(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

/**
 * Welcome when the car starts: the gold Honda logo with a light sweep, "Good morning," and the
 * driver's name letter by letter over a gold rule, the date and car underneath. After ~3 s it lifts
 * away to Home and [onReveal] flashes the 3D car's lamps. A tap skips it. Calm motion or system
 * animations off: a plain fade.
 */
@Composable
fun WelcomeOverlay(
    name: String,
    calm: Boolean,
    onStart: (greeting: String) -> Unit,
    onSkip: () -> Unit,
    onReveal: () -> Unit,
    onDone: () -> Unit,
) {
    val look = LocalGp5Look.current
    val t = remember { Animatable(0f) }          // timeline 0..1 over HOLD_MS
    val out = remember { Animatable(0f) }        // fade-out 0..1
    val scope = rememberCoroutineScope()
    val greet = remember { greeting() }
    val date = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date()) }

    suspend fun leave() {
        onReveal()
        out.animateTo(1f, tween(if (calm) 300 else 700, easing = Gp5Motion.EaseIn))
        onDone()
    }

    LaunchedEffect(Unit) {
        // On a cold start Home (and the 3D car) load behind the overlay; start the timeline once
        // frames are steady so the animation does not jump. Gives up waiting after 4 s.
        var steady = 0
        var last = 0L
        val until = android.os.SystemClock.uptimeMillis() + 4_000
        while (steady < 8 && android.os.SystemClock.uptimeMillis() < until) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            steady = if (last != 0L && now - last < 40_000_000L) steady + 1 else 0
            last = now
        }
        onStart(greet)
        if (calm) t.snapTo(1f) else t.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
        if (calm) kotlinx.coroutines.delay(1_800)
        leave()
    }

    val tv = t.value
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                alpha = 1f - out.value
                val s = 1f + 0.04f * out.value
                scaleX = s; scaleY = s
            }
            .background(Gp5Palette.Bg)
            .pointerInput(Unit) { detectTapGestures { onSkip(); scope.launch { t.snapTo(1f); leave() } } },
        contentAlignment = Alignment.Center,
    ) {
        // soft accent glow behind the logo, breathing in
        val glow = easeOut(phase(tv, 0f, 0.4f))
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(look.accent.copy(alpha = 0.16f * glow), Color.Transparent), center = Offset(size.width / 2, size.height * 0.36f), radius = size.minDimension * 0.62f),
                radius = size.minDimension * 0.62f, center = Offset(size.width / 2, size.height * 0.36f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // gold logo: scale in, then a light sweep across it
            val logoIn = easeOut(phase(tv, 0f, 0.22f))
            val sweep = phase(tv, 0.2f, 0.48f)
            Image(
                painterResource(DesignR.drawable.honda_logo_gold), "Honda",
                modifier = Modifier.size(width = 108.dp, height = 88.dp)
                    .graphicsLayer {
                        alpha = logoIn
                        val s = 0.82f + 0.18f * logoIn
                        scaleX = s; scaleY = s
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        if (sweep in 0.001f..0.999f) {
                            val x = -size.width * 0.6f + size.width * 2.2f * sweep
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent),
                                    start = Offset(x - size.width * 0.3f, 0f), end = Offset(x + size.width * 0.3f, size.height),
                                ),
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                    },
            )
            Spacer(Modifier.height(28.dp))
            val g = easeOut(phase(tv, 0.12f, 0.32f))
            Text(
                greet, style = Gp5Type.Title.copy(fontSize = 26.sp, color = Gp5Palette.Text2),
                modifier = Modifier.graphicsLayer { alpha = g; translationY = (1f - g) * 16.dp.toPx() },
            )
            // the name, letter by letter
            Row(Modifier.padding(top = 6.dp)) {
                name.forEachIndexed { i, ch ->
                    val start = 0.22f + i * (0.22f / name.length.coerceAtLeast(4))
                    val p = easeOut(phase(tv, start, start + 0.14f))
                    Text(
                        ch.toString(),
                        style = Gp5Type.Hero.copy(fontSize = 68.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold, color = Gp5Palette.Text),
                        modifier = Modifier.graphicsLayer { alpha = p; translationY = (1f - p) * 22.dp.toPx() },
                    )
                }
            }
            // gold rule drawing out from the centre
            val rule = easeOut(phase(tv, 0.42f, 0.62f))
            Canvas(Modifier.padding(top = 14.dp).width(260.dp).height(2.dp)) {
                val half = size.width / 2 * rule
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, Gp5Palette.GoldHi, Gp5Palette.GoldMid, Color.Transparent), startX = size.width / 2 - half, endX = size.width / 2 + half),
                    Offset(size.width / 2 - half, 0f), Offset(size.width / 2 + half, 0f), strokeWidth = size.height,
                )
            }
            val sub = easeOut(phase(tv, 0.55f, 0.75f))
            Row(
                Modifier.padding(top = 14.dp).graphicsLayer { alpha = sub },
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(date, style = Gp5Type.Label.copy(fontSize = 15.sp))
                Box(Modifier.size(4.dp).background(Gp5Palette.Text3))
                Text("Fit GP5 Hybrid", style = Gp5Type.Label.copy(fontSize = 15.sp, color = look.accent))
            }
        }
    }
}

private const val HOLD_MS = 3_000
