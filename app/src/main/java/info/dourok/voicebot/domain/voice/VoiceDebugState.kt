package info.dourok.voicebot.domain.voice

/**
 * Live snapshot of the few [VoiceAssistant] flags that decide whether a triggered action (notably
 * the News bulletin) actually runs, surfaced through the control panel's /api/state.
 *
 * Exists because logcat on the R1 is effectively unusable for diagnosis: the 4-mic driver
 * (UNI_4MIC) floods the buffer continuously and evicts app log lines within seconds, so "no log
 * line appeared" proves nothing. A News bulletin that silently no-ops was diagnosed twice the hard
 * way for exactly this reason -- these flags make the guard's decision inspectable over HTTP.
 *
 * Plain object rather than DI: ControlServer has no VoiceAssistant reference, and this mirrors the
 * existing MediaSessionState / MicTest / TextCommands pattern.
 */
object VoiceDebugState {
    @Volatile var awake: Boolean = false
    @Volatile var voiceState: String = "IDLE"
}
