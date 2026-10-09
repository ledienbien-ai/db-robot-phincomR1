package info.dourok.voicebot.domain.voice

/**
 * The output format the server announced in its hello, surfaced through the control panel so a
 * mismatch with this device's own setting is visible instead of being heard.
 *
 * The two ends cannot negotiate: the server encodes from its own config and this client decodes
 * from [info.dourok.voicebot.data.Settings], so a disagreement is not a loss of quality but audio
 * framed wrongly -- it comes out as noise. The panel offers Mono/Stereo and 24/48 kHz as two taps,
 * which makes reaching that state easy and, until this existed, silent.
 *
 * -1 means no session has completed a hello yet, which is a third answer and not "mono": a fresh
 * boot must not accuse the server of disagreeing before it has said anything.
 *
 * Plain object rather than DI, for [VoiceDebugState]'s reason: ControlServer holds no reference to
 * the protocol, and this mirrors the MediaSessionState / MicTest / TextCommands pattern.
 */
object ServerAudioParams {
    @Volatile var sampleRate: Int = -1
    @Volatile var channels: Int = -1
}
