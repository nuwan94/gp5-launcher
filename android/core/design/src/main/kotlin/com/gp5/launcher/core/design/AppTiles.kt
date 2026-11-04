package com.gp5.launcher.core.design

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Loads a package's icon at a pixel size (AppsRepository.icon). */
typealias IconLoader = suspend (packageName: String, sizePx: Int) -> Bitmap?

/** An app icon, loaded off the main thread; a soft circle stands in until it arrives. */
@Composable
fun AppIcon(packageName: String, size: Dp, load: IconLoader, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, packageName, px) { value = load(packageName, px)?.asImageBitmap() }
    val alpha by animateFloatAsState(if (bmp != null) 1f else 0f, tween(Gp5Motion.Fast), label = "icon")
    Box(modifier.size(size)) {
        Box(Modifier.fillMaxSize().padding(size / 10).clip(CircleShape).background(Gp5Palette.Surface2))
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) }
    }
}

/** Holds the latest on-screen bounds of a composable, for launch animations from the icon. */
class BoundsHolder {
    var rect: android.graphics.Rect? = null
}

@Composable
fun rememberBounds(): BoundsHolder = remember { BoundsHolder() }

fun Modifier.trackBounds(holder: BoundsHolder): Modifier = onGloballyPositioned { c ->
    val b = c.boundsInWindow()
    holder.rect = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
}

/** Press feedback shared by app cells and dock tiles: a quick 0.94 dip. */
@Composable
fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, tween(Gp5Motion.Fast), label = "press")
    return s
}

/** Dock tile with an app (Home and the Apps drawer): 36 dp icon over an 11 sp label. */
@Composable
fun DockSlot(
    packageName: String,
    label: String,
    load: IconLoader,
    onClick: (android.graphics.Rect?) -> Unit,
    onLongClick: (() -> Unit)? = null,
    phaseDeg: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val bounds = rememberBounds()
    val scale = pressScale(source)
    GpCard(
        modifier.fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .trackBounds(bounds)
            .combinedClickable(interactionSource = source, indication = null, onLongClick = onLongClick) { onClick(bounds.rect) },
        phaseDeg = phaseDeg,
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            AppIcon(packageName, 36.dp, load)
            Text(label, style = Gp5Type.Caption.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

/** Dock tile with a glyph instead of an app (Home, All apps). */
@Composable
fun DockGlyph(icon: Int, label: String, onClick: () -> Unit, phaseDeg: Float = 0f, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = Gp5Palette.Text) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    GpCard(
        modifier.fillMaxHeight().graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(interactionSource = source, indication = null, onClick = onClick),
        phaseDeg = phaseDeg,
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            Text(label, style = Gp5Type.Caption.copy(fontSize = 11.sp), maxLines = 1)
        }
    }
}

/** Empty dock slot: dashed outline, "+ Empty" (apps.html). */
@Composable
fun EmptyDockSlot(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) }
    Box(
        modifier.fillMaxHeight()
            .clip(RoundedCornerShape(Gp5Dimens.RadiusCard))
            .drawBehind {
                val r = Gp5Dimens.RadiusCard.toPx()
                drawRoundRect(Gp5Palette.LineStrong, cornerRadius = CornerRadius(r), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
            }
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = Gp5Palette.Text3, modifier = Modifier.size(20.dp))
            Text("Empty", style = Gp5Type.Caption.copy(color = Gp5Palette.Text3, fontSize = 11.sp))
        }
    }
}
