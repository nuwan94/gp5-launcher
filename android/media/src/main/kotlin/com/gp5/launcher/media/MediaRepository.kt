package com.gp5.launcher.media

import com.gp5.launcher.core.model.MediaState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Now playing + transport controls (DESIGN.md section 6.1). */
interface MediaRepository {
    val state: StateFlow<MediaState>
    fun playPause()
    fun next()
    fun previous()
}

/**
 * Placeholder until milestone M3, where a NotificationListenerService + MediaSessionManager
 * feed the active session (Spotify, YouTube Music, radio).
 */
@Singleton
class StubMediaRepository @Inject constructor() : MediaRepository {
    private val _state = MutableStateFlow(MediaState(title = "Midnight Drive", artist = "The Night Shift", source = "Spotify", durationMs = 222_000))
    override val state: StateFlow<MediaState> = _state.asStateFlow()
    override fun playPause() { _state.value = _state.value.copy(playing = !_state.value.playing) }
    override fun next() = Unit
    override fun previous() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    abstract fun media(impl: StubMediaRepository): MediaRepository
}
