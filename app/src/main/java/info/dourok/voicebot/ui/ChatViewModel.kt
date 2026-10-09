package info.dourok.voicebot.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import info.dourok.voicebot.ButtonEvents
import info.dourok.voicebot.data.SettingsRepository
import info.dourok.voicebot.data.model.DeviceInfo
import info.dourok.voicebot.data.model.TransportType
import info.dourok.voicebot.domain.voice.VoiceAssistant
import info.dourok.voicebot.protocol.MqttProtocol
import info.dourok.voicebot.protocol.Protocol
import info.dourok.voicebot.protocol.WebsocketProtocol
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin presentation layer: builds the network [Protocol] from the saved settings, starts the
 * [VoiceAssistant] runtime and exposes its state to the UI. All conversation logic lives in the
 * domain layer ([VoiceAssistant]).
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext context: Context,
    deviceInfo: DeviceInfo,
    settings: SettingsRepository,
    private val voiceAssistant: VoiceAssistant,
) : ViewModel() {

    private val protocol: Protocol = when (settings.transportType) {
        TransportType.MQTT -> MqttProtocol(context, settings.mqttConfig!!)
        TransportType.WebSockets -> WebsocketProtocol(
            deviceInfo,
            info.dourok.voicebot.data.Settings.wsUrl.ifBlank { settings.webSocketUrl.orEmpty() },
            info.dourok.voicebot.data.Settings.wsToken.ifBlank { "test-token" },
        )
    }

    val state = voiceAssistant.state
    val messages = voiceAssistant.messages
    val emotion = voiceAssistant.emotion

    init {
        voiceAssistant.start(protocol, viewModelScope)
        // R1 hardware button (KEYCODE_PHICOMM_OK = 275) -> wake / interrupt / stop.
        viewModelScope.launch { ButtonEvents.flow.collect { voiceAssistant.onButtonPress() } }
    }

    override fun onCleared() {
        voiceAssistant.dispose()
        super.onCleared()
    }
}
