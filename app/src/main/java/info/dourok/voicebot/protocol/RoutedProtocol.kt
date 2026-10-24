package info.dourok.voicebot.protocol

import info.dourok.voicebot.data.Settings
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The voice server chosen on the panel (Settings.voiceServer): the xiaozhi server from the OTA url,
 * or Home Assistant Assist. The choice is read at every new session (openAudioChannel), so
 * switching on the panel needs no restart; everything else goes to whichever one is in use.
 */
class RoutedProtocol(private val xiaozhi: Protocol, private val ha: Protocol) : Protocol() {

    @Volatile private var active: Protocol = pick()

    private fun pick(): Protocol = if (Settings.voiceServer == "ha") ha else xiaozhi

    init {
        for (p in listOf(xiaozhi, ha)) {
            scope.launch { p.incomingJsonFlow.collect { if (p === active) incomingJsonFlow.emit(it) } }
            scope.launch { p.incomingAudioFlow.collect { if (p === active) incomingAudioFlow.emit(it) } }
            scope.launch { p.audioChannelStateFlow.collect { if (p === active) audioChannelStateFlow.emit(it) } }
            scope.launch { p.networkErrorFlow.collect { if (p === active) networkErrorFlow.emit(it) } }
        }
    }

    override suspend fun start() {
        xiaozhi.start()
        ha.start()
    }

    override suspend fun openAudioChannel(): Boolean {
        val next = pick()
        if (next !== active) {
            active.closeAudioChannel()
            active = next
        }
        return active.openAudioChannel()
    }

    override suspend fun sendAudio(data: ByteArray) = active.sendAudio(data)
    override fun closeAudioChannel() = active.closeAudioChannel()
    override fun isAudioChannelOpened(): Boolean = active.isAudioChannelOpened()
    override suspend fun sendText(text: String): Boolean = active.sendText(text)

    override fun dispose() {
        xiaozhi.dispose()
        ha.dispose()
        scope.cancel()
    }
}
