package info.dourok.voicebot.data.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.bluetooth.BtController
import info.dourok.voicebot.domain.bluetooth.BtDevice
import info.dourok.voicebot.domain.bluetooth.BtResult
import info.dourok.voicebot.domain.bluetooth.BtState
import info.dourok.voicebot.domain.bluetooth.orderForPanel
import info.dourok.voicebot.domain.bluetooth.pairingAutoAccept
import info.dourok.voicebot.domain.bluetooth.reconnectTarget
import info.dourok.voicebot.domain.voice.AppLog
import java.util.concurrent.ConcurrentHashMap

/**
 * A2DP **source** on the R1: pair with a speaker or headphones and let the platform route
 * STREAM_MUSIC out to it, which is every sound this app makes — TTS, music and the bulletin all
 * go through one AudioTrack on that stream.
 *
 * Measured on the device before this was written: the adapter is a Broadcom AP6335 on /dev/ttyS1,
 * already enabled, and `A2dpService` plus `AvrcpControllerService` are running. `audio_policy.conf`
 * carries `AUDIO_DEVICE_OUT_ALL_A2DP`, so nothing in the audio path needs arranging here.
 */
class AndroidBtController(private val context: Context) : BtController {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var a2dp: BluetoothA2dp? = null
    private var started = false

    /** What the last scan saw, by address. Cleared when a new scan starts. */
    private val found = ConcurrentHashMap<String, BtDevice>()

    /**
     * elapsedRealtime of the last Pair press — the clock this window is measured against, because
     * the wall clock jumps when NTP lands after boot.
     */
    @Volatile private var pairWindowOpenedAt = 0L

    /** Address we are bonding, so a successful bond can connect without a second press. */
    @Volatile private var pairing = ""

    /** Set by Disconnect, cleared by a manual Connect. See reconnectTarget(). */
    @Volatile private var autoSuspended = false

    @Volatile private var busy = ""
    /**
     * elapsedRealtime after which [busy] is reported as idle regardless. A connect that is refused
     * silently upstream never produces a state change, and a panel stuck on "Đang nối…" is a panel
     * lying about work nobody is doing.
     */
    @Volatile private var busyUntil = 0L
    @Volatile private var error = ""

    override fun start() {
        if (started) return
        started = true
        if (adapter == null) {
            AppLog.i("Bluetooth: máy không có adapter")
            return
        }
        register()
        // A remembered address that is not bonded can never be reconnected, so keeping the string
        // is keeping a dead reference -- and one gets written the first time somebody pokes
        // /api/bt/connect with an address by hand.
        val last = Settings.btLastDevice
        if (last.isNotEmpty() && bondedDevices().none { it.address == last }) {
            AppLog.i("Bluetooth: quên thiết bị $last vì không còn ghép đôi")
            Settings.btLastDevice = ""
        }
        bindA2dp()
    }

    // ── state ────────────────────────────────────────────────────────────────

    override fun state(): BtState {
        val connected = connectedAddress()
        val bonded = bondedDevices().map { it.toBt(connected, rssi = null) }
        val bondedAddrs = bonded.map { it.address }.toSet()
        val scanned = found.values.filter { it.address !in bondedAddrs }
            .map { it.copy(connected = it.address == connected) }
        return BtState(
            supported = adapter != null,
            enabled = adapter?.isEnabled == true,
            scanning = adapter?.isDiscovering == true,
            autoReconnect = Settings.btAutoReconnect,
            devices = orderForPanel(bonded + scanned),
            connectedAddress = connected,
            busy = if (busy.isNotEmpty() && SystemClock.elapsedRealtime() > busyUntil) "" else busy,
            error = error,
        )
    }

    private fun bondedDevices(): List<BluetoothDevice> =
        try { adapter?.bondedDevices?.toList() ?: emptyList() }
        catch (e: Exception) { Log.e(TAG, "bondedDevices: ${e.message}"); emptyList() }

    private fun connectedAddress(): String =
        try { a2dp?.connectedDevices?.firstOrNull()?.address ?: "" }
        catch (e: Exception) { Log.e(TAG, "connectedDevices: ${e.message}"); "" }

    private fun BluetoothDevice.toBt(connected: String, rssi: Int?) = BtDevice(
        address = address,
        name = name ?: "",
        bonded = bondState == BluetoothDevice.BOND_BONDED,
        audio = bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO,
        connected = address == connected,
        rssi = rssi,
    )

