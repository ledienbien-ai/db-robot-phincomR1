package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Media Player tab commands from the control panel, consumed by [VoiceAssistant] and forwarded to
 * the server. Mirrors [TextCommands].
 *
 * ControlServer must NOT inject [VoiceAssistant] directly: it is not @Singleton and is not provided
 * by any DI module, so Hilt hands each injection site its own instance. ControlServer would get a
 * second, never-started one whose `protocol` is uninitialized -- every command silently dropped.
 * Routing through a global object means the command always reaches the ONE instance that
 * ChatViewModel actually started.
 */
object MediaCommands {
    sealed class Command {
        /** [itemsJson]: JSON array of the panel's WHOLE displayed list; [startIndex]: tapped song. */
        data class Play(val itemsJson: String, val startIndex: Int) : Command()
        object Next : Command()
        data class Seek(val positionS: Int) : Command()
        object Pause : Command()
        object Resume : Command()
        object Stop : Command()
    }

    val flow = MutableSharedFlow<Command>(extraBufferCapacity = 16)
}
