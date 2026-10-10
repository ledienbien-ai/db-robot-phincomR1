package info.dourok.voicebot.domain.voice

import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.media.LocalMusicPlayer
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * "Stop listening for a while", as the owner asks for it from the control panel.
 *
 * The speaker has one microphone and no echo cancellation, so music coming out of its own driver
 * can be taken for the wake word -- after which the assistant "hears" lyrics and answers them. The
 * owner's remedy is to switch the wake word off while they listen, and to call the assistant by
 * hand when they do want it: [paused] is that switch, and [VoiceCommands] carries the call.
 *
 * Two ways in stay open while the wake word is off, because both are deliberate: the button on top
 * of the speaker, and the panel's "Gọi loa".
 *
 * [paused] is not saved. A speaker that had been told to stop listening and then lost power would
 * otherwise come back deaf with nothing on it to say why.
 */
object VoiceGate {
    @Volatile var paused = false

    /** May the wake word start (or interrupt) a conversation right now? */
    val wakeWordAllowed: Boolean
        get() = !paused && !(Settings.pauseOnMusic && LocalMusicPlayer.isActive)
}

/** Requests from the control panel to [VoiceAssistant], which ControlServer cannot call directly. */
object VoiceCommands {
    enum class Command { WAKE, SLEEP }

    val flow = MutableSharedFlow<Command>(extraBufferCapacity = 4)
}
