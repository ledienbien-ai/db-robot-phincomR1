package info.dourok.voicebot.data.voice

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.AudioCapture
import info.dourok.voicebot.domain.voice.AudioPlayback
import info.dourok.voicebot.domain.voice.LedIndicator
import info.dourok.voicebot.domain.voice.SoundEffects
import info.dourok.voicebot.domain.voice.WakeWordDetector
import javax.inject.Singleton

/** Binds the voice-runtime ports (domain) to their concrete device implementations (data). */
@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {

    @Provides
    @Singleton
    fun provideWakeWordDetector(@ApplicationContext context: Context): WakeWordDetector =
        when (Settings.wakeEngine) {
            "nabu" -> MicroWakeWordDetector(context)
            "mai_oi" -> MaiOiWakeWordDetector(context)
            else -> SnowboyWakeWordDetector(context)
        }

    @Provides
    @Singleton
    fun provideAudioCapture(): AudioCapture = RecorderAudioCapture()

    @Provides
    @Singleton
    fun provideAudioPlayback(): AudioPlayback = OpusAudioPlayback(Settings.playbackSampleRate, Settings.playbackChannels)

    @Provides
    @Singleton
    fun provideSoundEffects(@ApplicationContext context: Context): SoundEffects =
        AudioTrackSoundEffects(context)

    @Provides
    @Singleton
    fun provideLedIndicator(): LedIndicator = MsgCenterLedIndicator()
}
