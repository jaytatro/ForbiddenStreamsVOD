package com.forbiddenstreams.vod.data

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.IOException

/** Sent with API calls and stream requests. Some providers filter by user agent; change it here if needed. */
const val USER_AGENT = "ForbiddenStreamsVOD/0.1 (Android)"

class XtreamException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Client for the Xtream Codes `player_api.php` API.
 *
 * Provider panels are inconsistent: numbers arrive as strings or ints, empty objects arrive
 * as `[]`, and fields go missing, so everything is parsed by hand and leniently.
 */
class XtreamClient(private val http: OkHttpClient, val account: Account) {

    private val base: HttpUrl = normalizeServer(account.server).toHttpUrlOrNull()
        ?: throw XtreamException("Invalid server URL")

    companion object {
        private const val PROGRESS_EVERY = 250

        /** Accepts "host:port", "http://host:port/", or a pasted player_api.php / get.php link. */
        fun normalizeServer(raw: String): String {
            var s = raw.trim()
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
                s = "http://$s"
            }
            s = s.substringBefore("/player_api.php").substringBefore("/get.php").substringBefore("?")
            return s.trimEnd('/')
        }
    }

    // ---- API ----------------------------------------------------------------------------

    suspend fun authenticate(): AuthInfo {
        val root = api(null).obj() ?: throw XtreamException("This doesn't look like an Xtream Codes server")
        val user = root["user_info"].obj()
            ?: throw XtreamException("This doesn't look like an Xtream Codes server")
        if (user.int("auth") != 1) throw XtreamException("Login failed. Check the username and password.")
        val status = user.str("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw XtreamException("Account status: $status")
        }
        return AuthInfo(
            status = status,
            expiresAtEpochSec = user.long("exp_date"),
            maxConnections = user.int("max_connections"),
            activeConnections = user.int("active_cons"),
        )
    }

    suspend fun vodCategories(): List<Category> =
        api("get_vod_categories").list().mapNotNull { it.obj()?.toCategory() }

    suspend fun seriesCategories(): List<Category> =
        api("get_series_categories").list().mapNotNull { it.obj()?.toCategory() }

    /**
     * Movies in one category, or the whole catalog when [categoryId] is null.
     * [onProgress] receives the running count of titles read so far.
     */
    suspend fun movies(categoryId: String? = null, onProgress: ((Int, Float?) -> Unit)? = null): List<Movie> =
        streamList("get_vod_streams", *categoryParam(categoryId), onProgress = onProgress) { it.toMovie() }

    /** Series in one category, or the whole catalog when [categoryId] is null. */
    suspend fun series(categoryId: String? = null, onProgress: ((Int, Float?) -> Unit)? = null): List<Series> =
        streamList("get_series", *categoryParam(categoryId), onProgress = onProgress) { it.toSeries() }

    private fun categoryParam(categoryId: String?): Array<Pair<String, String>> =
        if (categoryId == null) emptyArray() else arrayOf("category_id" to categoryId)

    suspend fun movieDetails(id: Int): MovieDetails {
        val root = api("get_vod_info", "vod_id" to id.toString()).obj()
            ?: throw XtreamException("Movie not found")
        val info = root["info"].obj() ?: JsonObject(emptyMap())
        val data = root["movie_data"].obj() ?: JsonObject(emptyMap())
        val movie = Movie(
            streamId = data.int("stream_id") ?: id,
            name = data.str("name") ?: info.str("name") ?: "Untitled",
            poster = info.str("movie_image") ?: info.str("cover_big"),
            rating = info.str("rating"),
            extension = data.str("container_extension") ?: "mp4",
            categoryId = data.str("category_id"),
        )
        return MovieDetails(
            movie = movie,
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            cast = info.str("cast") ?: info.str("actors"),
            director = info.str("director"),
            releaseDate = info.str("releasedate") ?: info.str("release_date"),
            durationSecs = info.int("duration_secs") ?: parseDuration(info.str("duration")),
            backdrop = info.firstString("backdrop_path"),
            youtubeTrailer = info.str("youtube_trailer") ?: info.str("trailer"),
        )
    }

    suspend fun seriesDetails(id: Int): SeriesDetails {
        val root = api("get_series_info", "series_id" to id.toString()).obj()
            ?: throw XtreamException("Series not found")
        val info = root["info"].obj() ?: JsonObject(emptyMap())
        val series = Series(
            seriesId = id,
            name = info.str("name") ?: "Untitled",
            cover = info.str("cover"),
            rating = info.str("rating"),
            plot = info.str("plot"),
            categoryId = info.str("category_id"),
            genre = info.str("genre"),
            releaseDate = info.str("releaseDate") ?: info.str("release_date"),
            cast = info.str("cast"),
            backdrop = info.firstString("backdrop_path"),
        )

        val episodes = mutableListOf<Episode>()
        when (val eps = root["episodes"]) {
            // Usual shape: { "1": [ep, ep], "2": [...] }
            is JsonObject -> eps.forEach { (seasonKey, seasonEpisodes) ->
                seasonEpisodes.list().forEach { el -> el.obj()?.toEpisode(seasonKey.toIntOrNull())?.let(episodes::add) }
            }
            // Some panels send [[ep, ep], [ep]] or a flat [ep, ep].
            is JsonArray -> eps.forEach { el ->
                when (el) {
                    is JsonArray -> el.forEach { e -> e.obj()?.toEpisode(null)?.let(episodes::add) }
                    is JsonObject -> el.toEpisode(null)?.let(episodes::add)
                    else -> Unit
                }
            }
            else -> Unit
        }

        val seasons = episodes
            .distinctBy { it.id }
            .groupBy { it.season }
            .toSortedMap()
            .mapValues { (_, list) -> list.sortedBy { it.number } }
        return SeriesDetails(series, seasons)
    }

    // ---- Stream URLs ----------------------------------------------------------------------

    fun movieUrl(streamId: String, extension: String): String = streamUrl("movie", streamId, extension)

    fun episodeUrl(episodeId: String, extension: String): String = streamUrl("series", episodeId, extension)

    private fun streamUrl(type: String, id: String, extension: String): String =
        base.newBuilder()
            .addPathSegment(type)
            .addPathSegment(account.username)
            .addPathSegment(account.password)
            .addPathSegment("$id.$extension")
            .build()
            .toString()

    // ---- Plumbing -------------------------------------------------------------------------

    /**
     * Reads a (possibly huge) list response item by item straight from the network, so a
     * catalog of tens of thousands of titles never sits in memory as one big string / tree.
     * Accepts a JSON array, or an object whose values are the items (some panels do that).
     */
    private suspend fun <T> streamList(
        action: String,
        vararg params: Pair<String, String>,
        onProgress: ((Int, Float?) -> Unit)? = null,
        map: (JsonObject) -> T?,
    ): List<T> = withContext(Dispatchers.IO) {
        var bytesRead = 0L
        var totalBytes = -1L
        fun fraction(): Float? = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else null
        fun add(out: ArrayList<T>, item: T?) {
            if (item == null) return
            out += item
            if (out.size % PROGRESS_EVERY == 0) {
                ensureActive() // stop reading if the user cancelled
                onProgress?.invoke(out.size, fraction())
            }
        }
        val request = Request.Builder().url(apiUrl(action, *params)).header("User-Agent", USER_AGENT).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw XtreamException("Server returned HTTP ${response.code}")
                val body = response.body ?: return@use emptyList()
                val out = ArrayList<T>()
                // Count bytes so progress can be a percentage when the server sends a size.
                totalBytes = body.contentLength()
                val counting = object : FilterInputStream(body.byteStream()) {
                    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        super.read(b, off, len).also { if (it > 0) bytesRead += it }
                }
                try {
                    JsonReader(InputStreamReader(counting, Charsets.UTF_8).buffered()).use { reader ->
                        val first = try {
                            reader.peek()
                        } catch (e: EOFException) {
                            JsonToken.END_DOCUMENT // empty body = empty list
                        }
                        when (first) {
                            JsonToken.BEGIN_ARRAY -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    add(out, (readElement(reader) as? JsonObject)?.let(map))
                                }
                                reader.endArray()
                            }
                            JsonToken.BEGIN_OBJECT -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    reader.nextName()
                                    add(out, (readElement(reader) as? JsonObject)?.let(map))
                                }
                                reader.endObject()
                            }
                            JsonToken.END_DOCUMENT -> Unit
                            else -> throw XtreamException("Unexpected response from the server")
                        }
                    }
                } catch (e: XtreamException) {
                    throw e
                } catch (e: IOException) {
                    // Malformed JSON (e.g. an HTML error page) or the connection dropped mid-list.
                    throw XtreamException("The server sent an incomplete or invalid catalog. Please try again.", e)
                }
                onProgress?.invoke(out.size, 1f)
                out
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: IllegalStateException) {
            throw XtreamException("Unexpected response from the server", e)
        } catch (e: IOException) {
            // Includes malformed JSON (e.g. an HTML error page).
            throw XtreamException(e.message ?: "Network error", e)
        }
    }

    /** Reads one JSON value (an item is small) into a kotlinx JsonElement. */
    private fun readElement(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val map = LinkedHashMap<String, JsonElement>()
            reader.beginObject()
            while (reader.hasNext()) map[reader.nextName()] = readElement(reader)
            reader.endObject()
            JsonObject(map)
        }
        JsonToken.BEGIN_ARRAY -> {
            val list = ArrayList<JsonElement>()
            reader.beginArray()
            while (reader.hasNext()) list += readElement(reader)
            reader.endArray()
            JsonArray(list)
        }
        JsonToken.STRING, JsonToken.NUMBER -> JsonPrimitive(reader.nextString())
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> {
            reader.nextNull()
            JsonNull
        }
        else -> {
            reader.skipValue()
            JsonNull
        }
    }

    private fun apiUrl(action: String?, vararg params: Pair<String, String>): HttpUrl =
        base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
            .apply {
                if (action != null) addQueryParameter("action", action)
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()

    private suspend fun api(action: String?, vararg params: Pair<String, String>): JsonElement =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(apiUrl(action, *params)).header("User-Agent", USER_AGENT).build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw XtreamException("Server returned HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use JsonArray(emptyList())
                    val parsed = Json.parseToJsonElement(body)
                    // An HTML error page or a bare string is not a valid API answer.
                    if (parsed !is JsonObject && parsed !is JsonArray) {
                        throw XtreamException("Unexpected response from the server")
                    }
                    parsed
                }
            } catch (e: XtreamException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization reports malformed JSON as SerializationException (an IllegalArgumentException).
                throw XtreamException("Unexpected response from the server", e)
            } catch (e: IOException) {
                throw XtreamException(e.message ?: "Network error", e)
            }
        }

    private fun JsonObject.toCategory(): Category? {
        val id = str("category_id") ?: return null
        return Category(id, str("category_name") ?: "Unnamed")
    }

    private fun JsonObject.toMovie(): Movie? {
        val id = int("stream_id") ?: return null
        return Movie(
            streamId = id,
            name = str("name") ?: "Untitled",
            poster = str("stream_icon"),
            rating = str("rating"),
            extension = str("container_extension") ?: "mp4",
            categoryId = str("category_id"),
            added = long("added"),
            adult = flag("is_adult"),
        )
    }

    private fun JsonObject.toSeries(): Series? {
        val id = int("series_id") ?: return null
        return Series(
            seriesId = id,
            name = str("name") ?: "Untitled",
            cover = str("cover"),
            rating = str("rating"),
            plot = str("plot"),
            categoryId = str("category_id"),
            genre = str("genre"),
            releaseDate = str("releaseDate") ?: str("release_date"),
            cast = str("cast"),
            backdrop = firstString("backdrop_path"),
            added = long("last_modified"),
            adult = flag("is_adult"),
        )
    }

    private fun JsonObject.toEpisode(fallbackSeason: Int?): Episode? {
        val id = str("id") ?: return null
        val info = this["info"].obj()
        return Episode(
            id = id,
            season = int("season") ?: fallbackSeason ?: 1,
            number = int("episode_num") ?: 0,
            title = str("title") ?: "Episode",
            extension = str("container_extension") ?: "mp4",
            plot = info?.str("plot"),
            image = info?.str("movie_image") ?: info?.str("cover_big"),
            durationSecs = info?.int("duration_secs") ?: parseDuration(info?.str("duration")),
        )
    }
}

// ---- Lenient JSON helpers -----------------------------------------------------------------

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.list(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> this.values.toList()
    else -> emptyList()
}

internal fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.trim().takeIf { it.isNotEmpty() && it != "null" }
}

internal fun JsonObject.int(key: String): Int? =
    str(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

internal fun JsonObject.flag(key: String): Boolean =
    str(key)?.lowercase()?.let { it == "1" || it == "true" } ?: false

internal fun JsonObject.long(key: String): Long? =
    str(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

/** Reads a field that may be a string or an array of strings (e.g. backdrop_path). */
internal fun JsonObject.firstString(key: String): String? = when (val v = this[key]) {
    is JsonArray -> v.firstNotNullOfOrNull { el ->
        (el as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
    is JsonPrimitive -> str(key)
    else -> null
}

/** "01:45:00" or "45:00" -> seconds. */
internal fun parseDuration(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    val parts = value.trim().split(":")
    var total = 0
    for (part in parts) {
        val n = part.trim().toIntOrNull() ?: return null
        total = total * 60 + n
    }
    return total.takeIf { it > 0 }
}