    // ── adapter / scan ───────────────────────────────────────────────────────

    override fun setEnabled(on: Boolean): BtResult {
        val a = adapter ?: return BtResult.fail("Máy không có Bluetooth")
        return try {
            val ok = if (on) a.enable() else a.disable()
            AppLog.i("Bluetooth: ${if (on) "bật" else "tắt"} adapter -> $ok")
            if (ok) BtResult.OK else BtResult.fail("Adapter từ chối ${if (on) "bật" else "tắt"}")
        } catch (e: Exception) {
            BtResult.fail("Không đổi được trạng thái Bluetooth: ${e.message}")
        }
    }

    /**
     * One bounded discovery per press. The AP6335 is a combo part whose radio is shared with wifi,
     * and the panel asking for this scan is itself reached over that wifi — so scanning is a thing
     * that starts, finishes and stops, never a thing left running while a card is open.
     */
    override fun startScan(): BtResult {
        val a = adapter ?: return BtResult.fail("Máy không có Bluetooth")
        if (!a.isEnabled) return BtResult.fail("Bluetooth đang tắt")
        return try {
            found.clear()
            if (a.isDiscovering) a.cancelDiscovery()
            error = ""
            val ok = a.startDiscovery()
            AppLog.i("Bluetooth: quét -> $ok")
            if (ok) BtResult.OK else BtResult.fail("Không bắt đầu quét được")
        } catch (e: Exception) {
            BtResult.fail("Quét lỗi: ${e.message}")
        }
    }

    override fun stopScan() {
        try { if (adapter?.isDiscovering == true) adapter.cancelDiscovery() }
        catch (e: Exception) { Log.e(TAG, "cancelDiscovery: ${e.message}") }
    }

    // ── pair / connect / forget ──────────────────────────────────────────────

    override fun pair(address: String): BtResult {
        val device = remote(address) ?: return BtResult.fail("Không có thiết bị $address")
        // A radio hopping through a discovery cannot answer a page. Pairing while scanning is the
        // classic way to make bonding work only sometimes.
        stopScan()
        if (device.bondState == BluetoothDevice.BOND_BONDED) return connect(address)
        pairWindowOpenedAt = SystemClock.elapsedRealtime()
        pairing = address
        busy = "pairing"
        busyUntil = SystemClock.elapsedRealtime() + BUSY_TIMEOUT_MS
        error = ""
        return try {
            val ok = device.createBond()
            AppLog.i("Bluetooth: ghép đôi ${device.name ?: address} -> $ok")
            if (ok) BtResult.OK else { busy = ""; BtResult.fail("Không bắt đầu ghép đôi được") }
        } catch (e: Exception) {
            busy = ""
            BtResult.fail("Ghép đôi lỗi: ${e.message}")
        }
    }

    override fun connect(address: String): BtResult {
        val device = remote(address) ?: return BtResult.fail("Không có thiết bị $address")
        val proxy = a2dp ?: return BtResult.fail("Chưa nối được A2DP profile, thử lại sau 1-2 giây")
        stopScan()
        // A manual Connect is what lifts the suspension a manual Disconnect put on. That is about
        // intent, so it happens whether or not the connection then succeeds.
        autoSuspended = false
        // btLastDevice is NOT written here. Measured on the device: A2DP's connect() answers `true`
        // for an address that was never bonded -- it only means the request was queued -- so
        // remembering the address at this point remembers speakers that never played a note. The
        // CONNECTION_STATE_CHANGED handler writes it, which is the only evidence it worked.
        busy = "connecting"
        busyUntil = SystemClock.elapsedRealtime() + BUSY_TIMEOUT_MS
        error = ""
        val r = BtHidden.connect(proxy, device)
        AppLog.i("Bluetooth: nối ${device.name ?: address} -> ${if (r.ok) "ok" else r.error}")
        if (!r.ok) { busy = ""; error = r.error }
        return r
    }

