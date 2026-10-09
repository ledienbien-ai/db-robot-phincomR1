package info.dourok.voicebot.domain.voice.model

/** A single line of the conversation transcript shown on screen. */
data class ChatMessage(
    val sender: String = "",
    val text: String = "",
)
