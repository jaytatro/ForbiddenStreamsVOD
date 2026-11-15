package com.forbiddenstreams.vod.player

import android.content.Context
import android.os.StatFs
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.forbiddenstreams.vod.data.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * Disk cache for video. Everything the player streams is kept on the device, so rewinding
 * or resuming later doesn't download it again, and [Prefetcher] can load the start of a
 * video before Play is pressed.
 */
@OptIn(UnstableApi::class)
class MediaCache(context: Context, private val http: OkHttpClient) {
    private val dir = File(context.cacheDir, "video")

    /** Up to a quarter of the free space, between 512 MB and 4 GB. */
    private val maxBytes: Long = run {
        val free = runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(0L)
        (free / 4).coerceIn(512L * 1024 * 1024, 4L * 1024 * 1024 * 1024)
    }

    val cache: SimpleCache by lazy {
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), StandaloneDatabaseProvider(context))
    }

    fun dataSourceFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http).setUserAgent(USER_AGENT))
            // If the cache has a problem, just stream instead of failing.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun usedBytes(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    fun clear() {
        runCatching { cache.keys.toList().forEach { cache.removeResource(it) } }
    }
}

/** Progress of loading the start of a video ahead of time. */
data class PrefetchState(val url: String, val progress: Float, val done: Boolean)

/**
 * Loads the start of a movie / episode into [MediaCache] while its page is open, so playback
 * starts right away. Only one at a time, and always stopped before playback starts (most IPTV
 * accounts allow only one connection).
 */
@OptIn(UnstableApi::class)
class Prefetcher(private val mediaCache: MediaCache, private val scope: CoroutineScope) {
    var state by mutableStateOf<PrefetchState?>(null)
        private set

    private var job: Job? = null
    private var writer: CacheWriter? = null

    /** True while the player is open: never open a second connection then. */
    @Volatile
    var playerActive = false
        set(value) {
            field = value
            if (value) cancel()
        }

    fun prefetch(url: String, bytes: Long) {
        if (playerActive) return
        val current = state
        if (current != null && current.url == url && (current.done || job?.isActive == true)) return
        cancel()
        state = PrefetchState(url, 0f, false)
        job = scope.launch(Dispatchers.IO) {
            try {
                val spec = DataSpec.Builder().setUri(url).setPosition(0).setLength(bytes).build()
                val w = CacheWriter(mediaCache.dataSourceFactory().createDataSource(), spec, null) { requestLength, cached, _ ->
                    val total = if (requestLength > 0) requestLength else bytes
                    state = PrefetchState(url, (cached.toFloat() / total).coerceIn(0f, 1f), false)
                }
                writer = w
                w.cache()
                state = PrefetchState(url, 1f, true)
            } catch (e: Exception) {
                // Cancelled or failed: playback will simply stream as usual.
                if (state?.url == url && state?.done != true) state = null
            } finally {
                writer = null
            }
        }
    }

    /** Stop loading (whatever was loaded stays cached). */
    fun cancel() {
        writer?.cancel()
        job?.cancel()
        job = null
        if (state?.done != true) state = null
    }

    /** Stop only if it's loading [url] (e.g. leaving that page). */
    fun cancel(url: String) {
        if (state?.url == url) cancel()
    }

    companion object {
        /** From the beginning: enough for a near-instant start, even for 4K. */
        const val START_BYTES = 64L * 1024 * 1024

        /** Resuming mid-way: just the file's header. */
        const val HEADER_BYTES = 8L * 1024 * 1024
    }
}
