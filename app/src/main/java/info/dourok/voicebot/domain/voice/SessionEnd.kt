package info.dourok.voicebot.domain.voice

import info.dourok.voicebot.domain.voice.model.VoiceState

/**
 * The two judgements that decide when a spoken session is over.
 *
 * Named, pure and tested for the reason the rest of this runtime is not: nothing in the type
 * system catches either of them being wrong. Both answers are a boolean the runtime acts on in
 * silence -- a wrong one does not throw, does not log, and does not fail a build. It leaves the
 * speaker sitting in SPEAKING with the panel reading "Đang trả lời…" hours after the user said
 * goodbye, which is exactly the fault measured on 2026-09-24.
 */
object SessionEnd {

    /**
     * How long after the media session went PAUSED a `tts stop` may still be that pause's own
     * audio flush rather than the end of a spoken reply.
     *
     * The flush follows its pause immediately (same server turn, one round trip over the LAN); a
     * real reply that happens to be spoken while a song sits paused is seconds of LLM and TTS
     * away. Three seconds separates the two with room to spare in both directions.
     */
    const val PAUSE_FLUSH_WINDOW_MS = 3_000L

    /**
     * Does this `tts stop` frame end a spoken reply (so the mic should re-open, or the session
     * wind down), or is it the flush that a media pause drags behind it?
     *
     * @param voiceState what the runtime currently believes it is doing.
     * @param mediaPaused whether the server's last media push said PAUSED.
     * @param msSincePause how long ago the media session entered PAUSED. Only meaningful when
     *   [mediaPaused]; pass anything when it is false.
     */
    fun ttsStopEndsReply(
        voiceState: VoiceState,
        mediaPaused: Boolean,
        msSincePause: Long,
    ): Boolean {
        if (voiceState != VoiceState.SPEAKING) return false
        // The guard is about a pause that JUST happened, not about a song that happens to be
        // paused. Written without the window it read "media is paused" as "this frame is a flush",
        // and a pause left over from the previous day then swallowed every `tts stop` that
        // followed it -- including the one that ends a goodbye.
        if (mediaPaused && msSincePause < PAUSE_FLUSH_WINDOW_MS) return false
        return true
    }

    /**
     * The channel to the server just closed. Does the voice runtime have a session to tear down?
     *
     * Only when it still believes it is awake -- a close arriving at an already-idle runtime is
     * the ordinary end of a session that has already been cleaned up, and answering true there
     * would sound the end-of-session chime a second time.
     */
    fun closedChannelEndsSession(awake: Boolean): Boolean = awake
}