    override fun disconnect(address: String): BtResult {
        val device = remote(address) ?: return BtResult.fail("Không có thiết bị $address")
        val proxy = a2dp ?: return BtResult.fail("Chưa nối được A2DP profile")
        // "Stop playing through that speaker" is an intent, not a dropout — so it outlives this
        // call and auto-reconnect honours it until somebody presses Connect again.
        autoSuspended = true
        busy = "disconnecting"
        busyUntil = SystemClock.elapsedRealtime() + BUSY_TIMEOUT_MS
        error = ""
        val r = BtHidden.disconnect(proxy, device)
        AppLog.i("Bluetooth: ngắt ${device.name ?: address} -> ${if (r.ok) "ok" else r.error}")
        if (!r.ok) { busy = ""; error = r.error }
        return r
    }

    override fun forget(address: String): BtResult {
        val device = remote(address) ?: return BtResult.fail("Không có thiết bị $address")
        stopScan()
        val r = BtHidden.removeBond(device)
        AppLog.i("Bluetooth: xoá ghép đôi ${device.name ?: address} -> ${if (r.ok) "ok" else r.error}")
        if (r.ok) {
            if (Settings.btLastDevice == address) Settings.btLastDevice = ""
            found.remove(address)
        } else {
            error = r.error
        }
        return r
    }

    override fun setAutoReconnect(on: Boolean) {
        Settings.btAutoReconnect = on
        // Turning it back on is as deliberate as pressing Connect, so it clears the suspension too.
        if (on) autoSuspended = false
        AppLog.i("Bluetooth: tự nối lại = $on")
    }

    override fun applyOutputVolume(index: Int): BtResult {
        if (connectedAddress().isEmpty()) return BtResult.fail("Chưa nối thiết bị Bluetooth nào")
        val r = BtHidden.setA2dpMusicVolumeIndex(index)
        if (!r.ok) error = r.error
        return r
    }

    override fun outputVolumeIndex(): Int =
        if (connectedAddress().isEmpty()) -1 else BtHidden.a2dpMusicVolumeIndex()

