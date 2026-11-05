package com.gp5.launcher

import com.gp5.launcher.core.data.WelcomeAudio
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {
    @Binds abstract fun welcomeAudio(sound: WelcomeSound): WelcomeAudio
}
