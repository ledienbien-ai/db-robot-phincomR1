package info.dourok.voicebot.domain.voice

/**
 * AGC nhẹ cho luồng STT (CHỈ áp trước Opus encode, KHÔNG đụng wake-detect) — kéo giọng xa/nhỏ lên
 * mức chuẩn để Opus mã hoá khoẻ + Whisper nghe rõ, mà không méo cứng như nhân gain cố định.
 *
 * Envelope đỉnh: attack TỨC THÌ (frame to -> gain giảm ngay, chống clip), release CHẬM (giữ gain
 * qua khoảng lặng trong câu -> không pump). Gain tính thẳng từ env mỗi frame nên KHÔNG có độ trễ
 * ramp-up (boost ngay từ frame đầu của câu — quan trọng vì lệnh ngắn ~1.5s).
 */
class SttAgc(
    var target: Float = 0.35f,     // đỉnh mục tiêu (0..1)
    var maxGain: Float = 30f,      // trần khuếch đại
    private val floor: Float = 0.004f,      // dưới mức này coi là im -> không bơm lên trần
    private val releaseDecay: Float = 0.97f, // env giảm /frame (60ms) khi tín hiệu nhỏ lại (~giữ 2s)
) {
    private var env = 0f

    fun reset() { env = 0f }

    /** Áp gain in-place lên frame PCM16 little-endian. */
    fun process(buf: ByteArray, len: Int) {
        var pk = 0
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val a = if (s >= 0) s else -s
            if (a > pk) pk = a
            i += 2
        }
        val peak = pk / 32768f
        env = if (peak > env) peak else env * releaseDecay   // attack tức thì, release chậm
        val hi = maxOf(maxGain, 1f)   // slider có thể = 0 (tắt boost); coerceIn(1,<1) sẽ ném exception
        val gain = (target / maxOf(env, floor)).coerceIn(1f, hi)
        if (gain <= 1f) return
        i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val v = (s * gain).toInt().coerceIn(-32768, 32767)
            buf[i] = (v and 0xFF).toByte()
            buf[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}
