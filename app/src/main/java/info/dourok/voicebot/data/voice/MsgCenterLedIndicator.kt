package info.dourok.voicebot.data.voice

import android.os.IBinder
import android.util.Log
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.LedIndicator
import info.dourok.voicebot.domain.voice.LedState

/**
 * Điều khiển LED ring R1 qua system service "msgcenter" (IMessageCenterService) bằng reflection
 * (như aiboxplus defpackage/s4.java) — /sys/class/leds chỉ root ghi được.
 *
 * Giao thức: sendMsg(4096, code, 0). Mã theo trạng thái (mặc định aiboxplus: listening=501,
 * speaking=204, idle=504), chỉnh được qua panel (Settings.led*). Mỗi trạng thái là 1 DÃY code (CSV)
 * gửi tuần tự cách nhau 120ms -> làm được hiệu ứng nhiều bước (vd breathing "505,309,503").
 */
class MsgCenterLedIndicator : LedIndicator {

    private var service: Any? = null
    private var sendMsg: java.lang.reflect.Method? = null
    private var inited = false
    @Volatile private var last: LedState? = null

    override fun setState(state: LedState) {
        if (state == last) return   // không gửi lại trùng trạng thái
        apply(state)
    }

    override fun preview(state: LedState) = apply(state)   // luôn gửi (test/sweep)

    private fun apply(state: LedState) {
        last = state
        val csv = when (state) {
            LedState.IDLE -> Settings.ledIdle
            LedState.LISTENING -> Settings.ledListening
            LedState.SPEAKING -> Settings.ledSpeaking
            LedState.MUSIC -> Settings.ledMusic
        }
        val codes = csv.split(",").mapNotNull { it.trim().toIntOrNull() }
        Thread {
            ensure()
            for (c in codes) {
                send(c)
                try { Thread.sleep(120) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    private fun ensure() {
        if (inited) return
        inited = true
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, "msgcenter") as? IBinder ?: return
            val stub = Class.forName("android.os.IMessageCenterService\$Stub")
            service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            sendMsg = service?.javaClass?.getMethod(
                "sendMsg",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            Log.i(TAG, "msgcenter ready: ${service != null && sendMsg != null}")
        } catch (e: Exception) {
            Log.e(TAG, "msgcenter init failed: ${e.message}")
        }
    }

    private fun send(code: Int) {
        try {
            sendMsg?.invoke(service, CATEGORY, code, 0)
        } catch (e: Exception) {
            Log.e(TAG, "sendMsg $code failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LedIndicator"
        private const val CATEGORY = 4096
    }
}
