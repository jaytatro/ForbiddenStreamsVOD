package com.forbiddenstreams.vod.player

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Renderers tuned for IPTV 4K files:
 * - Dolby Vision that the device can't decode (notably dual-layer profile 7 from UHD Blu-ray
 *   rips, which almost no TV box supports) is played through the HEVC decoder instead, which
 *   shows the HDR10 picture every such file carries. [forceHdr10] does this for all DV.
 * - The FFmpeg audio extension (when present) decodes TrueHD / DTS / DTS-HD that the box
 *   can't play natively.
 * - If a decoder fails to start, the next available one is tried.
 */
@OptIn(UnstableApi::class)
class VodRenderersFactory(
    context: Context,
    private val forceHdr10: Boolean,
    private val pcmAudioOnly: Boolean,
) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    /** With [pcmAudioOnly], surround formats are decoded in the app instead of passed through. */
    @Suppress("DEPRECATION")
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (!pcmAudioOnly) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        return DefaultAudioSink.Builder(context)
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            DolbyVisionFallbackRenderer(
                context = context,
                selector = mediaCodecSelector,
                joiningTimeMs = allowedVideoJoiningTimeMs,
                decoderFallback = enableDecoderFallback,
                handler = eventHandler,
                listener = eventListener,
                forceHdr10 = forceHdr10,
            ),
        )
    }
}

@OptIn(UnstableApi::class)
private class DolbyVisionFallbackRenderer(
    context: Context,
    selector: MediaCodecSelector,
    joiningTimeMs: Long,
    decoderFallback: Boolean,
    handler: Handler,
    listener: VideoRendererEventListener,
    private val forceHdr10: Boolean,
) : MediaCodecVideoRenderer(context, selector, joiningTimeMs, decoderFallback, handler, listener, 50) {

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && useHevcFor(mediaCodecSelector, format, requiresSecureDecoder)) {
            val hevc = mediaCodecSelector.getDecoderInfos(MimeTypes.VIDEO_H265, requiresSecureDecoder, false)
            if (hevc.isNotEmpty()) return hevc
        }
        return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
    }

    private fun useHevcFor(selector: MediaCodecSelector, format: Format, secure: Boolean): Boolean {
        if (forceHdr10) return true
        val profile = MediaCodecUtil.getCodecProfileAndLevel(format)?.first
        // Dual-layer profiles (4, 6, 7): the base layer is plain HEVC/HDR10.
        if (profile == CodecProfileLevel.DolbyVisionProfileDvheDtr ||
            profile == CodecProfileLevel.DolbyVisionProfileDvheDth ||
            profile == CodecProfileLevel.DolbyVisionProfileDvheDtb
        ) {
            return true
        }
        // No Dolby Vision decoder on this device that handles this file.
        val dv = selector.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, secure, false)
        return dv.none { it.isFormatSupported(format) }
    }
}

/** Bigger buffer for high-bitrate 4K, capped by how much memory the app has. */
@OptIn(UnstableApi::class)
fun vodLoadControl(): LoadControl {
    val maxMemory = Runtime.getRuntime().maxMemory()
    val targetBytes = (maxMemory / 4).coerceIn(32L * 1024 * 1024, 200L * 1024 * 1024).toInt()
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            30_000, // keep at least 30 s loaded when possible
            90_000, // up to 90 s
            2_500, // start playing after 2.5 s
            6_000, // after a stall, wait for 6 s so it doesn't stutter again right away
        )
        .setTargetBufferBytes(targetBytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}

/** Error groups used to decide how the player recovers. */
object PlaybackErrors {
    fun isDecoderError(code: Int) = code in 4000..4999
    fun isAudioError(code: Int) = code in 5000..5999
    fun isNetworkError(code: Int) = code in 2000..2999 || code == 1003 // 1003 = timeout
}
