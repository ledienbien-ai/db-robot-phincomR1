package info.dourok.voicebot.domain.voice

/** Trạng thái đèn LED theo ngữ cảnh trợ lý (mỗi cái 1 màu/hiệu ứng khác). */
enum class LedState { IDLE, LISTENING, SPEAKING, MUSIC }

/** Điều khiển đèn LED của thiết bị theo trạng thái. */
interface LedIndicator {
    /** Đặt trạng thái (bỏ qua nếu trùng trạng thái hiện tại). */
    fun setState(state: LedState)

    /** Gửi LUÔN (kể cả trùng) — để test/sweep mã qua panel. */
    fun preview(state: LedState)
}
