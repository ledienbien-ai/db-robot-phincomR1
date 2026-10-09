package info.dourok.voicebot.domain.voice

/** Plays short UI feedback sounds (wake / stop chimes). */
interface SoundEffects {
    /** Chime played when the wake word is detected ("listening" cue). Returns playback duration (ms). */
    fun playWake(): Long

    /** Chime played when a session is stopped. Returns playback duration (ms). */
    fun playStop(): Long
}
