package info.dourok.voicebot.media

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayableMusicUrlTest {
    private val base = "https://music.example.com"

    @Test fun httpsSongOnTheMusicServerGoesThroughTheRelay() {
        val song = "$base/stream_mp3?id=yt_abc"
        assertEquals(MUSIC_RELAY + java.net.URLEncoder.encode(song, "UTF-8"), playableMusicUrl(song, "$base/"))
    }

    @Test fun plainHttpServerIsOpenedDirectly() {
        val song = "http://192.168.1.5:5000/stream_mp3?id=x"
        assertEquals(song, playableMusicUrl(song, "http://192.168.1.5:5000"))
    }

    @Test fun otherHostsAreNotRelayed() {
        val other = "https://elsewhere.example.com/a.mp3"
        assertEquals(other, playableMusicUrl(other, base))
        assertEquals(other, playableMusicUrl(other, ""))
    }

    @Test fun lookalikeHostIsNotRelayed() {
        val other = "https://music.example.com.evil.net/a.mp3"
        assertEquals(other, playableMusicUrl(other, base))
    }
}
