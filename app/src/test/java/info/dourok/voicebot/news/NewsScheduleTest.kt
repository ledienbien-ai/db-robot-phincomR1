package info.dourok.voicebot.news

import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression cover for "rút phích cắm, gắn lại thì loa tự đọc bản tin".
 *
 * Taken from the R1's own log, which had recorded the fault before anybody reported it:
 *   seq 1  2018-01-20 23:00:00  Hẹn giờ bản tin: 21/01 09:15
 *   seq 2  2026-09-23 15:08:53  Tới giờ hẹn -> yêu cầu đọc bản tin
 * The device boots with the ROM's 2018 clock, BootReceiver schedules the alarm against it, NTP
 * then corrects the clock, and AlarmManager delivers the now eight-years-late RTC alarm at once.
 */
class NewsScheduleTest {

    private val vn = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(vn).apply {
            clear(); set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    @Test
    fun `the ROM's own clock is not a clock`() {
        assertFalse(NewsSchedule.clockIsTrustworthy(at(2018, 1, 20, 23, 0)))
        assertTrue(NewsSchedule.clockIsTrustworthy(at(2026, 9, 23, 15, 8)))
    }

    @Test
    fun `the alarm that fired at boot is refused`() {
        // THE BUG, on the measured numbers: scheduled 09:15, delivered 15:08:53.
        assertFalse(NewsSchedule.shouldRun(at(2026, 9, 23, 15, 8), hour = 9, minute = 15, timeZone = vn))
    }

    @Test
    fun `the real daily alarm runs`() {
        assertTrue(NewsSchedule.shouldRun(at(2026, 9, 25, 9, 15), hour = 9, minute = 15, timeZone = vn))
    }

    @Test
    fun `a late alarm inside the tolerance still runs`() {
        // Doze or a busy boot can push an exact alarm a little; that is still the daily bulletin.
        assertTrue(NewsSchedule.shouldRun(at(2026, 9, 25, 9, 40), hour = 9, minute = 15, timeZone = vn))
        assertFalse(NewsSchedule.shouldRun(at(2026, 9, 25, 9, 50), hour = 9, minute = 15, timeZone = vn))
    }

    @Test
    fun `an alarm delivered before the clock is set is refused whatever the hour says`() {
        // 09:15 on the ROM's 2018 date looks exactly like the right minute, and is not.
        assertFalse(NewsSchedule.shouldRun(at(2018, 1, 21, 9, 15), hour = 9, minute = 15, timeZone = vn))
    }

    @Test
    fun `midnight is not the far side of the clock face`() {
        assertEquals(7, NewsSchedule.minutesApart(23 * 60 + 58, 5))
        assertEquals(0, NewsSchedule.minutesApart(0, 0))
        assertEquals(720, NewsSchedule.minutesApart(0, 12 * 60))
        // A bulletin set for 00:05, alarm a few minutes early -- still the bulletin.
        assertTrue(NewsSchedule.shouldRun(at(2026, 9, 25, 23, 58), hour = 0, minute = 5, timeZone = vn))
    }
}