    /**
     * Push whatever the panel is showing onto the speaker the moment it connects, so it does not
     * start at the fallback index nobody chose. Measured: without this the A2DP output sits at 6 of
     * 15 while the panel reads 100%.
     */
    private fun mirrorVolumeToSpeaker() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val r = BtHidden.setA2dpMusicVolumeIndex(index)
            AppLog.i("Bluetooth: đặt âm lượng A2DP = $index/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} -> ${if (r.ok) "ok" else r.error}")
        } catch (e: Exception) {
            Log.e(TAG, "mirrorVolumeToSpeaker: ${e.message}")
        }
    }

    private fun remote(address: String): BluetoothDevice? =
        try { adapter?.getRemoteDevice(address) }
        catch (e: Exception) { Log.e(TAG, "getRemoteDevice($address): ${e.message}"); null }

    // ── auto-reconnect ───────────────────────────────────────────────────────

    /**
     * Tries the remembered device once. Called at start, when the adapter comes on, when the
     * speaker itself reconnects at the ACL level (many do on power-up), and after a fresh bond.
     */
    private fun tryReconnect(why: String) {
        val proxy = a2dp ?: return
        val target = reconnectTarget(
            enabled = Settings.btAutoReconnect,
            suspended = autoSuspended,
            lastAddress = Settings.btLastDevice,
            connectedAddress = connectedAddress(),
            availableAddresses = bondedDevices().map { it.address }.toSet(),
        ) ?: return
        val device = remote(target) ?: return
        val r = BtHidden.connect(proxy, device)
        AppLog.i("Bluetooth: tự nối lại ($why) ${device.name ?: target} -> ${if (r.ok) "ok" else r.error}")
    }

    // ── receivers ────────────────────────────────────────────────────────────

    private fun bindA2dp() {
        val a = adapter ?: return
        try {
            a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.A2DP) return
                    a2dp = proxy as? BluetoothA2dp
                    AppLog.i("Bluetooth: A2DP profile sẵn sàng")
                    tryReconnect("profile ready")
                    // A2DP lives in the Bluetooth stack, not in this app, so a speaker stays
                    // connected across an app restart -- and then no CONNECTION_STATE_CHANGED ever
                    // arrives to carry the volume across. Measured after a reinstall: connected
                    // device, and the index still at the fallback nobody chose.
                    if (connectedAddress().isNotEmpty()) mirrorVolumeToSpeaker()
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) a2dp = null
                }
            }, BluetoothProfile.A2DP)
        } catch (e: Exception) {
            Log.e(TAG, "getProfileProxy: ${e.message}")
        }
    }

    private fun register() {
        val main = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(receiver, main)

        // Its own filter, with a priority, because the ROM's Settings app also listens for this and
        // would raise a dialog on a screen the R1 does not have. Whoever answers first resolves it.
        val pairingFilter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            priority = PAIRING_PRIORITY
        }
        context.registerReceiver(receiver, pairingFilter)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> device?.let {
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    found[it.address] = it.toBt(
                        connected = "",
                        rssi = if (rssi == Short.MIN_VALUE) null else rssi.toInt(),
                    )
                }

                BluetoothDevice.ACTION_PAIRING_REQUEST -> device?.let {
                    onPairingRequest(it, intent)
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> device?.let {
                    val bond = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE
                    )
                    if (bond == BluetoothDevice.BOND_BONDED) {
                        AppLog.i("Bluetooth: đã ghép đôi ${it.name ?: it.address}")
                        // Pairing a speaker then having to press Connect is a second press for
                        // one intent.
                        if (pairing == it.address) { pairing = ""; connect(it.address) }
                    } else if (bond == BluetoothDevice.BOND_NONE && pairing == it.address) {
                        pairing = ""
                        busy = ""
                        error = "Ghép đôi thất bại với ${it.name ?: it.address}"
                        AppLog.i("Bluetooth: ghép đôi thất bại ${it.name ?: it.address}")
                    }
                }

                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (st == BluetoothProfile.STATE_CONNECTED) {
                        busy = ""
                        error = ""
                        // Here, not in connect(): this is the only point at which the speaker is
                        // known to have accepted audio. Covers the speaker reconnecting by itself
                        // too, which is how most of them behave on power-up.
                        device?.address?.let { Settings.btLastDevice = it }
                        mirrorVolumeToSpeaker()
                        AppLog.i("Bluetooth: đang phát qua ${device?.name ?: device?.address}")
                    } else if (st == BluetoothProfile.STATE_DISCONNECTED) {
                        busy = ""
                        AppLog.i("Bluetooth: đã ngắt ${device?.name ?: device?.address}")
                    }
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> device?.let {
                    // Speakers commonly reach back to their last source on power-up; the ACL link
                    // arrives first and A2DP does not always follow on its own.
                    if (it.address == Settings.btLastDevice) tryReconnect("acl")
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == BluetoothAdapter.STATE_ON) {
                        bindA2dp()
                        tryReconnect("adapter on")
                    } else if (st == BluetoothAdapter.STATE_OFF) {
                        found.clear()
                        busy = ""
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> busy = ""
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> busy = "scanning"
            }
        }
    }

    /**
     * Answers the prompt this box cannot show. Only inside the window a Pair press opened —
     * outside it the request is left alone, so a neighbour's phone cannot bond itself to the
     * speaker by asking at the right moment.
     */
    private fun onPairingRequest(device: BluetoothDevice, intent: Intent) {
        if (!pairingAutoAccept(pairWindowOpenedAt, SystemClock.elapsedRealtime())) {
            AppLog.i("Bluetooth: bỏ qua yêu cầu ghép đôi từ ${device.name ?: device.address}")
            return
        }
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        try {
            when (variant) {
                BluetoothDevice.PAIRING_VARIANT_PIN -> {
                    // Legacy BT 2.x audio gear. setPin takes the PIN's own characters as bytes,
                    // which is all the framework's hidden convertPinToBytes does -- so this needs
                    // no reflection, and the three calls in BtHidden stay the only ones.
                    val ok = device.setPin(LEGACY_PIN.toByteArray(Charsets.UTF_8))
                    AppLog.i("Bluetooth: trả PIN $LEGACY_PIN -> $ok")
                }
                else -> {
                    val ok = device.setPairingConfirmation(true)
                    AppLog.i("Bluetooth: xác nhận ghép đôi (variant $variant) -> $ok")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pairing request variant $variant: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BtController"
        /** Above an ordinary receiver so the ROM's Settings app is not the one that answers. */
        private const val PAIRING_PRIORITY = 999
        /** What audio gear old enough to ask for a PIN at all asks for. */
        private const val LEGACY_PIN = "0000"
        /** How long a pair/connect/disconnect may be reported as in flight before it is called over. */
        private const val BUSY_TIMEOUT_MS = 20_000L
    }
}
