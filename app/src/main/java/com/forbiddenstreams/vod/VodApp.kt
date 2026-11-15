package com.forbiddenstreams.vod

import android.app.Application
import android.content.Context
import com.forbiddenstreams.vod.data.Account
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.forbiddenstreams.vod.data.LocalStore
import com.forbiddenstreams.vod.data.UpdateInfo
import com.forbiddenstreams.vod.data.TrailerFinder
import com.forbiddenstreams.vod.data.UpdateManager
import com.forbiddenstreams.vod.player.MediaCache
import com.forbiddenstreams.vod.player.Prefetcher
import com.forbiddenstreams.vod.data.XtreamClient
import com.forbiddenstreams.vod.data.XtreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import android.os.Build
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

class VodApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        container = AppContainer(this)
        // A single tiny read, done up front so every screen (and the player after
        // process death) can rely on the account being known.
        runBlocking { container.store.account.first() }?.let(container::activate)
        container.forceHdr10 = runBlocking { container.store.forceHdr10.first() }
        container.matchFrameRate = runBlocking { container.store.matchFrameRate.first() }
    }
}

/**
 * Saves the stack trace of any crash to a file, so the next launch can show what went wrong
 * (there's no logcat on a TV). Android's normal crash handling still runs afterwards.
 */
private fun Application.installCrashRecorder() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            File(filesDir, CRASH_FILE).writeText(
                "Build ${BuildConfig.VERSION_CODE} on Android ${Build.VERSION.RELEASE} (${Build.MODEL})\n" +
                    "${Date()}\n\n" + error.stackTraceToString().take(12_000),
            )
        }
        previous?.uncaughtException(thread, error)
    }
}

private const val CRASH_FILE = "last_crash.txt"

/** Simple manual dependency container. */
class AppContainer(context: Context) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val store = LocalStore(context)

    val updates = UpdateManager(context, http)

    val trailers = TrailerFinder(http)

    /** Disk cache for video, and the "pre-load before Play" helper. */
    val mediaCache = MediaCache(context.applicationContext, http)
    val prefetcher by lazy { Prefetcher(mediaCache, appScope) }

    /** Set by the background check on the home screen; shows a badge on the Settings button. */
    var availableUpdate by mutableStateOf<UpdateInfo?>(null)
    var checkedForUpdates = false

    /** Settings > Playback: always play Dolby Vision as HDR10. */
    var forceHdr10 = false

    /** Settings > Playback: switch the TV to the video's frame rate. */
    var matchFrameRate = true

    /** For work that must finish even if the screen that started it closes (e.g. saving progress). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var account: Account? = null
        private set

    var repository: XtreamRepository? = null
        private set

    private val crashFile = File(context.filesDir, CRASH_FILE)

    /** The report from the last crash, if any. Reading it clears it. */
    fun takeCrashReport(): String? =
        crashFile.takeIf { it.exists() }?.let { f -> runCatching { f.readText() }.getOrNull().also { f.delete() } }

    fun activate(account: Account) {
        this.account = account
        repository = XtreamRepository(XtreamClient(http, account))
    }

    suspend fun signOut() {
        store.clearAccount()
        account = null
        repository = null
    }
}

fun Context.appContainer(): AppContainer = (applicationContext as VodApp).container
