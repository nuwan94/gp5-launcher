package com.gp5.launcher.core.design

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gp5.launcher.core.model.RailSide

/** Top-level destinations, in rail order. Settings sits at the bottom of the rail. */
enum class Destination(val label: String, @DrawableRes val icon: Int) {
    Home("Home", R.drawable.ic_rail_home),
    Vehicle("Vehicle", R.drawable.ic_rail_vehicle),
    Trips("Trips", R.drawable.ic_rail_trips),
    Apps("Apps", R.drawable.ic_rail_apps),
    Settings("Settings", R.drawable.ic_rail_settings),
}

private val ItemShape = RoundedCornerShape(Gp5Dimens.RadiusCard)
private val ItemSize = 64.dp
private val ItemGap = 8.dp

/** 80 dp navigation rail (design/shell.css .rail): gold logo, 4 destinations, Settings at the bottom. */
@Composable
fun Rail(selected: Destination, onSelect: (Destination) -> Unit, side: RailSide, modifier: Modifier = Modifier) {
    val look = LocalGp5Look.current
    val top = Destination.entries.filter { it != Destination.Settings }
    val index = top.indexOf(selected)
    val indY by animateDpAsState(
        targetValue = (ItemSize + ItemGap) * index.coerceAtLeast(0) + 16.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "railIndicator",
    )
    Box(
        modifier
            .width(Gp5Dimens.Rail)
            .fillMaxHeight()
            .background(Gp5Palette.Rail)
            .then(railEdge(side)),
    ) {
        Column(Modifier.fillMaxHeight().align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(Gp5Dimens.Status), contentAlignment = Alignment.Center) { GoldLogo() }
            Box(Modifier.padding(top = ItemGap).weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                    top.forEach { d -> RailItem(d, d == selected) { onSelect(d) } }
                }
                if (index >= 0) {
                    // Accent indicator on the content-facing edge, springs between items
                    Box(
                        Modifier
                            .offset(x = if (side == RailSide.Left) (-8).dp else ItemSize + 5.dp, y = indY)
                            .size(3.dp, 32.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(look.accent),
                    )
                }
            }
            RailItem(Destination.Settings, selected == Destination.Settings) { onSelect(Destination.Settings) }
            Spacer(Modifier.height(Gp5Dimens.Gutter))
        }
    }
}

@Composable
private fun RailItem(d: Destination, on: Boolean, onClick: () -> Unit) {
    val look = LocalGp5Look.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (on) look.accentSoft else if (pressed) Gp5Palette.Surface2 else Color.Transparent,
        tween(Gp5Motion.Fast), label = "railBg",
    )
    val fg by animateColorAsState(if (on) look.accent else Gp5Palette.Text2, tween(Gp5Motion.Fast), label = "railFg")
    Box(
        Modifier
            .size(ItemSize)
            .scale(if (pressed) 0.94f else 1f)
            .clip(ItemShape)
            .background(bg)
            .then(if (on && look.goldTrim) Modifier.border(1.dp, Gp5Palette.GoldDim, ItemShape) else Modifier)
            .clickable(interactionSource = src, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(d.icon), contentDescription = d.label, tint = fg, modifier = Modifier.size(28.dp))
    }
}

/** Gold hairline on the content side with a highlight gliding down it every 6 s (gold.css .rail::after). */
@Composable
private fun railEdge(side: RailSide): Modifier {
    val look = LocalGp5Look.current
    val t = remember { Animatable(0f) }
    LaunchedEffect(look.ambient, look.goldTrim) {
        if (!look.ambient || !look.goldTrim) return@LaunchedEffect
        while (true) {
            t.snapTo(0f)
            t.animateTo(1f, tween(Gp5Motion.GoldGlide, easing = Gp5Motion.EaseOut))
        }
    }
    return Modifier.drawWithContent {
        drawContent()
        val w = 1.dp.toPx()
        val x = if (side == RailSide.Left) size.width - w / 2f else w / 2f
        drawLine(if (look.goldTrim) Gp5Palette.GoldDim else Gp5Palette.Line, Offset(x, 0f), Offset(x, size.height), w)
        if (look.goldTrim) {
            // A highlight 30% of the height glides from above the top to below the bottom in the first 70% of the cycle
            val p = (t.value / 0.7f).coerceAtMost(1f)
            val h = size.height * 0.3f
            val y = -h + (size.height + h) * p
            drawLine(
                Brush.verticalGradient(listOf(Color.Transparent, Gp5Palette.GoldHi, Color.Transparent), startY = y, endY = y + h),
                Offset(x, y), Offset(x, y + h), w,
            )
        }
    }
}

/** The owner's Honda emblem in gold, with a light sheen sweeping across every 7 s (parked only). */
@Composable
fun GoldLogo(modifier: Modifier = Modifier) {
    val look = LocalGp5Look.current
    val t = remember { Animatable(0f) }
    LaunchedEffect(look.ambient) {
        if (!look.ambient) return@LaunchedEffect
        while (true) {
            t.snapTo(0f)
            t.animateTo(1f, tween(Gp5Motion.LogoSheen, easing = Gp5Motion.EaseOut))
        }
    }
    Image(
        painterResource(R.drawable.honda_logo_gold),
        contentDescription = "Honda",
        modifier = modifier
            .size(38.dp, 31.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // Sheen in the last 40% of the cycle, left to right, clipped to the emblem (SrcAtop)
                val p = ((t.value - 0.6f) / 0.3f).coerceIn(0f, 1f)
                if (p > 0.001f && p < 0.999f) {
                    val x = -size.width + 3 * size.width * p
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color(0xE6FFF6D6), Color.Transparent),
                            start = Offset(x - size.width * 0.35f, 0f),
                            end = Offset(x + size.width * 0.35f, size.height),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                }
            },
    )
}
