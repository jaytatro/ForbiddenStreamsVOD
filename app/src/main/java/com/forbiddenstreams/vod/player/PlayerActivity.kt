package com.forbiddenstreams.vod.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.ResumeEntry
import com.forbiddenstreams.vod.data.USER_AGENT
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full-screen ExoPlayer for movies and episodes.
 * Saves the position every few seconds and when leaving, so "Continue watching" works.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_ENTRY = "entry"
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_START_MS = "start_ms"

        private const val SAVE_INTERVAL_MS = 10_000L
        private const val MIN_SAVE_POSITION_MS = 15_000L
        private const val FINISHED_FRACTION = 0.95

        private val json = Json { ignoreUnknownKeys = true }

        fun intent(context: Context, url: String, entry: ResumeEntry, accountId: String, startMs: Long): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_ENTRY, json.encodeToString(entry))
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                .putExtra(EXTRA_START_MS, startMs)
    }

    private lateinit var playerView: PlayerView
    private lateinit var entry: ResumeEntry
    private lateinit var url: String
    private lateinit var accountId: String

    private var player: ExoPlayer? = null
    private var resumePositionMs = 0L
    private var playWhenReady = true
    private var ended = false

    /** Play Dolby Vision as HDR10 (setting, or switched on automatically after a decoder error). */
    private var forceHdr10 = false

    /** Decode surround audio in the app instead of passing it through (after an audio error). */
    private var pcmAudioOnly = false
    private var reconnects = 0

    private val handler = Handler(Looper.getMainLooper())
    private val saveTicker = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryJson = intent.getStringExtra(EXTRA_ENTRY)
        val urlExtra = intent.getStringExtra(EXTRA_URL)
        if (entryJson == null || urlExtra == null) {
            finish()
            return
        }
        entry = json.decodeFromString(ResumeEntry.serializer(), entryJson)
        url = urlExtra
        accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
        resumePositionMs = savedInstanceState?.getLong(EXTRA_START_MS)
            ?: intent.getLongExtra(EXTRA_START_MS, 0L)

        forceHdr10 = appContainer().forceHdr10
        playerView = PlayerView(this).apply {
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerAutoShow = true
        }
        setContentView(playerView)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (::entry.isInitialized) initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(EXTRA_START_MS, player?.currentPosition ?: resumePositionMs)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Route remote / keyboard keys to the player so D-pad and media keys work on Android TV. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (::playerView.isInitialized && playerView.dispatchKeyEvent(event)) || super.dispatchKeyEvent(event)

    private fun initializePlayer() {
        if (player != null) return

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true),
            )
        }
        val exo = ExoPlayer.Builder(this, VodRenderersFactory(this, forceHdr10, pcmAudioOnly))
            .setTrackSelector(trackSelector)
            .setLoadControl(vodLoadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .build()

        val title = listOfNotNull(entry.title, entry.subtitle).joinToString(" · ")
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) reconnects = 0
                if (state == Player.STATE_ENDED) {
                    ended = true
                    clearPosition()
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val code = error.errorCode
                when {
                    // Video decoder trouble (typically 4K Dolby Vision): retry showing HDR10.
                    PlaybackErrors.isDecoderError(code) && !forceHdr10 -> {
                        forceHdr10 = true
                        restart("Switching to HDR10 for this video…")
                    }
                    // Audio output trouble (TrueHD / DTS passthrough): decode the audio in the app.
                    PlaybackErrors.isAudioError(code) && !pcmAudioOnly -> {
                        pcmAudioOnly = true
                        restart("Switching audio mode…")
                    }
                    // Dropped connection: reconnect and carry on from the same spot.
                    PlaybackErrors.isNetworkError(code) && reconnects < 3 -> {
                        reconnects++
                        restart("Connection dropped, reconnecting…")
                    }
                    else -> {
                        Toast.makeText(
                            this@PlayerActivity,
                            "Can't play this stream (${error.errorCodeName})",
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                }
            }
        })

        exo.setMediaItem(mediaItem, resumePositionMs)
        exo.playWhenReady = playWhenReady
        exo.prepare()

        playerView.player = exo
        playerView.requestFocus()
        player = exo
        handler.postDelayed(saveTicker, SAVE_INTERVAL_MS)
    }

    /** Rebuilds the player with the current settings and continues from the same position. */
    private fun restart(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        handler.post {
            releasePlayer()
            initializePlayer()
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(saveTicker)
        val exo = player ?: return
        savePosition()
        resumePositionMs = exo.currentPosition
        playWhenReady = exo.playWhenReady
        playerView.player = null
        exo.release()
        player = null
    }

    private fun savePosition() {
        val exo = player ?: return
        if (accountId.isEmpty() || ended) return
        val position = exo.currentPosition
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val store = appContainer().store
        val scope = appContainer().appScope

        when {
            duration > 0 && position >= duration * FINISHED_FRACTION -> clearPosition()
            position >= MIN_SAVE_POSITION_MS -> {
                val updated = entry.copy(
                    positionMs = position,
                    durationMs = duration,
                    updatedAt = System.currentTimeMillis(),
                )
                scope.launch { store.saveResume(accountId, updated) }
            }
        }
    }

    private fun clearPosition() {
        if (accountId.isEmpty()) return
        val store = appContainer().store
        appContainer().appScope.launch { store.removeResume(accountId, entry.key) }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
