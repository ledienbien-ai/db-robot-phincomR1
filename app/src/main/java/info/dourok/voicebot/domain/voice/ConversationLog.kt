package info.dourok.voicebot.domain.voice

/** In-memory ring buffer of recent conversation lines, shown by the control panel's chat view. */
object ConversationLog {
    data class Entry(val sender: String, val text: String, val time: Long = System.currentTimeMillis())

    private const val MAX = 50
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(sender: String, text: String) {
        val last = entries.lastOrNull()
        if (last != null && last.sender == sender && last.text == text) return // dedup (server may echo stt)
        entries.addLast(Entry(sender, text))
        while (entries.size > MAX) entries.removeFirst()
    }

    @Synchronized
    fun recent(): List<Entry> = entries.toList()
}
