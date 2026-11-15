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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
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

        /** No new data arriving for this long while buffering = connection stalled. */
        private const val NO_DATA_STALL_MS = 25_000L

        /** "Buffering" with this much video already loaded = the decoder is stuck, not the network. */
        private const val LOADED_BUT_STUCK_AHEAD_MS = 8_000L
        private const val LOADED_BUT_STUCK_MS = 10_000L

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

    /**
     * Ways to decode Dolby Vision, tried in order if one gets stuck. The setting
     * "Always play Dolby Vision as HDR10" starts with HDR10.
     */
    private var dvModes = listOf(DvMode.AUTO, DvMode.HDR10, DvMode.NATIVE)
    private var dvModeIndex = 0
    private val dvMode get() = dvModes[dvModeIndex]
    private fun hasNextDvMode() = dvModeIndex < dvModes.lastIndex

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
    private var lastBuffered = -1L
    private var lastBufferedChange = 0L
    private var bandwidthBps = 0L
    private var displayModeSwitched = false

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
        if (appContainer().forceHdr10) dvModes = listOf(DvMode.HDR10, DvMode.NATIVE)

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

        // OkHttp (shared with the rest of the app) streams faster and copes better with
        // IPTV servers than Android's basic HTTP stack.
        val httpFactory = OkHttpDataSource.Factory(appContainer().http).setUserAgent(USER_AGENT)

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
        val exo = ExoPlayer.Builder(this, VodRenderersFactory(this, dvMode, pcmAudioOnly))
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
                    PlaybackErrors.isDecoderError(code) && hasNextDvMode() -> {
                        dvModeIndex++
                        restart("Trying another way to decode this video (${dvMode.label})…")
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

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                bandwidthBps = bitrateEstimate
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                matchDisplayMode(format)
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
        handler.removeCallbacksAndMessages(null)
        displayModeSwitched = false
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
        lastBuffered = -1L
        lastBufferedChange = now
    }

    /**
     * Catches the stalls that don't raise an error and adapts:
     * - loading but no data arriving -> reconnect;
     * - plenty of video loaded but still "buffering", or the picture/time frozen -> the
     *   decoder is stuck: try the next Dolby Vision decoding mode, then in-app audio;
     * - if nothing works, explain and show the playback details.
     */
    private fun checkForStall() {
        val exo = player ?: return
        val now = SystemClock.elapsedRealtime()
        val position = exo.currentPosition
        if (position != lastPosition) {
            lastPosition = position
            lastPositionChange = now
        }
        val buffered = exo.bufferedPosition
        if (buffered != lastBuffered) {
            lastBuffered = buffered
            lastBufferedChange = now
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
            val ahead = buffered - position
            when {
                ahead >= LOADED_BUT_STUCK_AHEAD_MS && now - bufferingSince > LOADED_BUT_STUCK_MS ->
                    decoderStuck("Video loaded (${ahead / 1000}s ahead) but not playing")
                now - lastBufferedChange > NO_DATA_STALL_MS -> {
                    lastIssue = "No data from the provider for ${NO_DATA_STALL_MS / 1000}s"
                    if (reconnects < 3) {
                        reconnects++
                        restart("No data from your provider, reconnecting…")
                    } else {
                        showFailure("Your provider stopped sending this video.")
                    }
                }
            }
            return
        }
        bufferingSince = 0L
        if (exo.playbackState != Player.STATE_READY || !exo.playWhenReady || displayModeSwitched) {
            // Paused, not started, or the TV is switching frame rate: nothing to watch.
            lastPositionChange = now
            lastFrameChange = now
            return
        }

        when {
            now - lastPositionChange > FROZEN_MS -> decoderStuck("Playback froze (time not moving)")
            exo.videoFormat != null && frames >= 0 && now - lastFrameChange > PICTURE_FROZEN_MS ->
                decoderStuck("Picture froze (no new video frames)")
        }
    }

    /** Move to the next way of decoding, or give up with details. */
    private fun decoderStuck(issue: String) {
        lastIssue = "$issue  [decoder ${videoDecoder ?: "-"}, mode ${dvMode.label}]"
        val exo = player ?: return
        when {
            isDolbyVision(exo.videoFormat) && hasNextDvMode() -> {
                dvModeIndex++
                restart("Video stuck, trying another way to decode it (${dvMode.label})…")
            }
            !pcmAudioOnly -> {
                pcmAudioOnly = true
                restart("Video stuck, switching audio mode…")
            }
            else -> showFailure("This video won't play on this device.")
        }
    }

    // ---- Match the TV's refresh rate to the video ---------------------------------------------

    /**
     * Switches the TV to a refresh rate that fits the video's frame rate (e.g. 23.976 Hz for
     * films), like Kodi's "adjust display refresh rate". Keeps the current resolution.
     */
    private fun matchDisplayMode(format: Format) {
        if (!appContainer().matchFrameRate) return
        val fps = format.frameRate
        if (fps <= 0f) return
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        // Best: refresh rate equal to the frame rate, then 2x, 3x... (e.g. 25 fps -> 50 Hz).
        val best = (1..5).firstNotNullOfOrNull { multiple ->
            candidates.firstOrNull { kotlin.math.abs(it.refreshRate - fps * multiple) < 0.05f }
        } ?: return
        if (best.modeId == current.modeId) return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        // Most TVs blank for a moment while switching; pause so nothing is missed.
        val exo = player ?: return
        if (exo.playWhenReady) {
            displayModeSwitched = true
            exo.playWhenReady = false
            handler.postDelayed({
                displayModeSwitched = false
                resetWatchdog()
                player?.playWhenReady = true
            }, 2_500)
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
            appendLine("Buffer  ${buffered?.let { "%.1f s ahead".format(it) } ?: "-"}   state ${stateName(exo?.playbackState)}")
            appendLine("Network ${if (bandwidthBps > 0) "%.1f Mbps".format(bandwidthBps / 1_000_000.0) else "measuring…"}   video bitrate ${v?.bitrate?.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1_000_000.0) } ?: "unknown"}")
            appendLine("Display ${currentDisplayMode()}")
            appendLine("Mode    Dolby Vision: ${dvMode.label}   in-app audio: ${if (pcmAudioOnly) "on" else "auto"}")
            appendLine("Device  ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            lastIssue?.let { append("Issue   ").append(it) }
        }
    }

    private fun stateName(state: Int?) = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "playing/ready"
        Player.STATE_ENDED -> "ended"
        else -> "-"
    }

    private fun currentDisplayMode(): String {
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return "-"
        val m = display.mode
        return "${m.physicalWidth}x${m.physicalHeight} @ ${"%.3f".format(m.refreshRate)} Hz"
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
