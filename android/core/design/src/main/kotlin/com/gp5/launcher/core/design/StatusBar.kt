package com.gp5.launcher.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TimeFmt = SimpleDateFormat("HH:mm", Locale.UK)
private val DateFmt = SimpleDateFormat("EEE d MMM", Locale.UK)

/** 40 dp status bar: clock and date on the left, system chips on the right (design/shell.css .status). */
@Composable
fun StatusBar(obdConnected: Boolean, outsideTempC: Int?, modifier: Modifier = Modifier) {
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L) // tick on the minute
        }
    }
    Row(
        modifier.fillMaxWidth().height(Gp5Dimens.Status),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Gp5Dimens.S2)) {
            Text(TimeFmt.format(now), style = Gp5Type.Title.copy(fontFamily = Gp5Num), color = Gp5Palette.Text)
            Text(DateFmt.format(now), style = Gp5Type.Label, fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gp5Dimens.S4)) {
            if (outsideTempC != null) Text("$outsideTempC°", style = Gp5Type.Label.copy(color = Gp5Palette.Text), fontSize = 14.sp)
            Chip("OBD", on = obdConnected)
        }
    }
}
