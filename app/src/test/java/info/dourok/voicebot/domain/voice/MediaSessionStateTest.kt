package info.dourok.voicebot.domain.voice

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pause clock that [SessionEnd.ttsStopEndsReply] reads. It is the half of the 2026-09-24 fault
 * that lives in state rather than in a judgement: the runtime could always see THAT a song was
 * paused and never WHEN, so a pause could not be told from a leftover.
 */
class MediaSessionStateTest {

    @BeforeTest fun setUp() = MediaSessionState.clear()
    @AfterTest fun tearDown() = MediaSessionState.clear()

    private fun np(state: MediaPlaybackState) = MediaNowPlaying(state = state, videoId = "v1")

    @Test
    fun `nothing paused means no pause to be inside the window of`() {
        assertEquals(Long.MAX_VALUE, MediaSessionState.msSincePause(nowMs = 5_000))
        MediaSessionState.updateNowPlaying(np(MediaPlaybackState.PLAYING), nowMs = 1_000)
        assertEquals(Long.MAX_VALUE, MediaSessionState.msSincePause(nowMs = 5_000))
    }

    @Test
    fun `the clock starts when the session enters paused`() {
        MediaSessionState.updateNowPlaying(np(MediaPlaybackState.PLAYING), nowMs = 1_000)
        MediaSessionState.updateNowPlaying(np(MediaPlaybackState.PAUSED), nowMs = 2_000)
        assertEquals(500, MediaSessionState.msSincePause(nowMs = 2_500))
    }

    @Test
    fun `a paused session re-pushed by the server does not look freshly paused`() {
        // play_youtube pushes position updates while paused. Restarting the clock on each of them
        // would make a pause permanently "just now" -- the guard would never lapse.
        MediaSessionState.updateNowPlaying(np(MediaPlaybackState.PAUSED), nowMs = 1_000)
        MediaSessionState.updateNowPlaying(np(MediaPlaybackState.PAUSED), nowMs = 9_000)
        assertEquals(19_000, MediaSessionState.msSincePause(nowMs = 20_000))
    }

    @Test
    fun `resuming forgets the pause`() {
        MediaSessionState.updateNowPlaying(np(MediaPlaybackState.PAUSED), nowMs = 1_000)
        MediaSessionState.updateNowPlaying(np(MediaPlaybackState.PLAYING), nowMs = 2_000)
        assertEquals(Long.MAX_VALUE, MediaSessionState.msSincePause(nowMs = 2_100))
    }

    @Test
    fun `ending the session drops the pause with everything else`() {
        // The leak: this clear never ran, because a channel that died by EPIPE emitted no CLOSED.
        MediaSessionState.updateNowPlaying(np(MediaPlaybackState.PAUSED), nowMs = 1_000)
        MediaSessionState.clear()
        assertEquals(Long.MAX_VALUE, MediaSessionState.msSincePause(nowMs = 2_000))
        assertEquals(MediaPlaybackState.IDLE, MediaSessionState.nowPlaying.value.state)
    }

    @Test
    fun `a day-old pause is outside the flush window and today's goodbye survives it`() {
        // The two halves together, on the measured numbers.
        MediaSessionState.updateNowPlaying(np(MediaPlaybackState.PAUSED), nowMs = 0)
        val aDayLater = 24 * 60 * 60 * 1000L
        assertTrue(MediaSessionState.msSincePause(nowMs = aDayLater) >= SessionEnd.PAUSE_FLUSH_WINDOW_MS)
        assertTrue(
            SessionEnd.ttsStopEndsReply(
                voiceState = info.dourok.voicebot.domain.voice.model.VoiceState.SPEAKING,
                mediaPaused = true,
                msSincePause = MediaSessionState.msSincePause(nowMs = aDayLater),
            )
        )
    }
}
