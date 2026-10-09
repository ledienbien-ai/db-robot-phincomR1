# ===== Keep rules cho release minify (R8) =====

# JNI: giữ class + tên native method (.so bind theo tên Java_<pkg>_<class>_<method>)
-keepclasseswithmembernames class * {
    native <methods>;
}

# microWakeWord: .so bind đúng com.aiboxplus.sample_wake_word.MicroWakeWordEngine
# + DetectionListener gọi NGƯỢC từ native (callback) -> phải giữ nguyên.
-keep class com.aiboxplus.sample_wake_word.** { *; }

# Opus JNI (src/main/cpp/opus_recorder.cpp + opus_decoder.cpp bind theo tên class+method)
-keep class info.dourok.voicebot.OpusEncoder { *; }
-keep class info.dourok.voicebot.OpusDecoder { *; }

# snowboy JNI (libsnowboy-detect-android.so bind ai.kitt.snowboy.snowboyJNI/SnowboyDetect theo tên)
-keep class ai.kitt.snowboy.** { *; }

# Giữ tên file + số dòng cho stack trace crash đọc được
-keepattributes SourceFile,LineNumberTable

# okhttp tham chiếu TLS provider tùy chọn không nhúng -> bỏ qua cảnh báo
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ===== Release: strip every android.util.Log call =====
# Logging here sits on hot paths (per-frame audio state, wake scores, playback ticks), so in release
# R8 removes the calls outright -- including the string building that would otherwise run only to be
# thrown away. Crash reporting is NOT affected: VApplication writes stack traces to
# /sdcard/voicebot-crash.log with real file I/O, not through Log.
# (Requires the optimizing config, which proguardFiles already uses.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
}
