package info.dourok.voicebot.domain.voice

import info.dourok.voicebot.data.Settings
import java.io.ByteArrayOutputStream

/**
 * Ghi thử mic cho control panel. KHÔNG mở AudioRecord riêng (mic đã bị wake-detect giữ độc quyền) —
 * thay vào đó [VoiceAssistant] bơm từng frame PCM (16 kHz mono 16-bit) vào đây khi đang "recording".
 * Luồng: /api/mic/start[?agc=1] → nói → /api/mic/stop → tải /api/mic/rec.wav nghe lại.
 *
 * 2 chế độ A/B: THÔ (trước AGC, nghe đúng mic bắt được) vs +AGC (áp SttAgc target/max gain HIỆN TẠI
 * để nghe hiệu ứng slider). Chế độ +AGC dùng 1 SttAgc RIÊNG (state envelope tách khỏi luồng STT thật,
 * chạy được cả khi app idle — luồng STT chỉ áp AGC lúc LISTENING).
 */
object MicTest {
    private const val SAMPLE_RATE = 16000
    private const val MAX_BYTES = SAMPLE_RATE * 2 * 30   // trần 30s (~960 KB) chống phình RAM -> tự dừng

    @Volatile var recording = false
        private set
    @Volatile var withAgc = false
        private set

    private var agc: SttAgc? = null
    private val buf = ByteArrayOutputStream()

    /** @param useAgc true = áp SttAgc (target/max gain từ Settings hiện tại) trước khi ghi. */
    @Synchronized fun start(useAgc: Boolean) {
        buf.reset()
        withAgc = useAgc
        agc = if (useAgc) SttAgc(target = Settings.agcTarget, maxGain = Settings.agcMaxGain) else null
        recording = true
    }
    @Synchronized fun stop() { recording = false }
    @Synchronized fun sizeBytes(): Int = buf.size()

    /** Gọi từ audio loop mỗi frame; bỏ qua khi không recording. Đầy 30s thì tự dừng. */
    @Synchronized fun feed(pcm: ByteArray, len: Int) {
        if (!recording) return
        if (buf.size() + len > MAX_BYTES) { recording = false; return }
        val a = agc
        if (a != null) {
            val tmp = pcm.copyOf(len)   // copy: SttAgc.process mutate in-place, KHÔNG đụng buffer của loop
            a.process(tmp, len)
            buf.write(tmp, 0, len)
        } else {
            buf.write(pcm, 0, len)
        }
    }

    /** WAV PCM16 mono 16 kHz của bản ghi gần nhất; null nếu chưa ghi gì. */
    @Synchronized fun wav(): ByteArray? {
        val pcm = buf.toByteArray()
        if (pcm.isEmpty()) return null
        return wavHeader(pcm.size) + pcm
    }

    private fun wavHeader(dataLen: Int): ByteArray {
        val byteRate = SAMPLE_RATE * 2
        val h = ByteArray(44)
        fun str(off: Int, s: String) { for (i in s.indices) h[off + i] = s[i].code.toByte() }
        fun le32(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte(); h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(off: Int, v: Int) { h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v shr 8) and 0xFF).toByte() }
        str(0, "RIFF"); le32(4, 36 + dataLen); str(8, "WAVE")
        str(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, 1)
        le32(24, SAMPLE_RATE); le32(28, byteRate); le16(32, 2); le16(34, 16)
        str(36, "data"); le32(40, dataLen)
        return h
    }
}
