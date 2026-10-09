package info.dourok.voicebot.news

import java.util.Calendar
import java.util.TimeZone

/**
 * The two judgements behind the News bulletin's daily alarm.
 *
 * Pure and tested for the reason [info.dourok.voicebot.domain.voice.SessionEnd] is: an AlarmManager
 * alarm is an ABSOLUTE wall-clock instant, and the R1 boots with a wall clock that is years wrong
 * (measured: 2018-01-20 23:00, the ROM's own default) until NTP corrects it seconds later. Nothing
 * in the type system catches either judgement being wrong -- the speaker simply starts reading the
 * news at a moment nobody asked for, which is what the household reported after a power cut.
 */
object NewsSchedule {

    /**
     * Before this instant the device clock has not been set yet -- it is still the ROM's built-in
     * default. Any date this app could legitimately run at is after it.
     *
     * 2026-01-01T00:00:00Z. A floor, not a calibration: it only has to sit above every plausible
     * factory default and below every real usage, so it needs no maintenance for years.
     */
    const val CLOCK_FLOOR_MILLIS = 1_767_225_600_000L

    /** How far from the scheduled minute an alarm may fire and still be that alarm. */
    const val FIRE_TOLERANCE_MINUTES = 30

    /** Has the wall clock been set yet, or is this still the ROM's default? */
    fun clockIsTrustworthy(nowMillis: Long): Boolean = nowMillis >= CLOCK_FLOOR_MILLIS

    /**
     * The bulletin alarm just fired. Is it really time to read the news?
     *
     * An alarm scheduled against the pre-NTP clock lands years in the past the moment the clock is
     * corrected, and AlarmManager delivers a past RTC alarm IMMEDIATELY -- so "the alarm fired" is
     * not on its own evidence that the scheduled minute has arrived. Measured on the R1: the alarm
     * was set for 2018-01-21 09:15 at boot and fired at 2026-09-23 15:08:53, six hours from the
     * 09:15 the household had asked for.
     */
    fun shouldRun(
        nowMillis: Long,
        hour: Int,
        minute: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        if (!clockIsTrustworthy(nowMillis)) return false
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return minutesApart(nowMinutes, hour * 60 + minute) <= FIRE_TOLERANCE_MINUTES
    }

    /**
     * Distance between two minutes-of-day, the short way round the clock face.
     *
     * A plain subtraction makes 23:58 and 00:05 look 23h53m apart, so an alarm a few minutes early
     * across midnight would be thrown away as a stray.
     */
    fun minutesApart(a: Int, b: Int): Int {
        val d = Math.abs(a - b)
        return minOf(d, 24 * 60 - d)
    }
}
