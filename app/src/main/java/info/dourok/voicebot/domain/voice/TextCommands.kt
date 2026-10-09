package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.MutableSharedFlow

/** Typed queries from the control-panel chat, consumed by [VoiceAssistant] and sent to the server. */
object TextCommands {
    val flow = MutableSharedFlow<String>(extraBufferCapacity = 8)
}
