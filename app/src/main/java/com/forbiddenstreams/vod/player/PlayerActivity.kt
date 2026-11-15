package com.forbiddenstreams.vod.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.ResumeEntry
import com.forbiddenstreams.vod.data.USER_AGENT
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full-screen ExoPlayer for movies and episodes.
 *
 * - Saves the position every few seconds and when leaving, so "Continue watching" works.
 * - Recovers instead of quitting: decoder errors retry as HDR10, audio errors retry with
 *   in-app audio decoding, dropped connections reconnect, and silent freezes are detected
 *   by a watchdog and handled the same way.
 * - Info / Menu on the remote shows playback details (codec, decoder, dropped frames).
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

        /** Position not moving while "playing" for this long = frozen. */
        private const val FROZEN_MS = 6_000L

        /** Position moving but no new video frames for this long = picture frozen. */
        private const val PICTURE_FROZEN_MS = 5_000L

        /** Buffering without progress for this long = connection stalled. */
        private const val BUFFERING_STALL_MS = 30_000L

        private const val GREEN = 0xFF90E15D.toInt()

        private val json = Json { ignoreUnknownKeys = true }

        fun intent(context: Context, url: String, entry: ResumeEntry, accountId: String, startMs: Long): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_ENTRY, json.encodeToString(entry))
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                .putExtra(EXTRA_START_MS, startMs)
    }

    private lateinit var playerView: PlayerView
    private lateinit var infoView: TextView
    private lateinit var entry: ResumeEntry
    private lateinit var url: String
    private lateinit var accountId: String

    private var player: ExoPlayer? = null
    private var resumePositionMs = 0L
    private var playWhenReady = true
    private var ended = false
    private var failedDialogShown = false

    /** Play Dolby Vision as HDR10 (setting, or switched on automatically after trouble). */
    private var forceHdr10 = false

    /** Decode surround audio in the app instead of passing it through (after trouble). */
    private var pcmAudioOnly = false
    private var reconnects = 0

    // Details for the info screen
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var lastIssue: String? = null

    // Watchdog state
    private var lastPosition = -1L
    private var lastPositionChange = 0L
    private var lastFrames = -1
    private var lastFrameChange = 0L
    private var bufferingSince = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val saveTicker = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            checkForStall()
            if (infoView.visibility == View.VISIBLE) infoView.text = playbackInfo()
            handler.postDelayed(this, 1_000)
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
        // Green progress bar / scrubber to match the app (buttons turn green via the theme).
        playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(GREEN)
            setScrubberColor(GREEN)
            setBufferedColor(0x8890E15D.toInt())
        }

        infoView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(0xCC000000.toInt())
            setPadding(28, 20, 28, 20)
            visibility = View.GONE
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                infoView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START,
                ).apply { setMargins(32, 32, 32, 32) },
            )
        }
        setContentView(root)
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

    /** Remote keys: Info / Menu toggles the info screen; everything else goes to the player. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val infoKeys = setOf(KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED)
        if (::infoView.isInitialized && event.keyCode in infoKeys) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleInfo()
            return true
        }
        return (::playerView.isInitialized && playerView.dispatchKeyEvent(event)) || super.dispatchKeyEvent(event)
    }

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
                    .setExceedAudioConstraintsIfNecessary(true)
                    // Prefer the lighter Dolby Digital / AAC tracks over TrueHD / DTS-HD when a
                    // file has both: far fewer playback problems on TV boxes.
                    .setPreferredAudioMimeTypes(
                        MimeTypes.AUDIO_E_AC3_JOC,
                        MimeTypes.AUDIO_E_AC3,
                        MimeTypes.AUDIO_AC3,
                        MimeTypes.AUDIO_AAC,
                    ),
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
                lastIssue = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
                val code = error.errorCode
                when {
                    PlaybackErrors.isDecoderError(code) && !forceHdr10 -> {
                        forceHdr10 = true
                        restart("Switching to HDR10 for this video…")
                    }
                    PlaybackErrors.isAudioError(code) && !pcmAudioOnly -> {
                        pcmAudioOnly = true
                        restart("Switching audio mode…")
                    }
                    PlaybackErrors.isNetworkError(code) && reconnects < 3 -> {
                        reconnects++
                        restart("Connection dropped, reconnecting…")
                    }
                    else -> showFailure("Can't play this stream (${error.errorCodeName}).")
                }
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                audioDecoder = decoderName
            }
        })

        exo.setMediaItem(mediaItem, resumePositionMs)
        exo.playWhenReady = playWhenReady
        exo.prepare()

        playerView.player = exo
        playerView.requestFocus()
        player = exo
        resetWatchdog()
        handler.postDelayed(saveTicker, SAVE_INTERVAL_MS)
        handler.postDelayed(watchdog, 1_000)
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
        handler.removeCallbacks(watchdog)
        val exo = player ?: return
        savePosition()
        resumePositionMs = exo.currentPosition
        playWhenReady = exo.playWhenReady
        playerView.player = null
        exo.release()
        player = null
        videoDecoder = null
        audioDecoder = null
    }

    // ---- Freeze detection ------------------------------------------------------------------

    private fun resetWatchdog() {
        val now = SystemClock.elapsedRealtime()
        lastPosition = -1L
        lastPositionChange = now
        lastFrames = -1
        lastFrameChange = now
        bufferingSince = 0L
    }

    /**
     * Catches the freezes that don't raise an error (common with 4K Dolby Vision on TV
     * boxes) and recovers: audio mode first, then HDR10, then explains what happened.
     */
    private fun checkForStall() {
        val exo = player ?: return
        val now = SystemClock.elapsedRealtime()
        val position = exo.currentPosition
        if (position != lastPosition) {
            lastPosition = position
            lastPositionChange = now
        }
        val counters = exo.videoDecoderCounters
        counters?.ensureUpdated()
        val frames = counters?.renderedOutputBufferCount ?: -1
        if (frames != lastFrames) {
            lastFrames = frames
            lastFrameChange = now
        }

        if (exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady) {
            if (bufferingSince == 0L) bufferingSince = now
            if (now - bufferingSince > BUFFERING_STALL_MS) {
                lastIssue = "Stuck buffering for ${BUFFERING_STALL_MS / 1000}s"
                if (reconnects < 3) {
                    reconnects++
                    restart("Still loading, reconnecting…")
                } else {
                    showFailure("Your provider isn't sending this video fast enough.")
                }
            }
            return
        }
        bufferingSince = 0L
        if (exo.playbackState != Player.STATE_READY || !exo.playWhenReady) {
            // Paused or not started: nothing to watch.
            lastPositionChange = now
            lastFrameChange = now
            return
        }

        val positionFrozen = now - lastPositionChange > FROZEN_MS
        val pictureFrozen = !positionFrozen && exo.videoFormat != null && frames >= 0 &&
            now - lastFrameChange > PICTURE_FROZEN_MS
        when {
            positionFrozen -> {
                lastIssue = "Playback froze (position not moving)"
                when {
                    !pcmAudioOnly -> {
                        pcmAudioOnly = true
                        restart("Playback froze, switching audio mode…")
                    }
                    !forceHdr10 && isDolbyVision(exo.videoFormat) -> {
                        forceHdr10 = true
                        restart("Playback froze, switching to HDR10…")
                    }
                    else -> showFailure("This video keeps freezing on this device.")
                }
            }
            pictureFrozen -> {
                lastIssue = "Picture froze (no new video frames)"
                if (!forceHdr10) {
                    forceHdr10 = true
                    restart("Picture froze, switching to HDR10…")
                } else {
                    showFailure("The picture keeps freezing on this device.")
                }
            }
        }
    }

    private fun dynamicRange(format: Format?): String = when {
        format == null -> ""
        isDolbyVision(format) -> "Dolby Vision"
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR"
    }

    private fun isDolbyVision(format: Format?) = format?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION

    // ---- Info screen -------------------------------------------------------------------------

    private fun toggleInfo() {
        if (infoView.visibility == View.VISIBLE) {
            infoView.visibility = View.GONE
        } else {
            infoView.text = playbackInfo()
            infoView.visibility = View.VISIBLE
        }
    }

    private fun playbackInfo(): String {
        val exo = player
        val v = exo?.videoFormat
        val a = exo?.audioFormat
        val counters = exo?.videoDecoderCounters?.also { it.ensureUpdated() }
        val buffered = exo?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0) / 1000.0 }
        return buildString {
            appendLine("PLAYBACK INFO  (Info / Menu to close)")
            appendLine("Video   ${v?.sampleMimeType ?: "-"}  ${v?.codecs ?: ""}")
            appendLine("        ${v?.width ?: 0}x${v?.height ?: 0}  ${v?.frameRate?.takeIf { it > 0 }?.let { "%.3f fps".format(it) } ?: ""}  ${dynamicRange(v)}")
            appendLine("Decoder ${videoDecoder ?: "-"}")
            appendLine("Frames  shown ${counters?.renderedOutputBufferCount ?: 0}  dropped ${counters?.droppedBufferCount ?: 0}")
            appendLine("Audio   ${a?.sampleMimeType ?: "-"}  ${a?.channelCount ?: 0}ch  ${a?.sampleRate ?: 0}Hz")
            appendLine("        ${audioDecoder ?: "passthrough / none"}")
            appendLine("Buffer  ${buffered?.let { "%.1f s ahead".format(it) } ?: "-"}")
            appendLine("Mode    DV as HDR10: ${if (forceHdr10) "on" else "auto"}   in-app audio: ${if (pcmAudioOnly) "on" else "auto"}")
            appendLine("Device  ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            lastIssue?.let { append("Issue   ").append(it) }
        }
    }

    /** Last resort: stop, and show what happened so it can be reported. */
    private fun showFailure(message: String) {
        if (failedDialogShown || isFinishing) return
        failedDialogShown = true
        player?.pause()
        val details = playbackInfo()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(message)
            .setMessage(
                "If this is a 4K Dolby Vision movie, try Settings > Playback > " +
                    "\"Always play Dolby Vision as HDR10\".\n\nTake a photo of this to report it:\n\n$details",
            )
            .setPositiveButton("Close") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // ---- Progress saving ----------------------------------------------------------------------

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
