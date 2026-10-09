package info.dourok.voicebot.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSearchResultTest {
    @Test fun parsesResults() {
        val json = """
            {"query":"mot nha","count":1,"results":[
              {"video_id":"S8sYD-2yco0","title":"Một Nhà","artist":"Da LAB","duration":"4:12","thumbnail":"https://x/y.jpg"}
            ]}
        """.trimIndent()
        val results = parseSearchResults(json)
        assertEquals(1, results.size)
        assertEquals(MediaSearchResult("S8sYD-2yco0", "Một Nhà", "Da LAB", "4:12", "https://x/y.jpg"), results[0])
    }

    @Test fun emptyResultsArray() {
        assertEquals(emptyList(), parseSearchResults("""{"query":"x","count":0,"results":[]}"""))
    }

    @Test fun skipsItemsMissingVideoId() {
        val json = """{"results":[{"title":"no id here"},{"video_id":"abc","title":"ok","artist":"","duration":"","thumbnail":""}]}"""
        assertEquals(1, parseSearchResults(json).size)
    }

    @Test fun malformedJsonReturnsEmptyList() {
        assertEquals(emptyList(), parseSearchResults("not json"))
    }

    @Test fun missingResultsKeyReturnsEmptyList() {
        assertEquals(emptyList(), parseSearchResults("""{"error":"missing q"}"""))
    }
}
