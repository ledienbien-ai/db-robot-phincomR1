package info.dourok.voicebot.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MaskApiKeyTest {
    @Test fun emptyStaysEmpty() = assertEquals("", maskApiKey(""))
    @Test fun nullStaysEmpty() = assertEquals("", maskApiKey(null))
    @Test fun shortIsFullyMasked() = assertEquals("••••", maskApiKey("abcd"))
    @Test fun longShowsLast4() = assertEquals("••••6789", maskApiKey("sk-123456789"))
}
