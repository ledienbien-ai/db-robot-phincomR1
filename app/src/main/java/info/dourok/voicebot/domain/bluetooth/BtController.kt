package info.dourok.voicebot.domain.bluetooth

/**
 * One remote Bluetooth device, as the control panel needs to see it.
 *
 * [audio] is `BluetoothClass.getMajorDeviceClass() == AUDIO_VIDEO`. It is carried rather than
 * filtered on, so the panel can offer "show everything" without a second round trip — cheap
 * speakers do misdeclare their class, and a device that can never appear is one nobody can
 * diagnose.
 */
data class BtDevice(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val audio: Boolean,
    val connected: Boolean,
    /** Signal strength from a scan, in dBm. Null for a bonded device nobody has just seen. */
    val rssi: Int?,
)

/** Everything the panel polls while its Bluetooth card is open. */
data class BtState(
    val supported: Boolean,
    val enabled: Boolean,
    val scanning: Boolean,
    val autoReconnect: Boolean,
    /** Bonded devices first, then what the last scan found. See [orderForPanel]. */
    val devices: List<BtDevice>,
    /** Address of the device audio is currently going to, or "" when none. */
    val connectedAddress: String,
    /** What is in flight right now ("pairing", "connecting", …), or "" when idle. */
    val busy: String,
    /** Last failure worth showing, or "". */
    val error: String,
)

/**
 * Outcome of a request that can fail for a reason worth putting on screen. Three of the calls
 * below reach hidden framework methods by reflection, and a reflection miss is invisible until
 * runtime — so the reason travels back rather than being logged and swallowed.
 */
data class BtResult(val ok: Boolean, val error: String = "") {
    companion object {
        val OK = BtResult(true)
        fun fail(why: String) = BtResult(false, why)
    }
}

/**
 * Bluetooth audio out: this device pairs with a speaker or headphones and plays through them
 * (A2DP **source**). The opposite direction — a phone streaming into the R1 — is the platform's
 * own A2dpSinkService and is deliberately not driven from here.
 */
interface BtController {
    /** Register receivers, bind the A2DP proxy, and try the remembered device once. */
    fun start()

    fun state(): BtState

    fun setEnabled(on: Boolean): BtResult

    /** Runs one bounded discovery. The radio is shared with wifi, which is how the panel is reached. */
    fun startScan(): BtResult
    fun stopScan()

    /** Bond, auto-accepting a PIN/passkey prompt for a short window because this box has no screen. */
    fun pair(address: String): BtResult

    fun connect(address: String): BtResult

    /** Also suspends auto-reconnect: "stop playing through that" is an intent, not a dropout. */
    fun disconnect(address: String): BtResult

    fun forget(address: String): BtResult

    fun setAutoReconnect(on: Boolean)

    /**
     * Force the volume index Android applies to the connected speaker.
     *
     * Needed because the platform's own volume call does not reach it: this box keeps one index per
     * output device, and the device it considers "current" for music is not the Bluetooth one -- so
     * the panel's slider moved an index the speaker never read. Fails when nothing is connected,
     * rather than reporting success for work it did not do.
     */
    fun applyOutputVolume(index: Int): BtResult

    /** That index as the platform holds it, or -1 when nothing is connected or it cannot be read. */
    fun outputVolumeIndex(): Int
}

/**
 * Bonded devices first, then audio devices, then by name — with nameless devices last inside each
 * group. A list somebody scans for one speaker wants one predictable order, and the address is
 * not a name for anything a person recognises.
 */
fun orderForPanel(devices: List<BtDevice>): List<BtDevice> =
    devices.sortedWith(
        compareByDescending<BtDevice> { it.bonded }
            .thenByDescending { it.audio }
            .thenBy { it.name.isEmpty() }
            .thenBy { it.name.lowercase() }
            .thenBy { it.address }
    )

/**
 * Which device auto-reconnect should reach for, or null for "leave it alone".
 *
 * Pure because every one of its answers is a judgement somebody has to be able to check, and
 * nothing in the type system catches one being wrong: the wrong answer is a speaker that
 * reconnects after being told to stop, or one that never comes back after a power cut, and both
 * only show up a minute later.
 */
fun reconnectTarget(
    enabled: Boolean,
    suspended: Boolean,
    lastAddress: String,
    connectedAddress: String,
    availableAddresses: Set<String>,
): String? {
    if (!enabled) return null
    // Disconnect was pressed: honour it until somebody presses Connect again.
    if (suspended) return null
    if (lastAddress.isEmpty()) return null
    // Already playing somewhere — including to the remembered device itself.
    if (connectedAddress.isNotEmpty()) return null
    if (lastAddress !in availableAddresses) return null
    return lastAddress
}

/** How long a Pair press keeps the door open for an unattended PIN/passkey answer. */
const val PAIRING_WINDOW_MS = 30_000L

/**
 * Whether a pairing prompt arriving now should be answered without asking anybody.
 *
 * The two arguments are **elapsed-realtime** readings, not wall clock. The R1's clock jumps when
 * NTP lands after boot — logcat shows January timestamps until it does — and a window measured
 * against a clock that jumps is a window that either never opens or never closes.
 *
 * [openedAtMs] of 0 means no Pair press has happened, so nothing is accepted: auto-accepting
 * unconditionally would let any device in the house bond itself to the speaker.
 */
fun pairingAutoAccept(openedAtMs: Long, nowMs: Long, windowMs: Long = PAIRING_WINDOW_MS): Boolean {
    if (openedAtMs <= 0L) return false
    val elapsed = nowMs - openedAtMs
    if (elapsed < 0L) return false
    return elapsed < windowMs
}
