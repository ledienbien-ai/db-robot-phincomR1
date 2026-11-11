package info.dourok.voicebot.lunar

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LunarCalendarTest {

    private fun lunar(d: Int, m: Int, y: Int) = LunarCalendar.solarToLunar(d, m, y).let {
        listOf(it.day, it.month, it.year, if (it.leap) 1 else 0)
    }

    @Test
    fun `Tet falls on the published dates`() {
        assertEquals(listOf(1, 1, 2024, 0), lunar(10, 2, 2024))
        assertEquals(listOf(1, 1, 2025, 0), lunar(29, 1, 2025))
        assertEquals(listOf(1, 1, 2026, 0), lunar(17, 2, 2026))
        // Tết Bính Ngọ has no "30 Tết": the twelfth month of 2025 is 29 days long.
        assertEquals(listOf(29, 12, 2025, 0), lunar(16, 2, 2026))
    }

    @Test
    fun `Vietnam keeps its own Tet in 1985, a day after China's`() {
        assertEquals(listOf(1, 1, 1985, 0), lunar(21, 1, 1985))
    }

    @Test
    fun `2025 repeats the sixth month`() {
        assertEquals(6, LunarCalendar.leapMonth(2025))
        assertEquals(listOf(1, 6, 2025, 1), lunar(25, 7, 2025))
        assertContentEquals(intArrayOf(25, 7, 2025), LunarCalendar.lunarToSolar(1, 6, 2025, true))
        assertEquals(0, LunarCalendar.leapMonth(2026))
        assertNull(LunarCalendar.lunarToSolar(1, 6, 2026, true))
    }

    @Test
    fun `lunar to solar round-trips for every day of 2026`() {
        val start = LunarCalendar.jdFromDate(1, 1, 2026)
        for (jd in start until start + 365) {
            val s = LunarCalendar.jdToDate(jd)
            val l = LunarCalendar.solarToLunar(s[0], s[1], s[2])
            assertContentEquals(s, LunarCalendar.lunarToSolar(l.day, l.month, l.year, l.leap))
        }
    }

    @Test
    fun `can chi of days and years`() {
        assertEquals("Mậu Ngọ", LunarCalendar.dayCanChi(LunarCalendar.jdFromDate(1, 1, 2000)))
        assertEquals("Bính Ngọ", LunarCalendar.yearCanChi(2026))
        assertEquals("Kỷ Mão", LunarCalendar.yearCanChi(1999))
        assertEquals("Thành Đầu Thổ", LunarCalendar.napAm(1999))
        assertEquals("Thiên Hà Thủy", LunarCalendar.napAm(2026))
    }

    @Test
    fun `answers for a birth date and today`() {
        val today = intArrayOf(21, 10, 2026)
        val text = LunarAnswers.horoscopeFacts(LunarAnswers.parseDate("31/07/1999", today), today)
        assertContains(text, "Tuổi Kỷ Mão (con mèo), mệnh Thành Đầu Thổ, năm nay 28 tuổi mụ.")
        assertContains(text, "Cung hoàng đạo: Sư Tử.")
        assertEquals("Sư Tử", LunarAnswers.zodiac(23, 7))
        assertEquals("Cự Giải", LunarAnswers.zodiac(22, 7))
        assertEquals("Ma Kết", LunarAnswers.zodiac(5, 1))
    }

    @Test
    fun `branch relations`() {
        assertTrue(LunarAnswers.relation(3, 10).startsWith("lục hợp")) // Mão - Tuất
        assertTrue(LunarAnswers.relation(3, 7).startsWith("tam hợp")) // Mão - Mùi
        assertTrue(LunarAnswers.relation(3, 9).startsWith("lục xung")) // Mão - Dậu
        assertTrue(LunarAnswers.relation(3, 4).startsWith("lục hại")) // Mão - Thìn
    }

    @Test
    fun `days until a lunar date`() {
        val today = intArrayOf(21, 10, 2026)
        assertContains(LunarAnswers.solarDate(1, 1, 2027, false, today), "dương lịch (còn")
        assertFailsWith<IllegalArgumentException> { LunarAnswers.parseDate("31/02/2026", today) }
    }
}
