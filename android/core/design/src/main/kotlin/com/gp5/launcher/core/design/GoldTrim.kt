package com.gp5.launcher.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/**
 * Gold trim (port of design/gold.css): a 1 dp gold edge plus a brighter arc travelling around it
 * once every [Gp5Motion.GoldOrbit] ms. [phaseDeg] staggers cards so highlights don't line up.
 * The arc freezes in place when ambient motion is off (driving, calm motion, system animations off);
 * with Settings > Gold trim off it falls back to the plain grey hairline.
 * The angle is read only in the draw phase, so the animation redraws without recomposing.
 */
fun Modifier.goldTrim(shape: Shape, phaseDeg: Float = 0f): Modifier = composed {
    val look = LocalGp5Look.current
    val angle = remember { Animatable(phaseDeg) }
    LaunchedEffect(look.ambient, look.goldTrim) {
        if (!look.ambient || !look.goldTrim) return@LaunchedEffect
        while (true) {
            val from = angle.value % 360f
            angle.snapTo(from)
            angle.animateTo(from + 360f, tween(Gp5Motion.GoldOrbit, easing = LinearEasing))
        }
    }
    drawWithCache {
        val w = 1.dp.toPx()
        val inner = Size(size.width - w, size.height - w)
        val outline = shape.createOutline(inner, layoutDirection, this)
        val stroke = Stroke(width = w)
        val center = Offset(size.width / 2f, size.height / 2f)
        val matrix = android.graphics.Matrix()
        onDrawWithContent {
            drawContent()
            translate(w / 2f, w / 2f) {
                if (!look.goldTrim) {
                    drawOutline(outline, Gp5Palette.Line, style = stroke)
                    return@translate
                }
                drawOutline(outline, Gp5Palette.GoldDim, style = stroke)
                val shader = SweepGradientShader(
                    center = center - Offset(w / 2f, w / 2f),
                    colors = listOf(Color.Transparent, Color.Transparent, Gp5Palette.GoldMid, Gp5Palette.GoldHi,
                        Gp5Palette.GoldMid, Color.Transparent, Color.Transparent),
                    colorStops = listOf(0f, 0.61f, 0.73f, 0.78f, 0.83f, 0.94f, 1f),
                )
                matrix.setRotate(angle.value, center.x, center.y)
                shader.setLocalMatrix(matrix)
                drawOutline(outline, ShaderBrush(shader), style = stroke)
            }
        }
    }
}
