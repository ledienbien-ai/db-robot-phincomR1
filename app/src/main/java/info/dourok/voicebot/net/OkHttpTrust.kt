package info.dourok.voicebot.net

import okhttp3.OkHttpClient

/**
 * Let this client trust the app's own root certificates as well as the system's (see [Https]).
 * Android 5.1 has no ISRG Root X1, so without this a server on a Let's Encrypt certificate fails
 * with "Trust anchor for certification path not found".
 */
fun OkHttpClient.Builder.withBundledRoots(): OkHttpClient.Builder {
    val f = Https.socketFactory()
    val t = Https.trustManager()
    if (f != null && t != null) sslSocketFactory(f, t)
    return this
}
