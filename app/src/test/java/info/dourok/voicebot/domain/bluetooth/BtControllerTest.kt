package info.dourok.voicebot.domain.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three decisions in Bluetooth audio out that nobody can check by looking at the panel.
 *
 * Each of them is wrong in a way that only shows up a minute later: a speaker that reconnects
 * right after being told to stop, one that never comes back after a power cut, or a pairing window
 * that stays open to the whole house. None of that is caught by the type system, and all of it
 * needs a device and a wait to see.
 */
class BtControllerTest {

    private fun dev(
        address: String,
        name: String = "",
        bonded: Boolean = false,
        audio: Boolean = false,
    ) = BtDevice(address, name, bonded, audio, connected = false, rssi = null)

    // ── orderForPanel ────────────────────────────────────────────────────────

    @Test
    fun `bonded devices come before anything a scan found`() {
        val ordered = orderForPanel(
            listOf(
                dev("AA", name = "Zeta speaker", audio = true),
                dev("BB", name = "Alpha", bonded = true),
            )
        )
        assertEquals(listOf("BB", "AA"), ordered.map { it.address })
    }

    @Test
    fun `among unbonded devices the audio ones come first`() {
        val ordered = orderForPanel(
            listOf(
                dev("AA", name = "Alpha laptop"),
                dev("BB", name = "Zeta speaker", audio = true),
            )
        )
        assertEquals(listOf("BB", "AA"), ordered.map { it.address })
    }

    @Test
    fun `equal devices sort by name, case-insensitively`() {
        val ordered = orderForPanel(
            listOf(
                dev("AA", name = "zeta", audio = true),
                dev("BB", name = "Alpha", audio = true),
                dev("CC", name = "beta", audio = true),
            )
        )
        assertEquals(listOf("Alpha", "beta", "zeta"), ordered.map { it.name })
    }

    @Test
    fun `a device with no name goes last in its group, not first`() {
        val ordered = orderForPanel(
            listOf(
                dev("AA", name = "", audio = true),
                dev("BB", name = "Zeta", audio = true),
            )
        )
        assertEquals(listOf("Zeta", ""), ordered.map { it.name })
    }

    // ── reconnectTarget ──────────────────────────────────────────────────────

    private val bonded = setOf("SPEAKER")

    @Test
    fun `the remembered speaker is reached for when nothing is playing`() {
        assertEquals(
            "SPEAKER",
            reconnectTarget(
                enabled = true, suspended = false, lastAddress = "SPEAKER",
                connectedAddress = "", availableAddresses = bonded,
            )
        )
    }

    @Test
    fun `Disconnect suspends it, so the button is not undone a second later`() {
        assertNull(
            reconnectTarget(
                enabled = true, suspended = true, lastAddress = "SPEAKER",
                connectedAddress = "", availableAddresses = bonded,
            )
        )
    }

    @Test
    fun `switched off, nothing is reached for`() {
        assertNull(
            reconnectTarget(
                enabled = false, suspended = false, lastAddress = "SPEAKER",
                connectedAddress = "", availableAddresses = bonded,
            )
        )
    }

    @Test
    fun `already connected somewhere, it leaves the connection alone`() {
        assertNull(
            reconnectTarget(
                enabled = true, suspended = false, lastAddress = "SPEAKER",
                connectedAddress = "OTHER", availableAddresses = bonded,
            )
        )
    }

    @Test
    fun `a forgotten speaker is no longer a target`() {
        assertNull(
            reconnectTarget(
                enabled = true, suspended = false, lastAddress = "SPEAKER",
                connectedAddress = "", availableAddresses = emptySet(),
            )
        )
    }

    @Test
    fun `nothing remembered, nothing to reach for`() {
        assertNull(
            reconnectTarget(
                enabled = true, suspended = false, lastAddress = "",
                connectedAddress = "", availableAddresses = bonded,
            )
        )
    }

    // ── pairingAutoAccept ────────────────────────────────────────────────────

    @Test
    fun `nobody pressed Pair, so no prompt is answered`() {
        assertFalse(pairingAutoAccept(openedAtMs = 0L, nowMs = 5_000L))
    }

    @Test
    fun `inside the window a prompt is answered`() {
        assertTrue(pairingAutoAccept(openedAtMs = 1_000L, nowMs = 1_000L + PAIRING_WINDOW_MS - 1))
    }

    @Test
    fun `the window is closed at exactly its length, not a moment after`() {
        assertFalse(pairingAutoAccept(openedAtMs = 1_000L, nowMs = 1_000L + PAIRING_WINDOW_MS))
    }

    @Test
    fun `long after the press, a prompt is left alone`() {
        assertFalse(pairingAutoAccept(openedAtMs = 1_000L, nowMs = 10 * 60 * 1_000L))
    }

    /**
     * The window is measured against elapsedRealtime for this reason, and the test says so: the
     * R1's wall clock jumps backwards when NTP lands after boot. A negative elapsed reading means
     * the clock moved, not that the press is in the future, and the safe answer is to not accept.
     */
    @Test
    fun `a clock that went backwards does not open the window`() {
        assertFalse(pairingAutoAccept(openedAtMs = 10_000L, nowMs = 5_000L))
    }
}
