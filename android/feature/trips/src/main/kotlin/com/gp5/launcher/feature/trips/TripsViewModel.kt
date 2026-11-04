package com.gp5.launcher.feature.trips

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gp5.launcher.core.data.SettingsRepository
import com.gp5.launcher.core.model.LauncherSettings
import com.gp5.launcher.core.model.VehicleState
import com.gp5.launcher.obd.VehicleStateRepository
import com.gp5.launcher.trips.Calibration
import com.gp5.launcher.trips.FillUp
import com.gp5.launcher.trips.Trip
import com.gp5.launcher.trips.TripSample
import com.gp5.launcher.trips.TripsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val repo: TripsRepository,
    private val settingsRepo: SettingsRepository,
    vehicle: VehicleStateRepository,
) : ViewModel() {
    val trips: StateFlow<List<Trip>> = repo.trips.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val fills: StateFlow<List<FillUp>> = repo.fillUps.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val calibration: StateFlow<Calibration?> = repo.calibration.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val live = repo.live
    val car: StateFlow<VehicleState> = vehicle.state
    val settings: StateFlow<LauncherSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())

    private val _selected = MutableStateFlow<Long?>(null)
    /** The trip in the detail pane: the one tapped, else the newest. */
    val selected: StateFlow<Trip?> = combine(trips, _selected) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val samples: StateFlow<List<TripSample>> = selected.flatMapLatest { t -> if (t == null) flowOf(emptyList()) else repo.samples(t.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun select(id: Long) { _selected.value = id }

    fun star(t: Trip) = viewModelScope.launch { repo.setKept(t, !t.kept) }

    private val _undo = MutableStateFlow<Pair<Trip, List<TripSample>>?>(null)
    val undo: StateFlow<Pair<Trip, List<TripSample>>?> = _undo.asStateFlow()
    private var undoJob: Job? = null

    fun delete(t: Trip) = viewModelScope.launch {
        if (t.open) return@launch
        _undo.value = repo.delete(t)
        undoJob?.cancel()
        undoJob = viewModelScope.launch { delay(4_000); _undo.value = null }
    }

    fun undoDelete() = viewModelScope.launch {
        val saved = _undo.value ?: return@launch
        _undo.value = null
        undoJob?.cancel()
        repo.restore(saved)
        _selected.value = saved.first.id
    }

    fun addFillUp(odometer: Double, litres: Double, price: Double, full: Boolean) = viewModelScope.launch {
        repo.addFillUp(FillUp(timeMs = System.currentTimeMillis(), odometerKm = odometer, litres = litres, pricePerL = price, full = full))
        // a fill-up reading ahead of the stored odometer moves it forward
        if (odometer > settings.value.odometerKm) settingsRepo.setOdometer(odometer)
    }

    fun deleteFillUp(f: FillUp) = viewModelScope.launch { repo.deleteFillUp(f.id) }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var messageJob: Job? = null

    fun say(text: String) {
        _message.value = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch { delay(3_000); _message.value = null }
    }

    fun export(folder: Uri) = viewModelScope.launch {
        runCatching { repo.exportCsv(folder) }
            .onSuccess { say("Saved ${it.joinToString(" and ")}") }
            .onFailure { say("Export failed: ${it.message}") }
    }
}
