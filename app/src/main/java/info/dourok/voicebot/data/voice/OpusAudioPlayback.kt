package info.dourok.voicebot.data.voice

import info.dourok.voicebot.OpusDecoder
import info.dourok.voicebot.OpusStreamPlayer
import info.dourok.voicebot.domain.voice.AudioPlayback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Playback backed by [OpusDecoder] + [OpusStreamPlayer] (24 kHz mono). */
class OpusAudioPlayback(
    sampleRate: Int = 24000,
    channels: Int = 1,
    frameSizeMs: Int = 60,
) : AudioPlayback {

    private val player = OpusStreamPlayer(sampleRate, channels, frameSizeMs)
    private val decoder = OpusDecoder(sampleRate, channels, frameSizeMs)

    override fun start(opusFrames: Flow<ByteArray>, onPlaying: () -> Unit) {
        player.start(opusFrames.map { frame ->
            onPlaying()
            decoder.decode(frame)
        })
    }

    override suspend fun awaitCompletion() = player.waitForPlaybackCompletion()

    override fun flush() = player.flush()

    override fun applyAudioSettings() = player.applyAudioSettings()

    override fun eqInfo() = player.eqInfo()

    override fun release() {
        player.release()
        decoder.release()
    }
}
