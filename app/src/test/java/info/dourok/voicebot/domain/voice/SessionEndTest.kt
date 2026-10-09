package info.dourok.voicebot.domain.voice

import info.dourok.voicebot.domain.voice.model.VoiceState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression cover for the "loa kẹt ở Đang trả lời" fault, measured on the live R1 on 2026-09-24.
 *
 * What was measured, so the numbers below are the device's and not invented:
 *  - 23/09 15:29:25 the household paused a song from the panel. The session later died by EPIPE,
 *    which the client's `onFailure` handled without ever emitting AudioState.CLOSED -- so
 *    MediaSessionState was never cleared and stayed PAUSED across every session that followed.
 *  - 24/09 15:14:33 "tặm biệt" was typed into the panel chat; 15:14:37 the server sent `tts stop`
 *    and closed the connection.
 *  - The client swallowed that `tts stop` because media was PAUSED -- 24 hours earlier -- so the
 *    voice state never left SPEAKING. Read back at 20:34, /api/state still said
 *    voice_awake=true, voice_state=SPEAKING.
 */
class SessionEndTest {

    @Test
    fun `a tts stop with no music in the picture ends the reply`() {
        assertTrue(SessionEnd.ttsStopEndsReply(VoiceState.SPEAKING, mediaPaused = false, msSincePause = 0))
    }

    @Test
    fun `the flush that follows a pause we just made is not the end of a reply`() {
        // The panel's pause flushes the device's audio with a tts-stop in the same round trip.
        // Re-opening the mic here would leave the robot listening for as long as the song stays
        // paused -- the fault this guard was written for, and it stays guarded.
        assertFalse(SessionEnd.ttsStopEndsReply(VoiceState.SPEAKING, mediaPaused = true, msSincePause = 400))
    }

    @Test
    fun `a pause from yesterday does not swallow today's goodbye`() {
        // THE BUG. 24 hours between the pause and the `tts stop` that ended the goodbye.
        val aDay = 24 * 60 * 60 * 1000L
        assertTrue(SessionEnd.ttsStopEndsReply(VoiceState.SPEAKING, mediaPaused = true, msSincePause = aDay))
    }

    @Test
    fun `a reply spoken over a paused song still ends`() {
        // Pause the music, then ask a question: the answer's `tts stop` is seconds of LLM and TTS
        // after the pause, so it is a real end of reply even though the song is still paused.
        assertTrue(SessionEnd.ttsStopEndsReply(VoiceState.SPEAKING, mediaPaused = true, msSincePause = 8_000))
    }

    @Test
    fun `the window's own edges`() {
        val w = SessionEnd.PAUSE_FLUSH_WINDOW_MS
        assertFalse(SessionEnd.ttsStopEndsReply(VoiceState.SPEAKING, mediaPaused = true, msSincePause = w - 1))
        assertTrue(SessionEnd.ttsStopEndsReply(VoiceState.SPEAKING, mediaPaused = true, msSincePause = w))
    }

    @Test
    fun `a tts stop arriving when we are not speaking ends nothing`() {
        assertFalse(SessionEnd.ttsStopEndsReply(VoiceState.LISTENING, mediaPaused = false, msSincePause = 0))
        assertFalse(SessionEnd.ttsStopEndsReply(VoiceState.IDLE, mediaPaused = false, msSincePause = 0))
        // ...and the pause window never promotes a non-speaking state into an end of reply.
        assertFalse(SessionEnd.ttsStopEndsReply(VoiceState.LISTENING, mediaPaused = true, msSincePause = 99_000))
    }

    @Test
    fun `a closed channel ends a session only while one is running`() {
        assertTrue(SessionEnd.closedChannelEndsSession(awake = true))
        assertFalse(SessionEnd.closedChannelEndsSession(awake = false))
    }
}
