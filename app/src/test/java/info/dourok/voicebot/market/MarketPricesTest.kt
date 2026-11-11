package info.dourok.voicebot.market

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Parsers fed the real responses of both sources, saved on 21/10/2026 (a fuel adjustment day). */
class MarketPricesTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/market/$name")!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun `gold prices are read as million dong per luong and per chi`() {
        val text = MarketPrices.parseGold(fixture("pnj-gold.json"))
        assertContains(text, "cập nhật 21/10/2026 09:36:37")
        assertContains(text, "Vàng miếng SJC 999.9: mua vào 139,3 triệu (13,93 triệu), bán ra 142,3 triệu (14,23 triệu).")
        assertContains(text, "Nhẫn Trơn PNJ 999.9: mua vào 138,4 triệu")
    }

    @Test
    fun `a gold line PNJ only buys has no sell price`() {
        val text = MarketPrices.parseGold(fixture("pnj-gold.json"))
        assertContains(text, "Vàng nguyên liệu mua ngoài 99.99: mua vào 130,75 triệu (13,08 triệu).")
    }

    @Test
    fun `an empty gold list is an error, not an empty answer`() {
        assertFailsWith<org.json.JSONException> { MarketPrices.parseGold("""{"data":[]}""") }
    }

    @Test
    fun `fuel prices list both Petrolimex zones and the last adjustment`() {
        val text = MarketPrices.parseFuel(fixture("giaxanghomnay.html"))
        assertContains(text, "cập nhật 00:00 ngày 21/10/2026")
        assertContains(text, "- Xăng E5 RON 92: vùng 1 26,670, vùng 2 27,200.")
        assertContains(text, "- Xăng E10 RON 95 Mức 3: vùng 1 26,720, vùng 2 27,250 (kỳ điều chỉnh gần nhất giảm 1,530).")
        assertContains(text, "PVOIL: Xăng E10 RON 95 Mức 3 26,720;")
        assertFalse(text.contains("Xăng/dầu:"), "header row leaked into the answer")
    }

    @Test
    fun `a page without the Petrolimex table is an error`() {
        assertFailsWith<java.io.IOException> { MarketPrices.parseFuel("<html>Just a moment...</html>") }
    }

    @Test
    fun `price wording drops trailing zeros`() {
        assertEquals("142,3 triệu", MarketPrices.perLuong(14230))
        assertEquals("140 triệu", MarketPrices.perLuong(14000))
        assertEquals("14,23 triệu", MarketPrices.perChi(14230))
    }
}
