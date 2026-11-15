package com.forbiddenstreams.vod.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A YouTube video that can be played with the in-app trailer player. */
data class Trailer(
    val videoId: String,
    val title: String,
) {
    val thumbnail: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}

/**
 * Finds trailers for a movie: the provider's own `youtube_trailer` first, then the top
 * results of a YouTube search for "<title> <year> official trailer".
 */
class TrailerFinder(private val http: OkHttpClient) {

    suspend fun find(title: String, year: String?, providerTrailer: String?): List<Trailer> {
        val results = mutableListOf<Trailer>()
        youtubeId(providerTrailer)?.let { results += Trailer(it, "Official trailer") }
        val query = listOfNotNull(cleanTitle(title), year, "official trailer").joinToString(" ")
        val found = try {
            search(query)
        } catch (e: IOException) {
            emptyList()
        }
        results += found
        return results.distinctBy { it.videoId }.take(MAX_TRAILERS)
    }

    private suspend fun search(query: String): List<Trailer> = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/results".toHttpUrl().newBuilder()
            .addQueryParameter("search_query", query)
            .addQueryParameter("hl", "en")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            // Skip the EU consent page.
            .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube returned HTTP ${response.code}")
            parseSearchPage(response.body?.string().orEmpty())
        }
    }

    companion object {
        private const val MAX_TRAILERS = 6
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private val ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val ID_IN_URL = Regex("""(?:v=|youtu\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})""")

        /** Accepts a bare video id or any YouTube URL. */
        fun youtubeId(value: String?): String? {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            if (ID.matches(v)) return v
            return ID_IN_URL.find(v)?.groupValues?.get(1)
        }

        /** "EN - The Matrix (1999) [4K]" -> "The Matrix". */
        fun cleanTitle(raw: String): String = raw
            .replace(Regex("""^\s*(\|[^|]*\||[A-Z]{2,4}\s*[-:|])\s*"""), "") // "EN - ", "|EN| "
            .replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ") // [4K], (1999)
            .replace(Regex("""\b(4K|UHD|FHD|HD|SD|HEVC|MULTI|SUB|DUB|VOSTFR)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        /**
         * Pulls videos out of a YouTube search page (the `ytInitialData` JSON it embeds).
         * Results whose title mentions "trailer" come first.
         */
        fun parseSearchPage(html: String): List<Trailer> {
            val start = html.indexOf("ytInitialData")
            if (start < 0) return emptyList()
            val jsonStart = html.indexOf('{', start)
            val jsonEnd = html.indexOf(";</script>", jsonStart)
            if (jsonStart < 0 || jsonEnd < 0) return emptyList()
            val root = try {
                Json.parseToJsonElement(html.substring(jsonStart, jsonEnd))
            } catch (e: IllegalArgumentException) {
                return emptyList()
            }
            val videos = mutableListOf<Trailer>()
            collectVideos(root, videos)
            return videos
                .distinctBy { it.videoId }
                .sortedByDescending { it.title.contains("trailer", ignoreCase = true) }
        }

        private fun collectVideos(element: JsonElement, out: MutableList<Trailer>) {
            if (out.size >= 20) return
            when (element) {
                is JsonObject -> {
                    val renderer = element["videoRenderer"] as? JsonObject
                    if (renderer != null) {
                        val id = renderer.str("videoId")
                        val title = (renderer["title"] as? JsonObject)?.let { t ->
                            (t["runs"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.str("text") }
                                ?: t.str("simpleText")
                        }
                        if (id != null && ID.matches(id)) out += Trailer(id, title ?: "Trailer")
                    }
                    element.values.forEach { collectVideos(it, out) }
                }
                is JsonArray -> element.forEach { collectVideos(it, out) }
                else -> Unit
            }
        }
    }
}
