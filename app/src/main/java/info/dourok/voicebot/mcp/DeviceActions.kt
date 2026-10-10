package info.dourok.voicebot.mcp

/**
 * Things the assistant's tools do that need an Android context -- which the tools, built with the
 * protocol, do not have. ControlServer owns the context and already implements them for the panel,
 * so it lends them here when it starts. Unset (very early start-up) they simply report "not ready".
 */
object DeviceActions {
    /** Set the speaker volume, 0..100. */
    @Volatile var setVolume: ((Int) -> Unit)? = null

    /** Current speaker volume, 0..100. */
    @Volatile var getVolume: (() -> Int)? = null
}
