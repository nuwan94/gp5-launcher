package com.gp5.launcher.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gp5.launcher.core.model.DriveMode

private val CardShape = RoundedCornerShape(Gp5Dimens.RadiusCard)

/** Surface card with the gold trim. [phaseDeg] staggers the travelling highlight. */
@Composable
fun GpCard(modifier: Modifier = Modifier, phaseDeg: Float = 0f, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .clip(CardShape)
            .background(Gp5Palette.Surface1)
            .goldTrim(CardShape, phaseDeg),
        content = content,
    )
}

/** Drive-mode pill (READY / EV / ENGINE / REGEN), as on Home and Vehicle. */
@Composable
fun ModePill(mode: DriveMode, modifier: Modifier = Modifier) {
    val accent = LocalGp5Look.current.accent
    val (label, fg) = when (mode) {
        DriveMode.Ev -> "EV" to Gp5Palette.Ev
        DriveMode.Engine, DriveMode.Assist -> (if (mode == DriveMode.Assist) "ASSIST" else "ENGINE") to accent
        DriveMode.Regen, DriveMode.Charging -> (if (mode == DriveMode.Regen) "REGEN" else "CHARGING") to Gp5Palette.Regen
        DriveMode.Ready -> "READY" to Gp5Palette.Text2
    }
    val bg = if (mode == DriveMode.Ready) Gp5Palette.Surface2 else fg.copy(alpha = 0.14f)
    Row(
        modifier.height(28.dp).clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Text(label, color = fg, fontSize = 13.sp, letterSpacing = 0.6.sp, style = Gp5Type.Label.copy(color = fg))
    }
}

/** Status chip (OBD connected / off). */
@Composable
fun Chip(text: String, on: Boolean, modifier: Modifier = Modifier) {
    val fg = if (on) Gp5Palette.Ev else Gp5Palette.Text3
    val bg = if (on) Gp5Palette.Ev.copy(alpha = 0.14f) else Gp5Palette.Surface2
    Row(
        modifier.height(22.dp).clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(text, style = Gp5Type.Caption.copy(color = fg), fontSize = 12.sp)
    }
}

/** Screen not built yet: names the milestone and the HTML prototype that specifies it. */
@Composable
fun PlaceholderScreen(title: String, note: String, modifier: Modifier = Modifier) {
    GpCard(modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = Gp5Type.Display, color = Gp5Palette.Text)
            Text(note, style = Gp5Type.Label, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

