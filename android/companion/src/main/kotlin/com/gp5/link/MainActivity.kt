package com.gp5.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Three steps: notification access, Bluetooth permission, which car. Then it runs by itself. */
class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private val askBt = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    override fun onResume() { super.onResume(); tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE8A33D))) {
                Surface(Modifier.fillMaxSize()) { Screen(tick) }
            }
        }
    }

    private fun listenerOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.contains(ComponentName(this, NavListener::class.java).flattenToString())
    }

    private fun btOk(): Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun bonded(): List<Pair<String, String>> = if (!btOk()) emptyList() else
        getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
            .map { (it.name ?: it.address) to it.address }.sortedBy { it.first }

    @Composable
    private fun Screen(tick: Int) {
        var status by remember { mutableStateOf(Link.status) }
        var car by remember { mutableStateOf(Link.car(this)) }
        LaunchedEffect(Unit) { while (true) { status = Link.status; delay(1_000) } }
        val listener = remember(tick) { listenerOn() }
        val bt = remember(tick) { btOk() }
        if (listener && bt) LaunchedEffect(Unit) { Link.start(this@MainActivity) }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("GP5 Link", style = MaterialTheme.typography.headlineMedium)
            Text("Shows your phone's Google Maps or Waze turns, and the weather, on the car's screen.", style = MaterialTheme.typography.bodyMedium)
            Step("1. Read navigation notifications", listener) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            Step("2. Use Bluetooth", bt) {
                if (Build.VERSION.SDK_INT >= 31) askBt.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            Text("3. Your car", style = MaterialTheme.typography.titleMedium)
            val devices = remember(tick, bt) { bonded() }
            if (devices.isEmpty()) Text("Pair the phone with the car first.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(devices) { (name, address) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { Link.setCar(this@MainActivity, address); car = address }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = car == address, onClick = { Link.setCar(this@MainActivity, address); car = address })
                        Text(name)
                    }
                }
            }
            Text("Status: $status", style = MaterialTheme.typography.bodyLarge)
        }
    }

    @Composable
    private fun Step(label: String, done: Boolean, fix: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label + if (done) "  ✓" else "", style = MaterialTheme.typography.titleMedium)
            if (!done) Button(onClick = fix) { Text("Allow") }
        }
    }
}
