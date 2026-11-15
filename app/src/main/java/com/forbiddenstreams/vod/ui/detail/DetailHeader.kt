package com.forbiddenstreams.vod.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.ui.Brand

/** Backdrop, poster, title, metadata and action buttons shared by the movie and series screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailHeader(
    title: String,
    backdrop: String?,
    poster: String?,
    meta: String?,
    plot: String?,
    credits: List<Pair<String, String?>>,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = backdrop ?: poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .alpha(0.45f),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, background), startY = 0f, endY = 700f)),
        )
        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            // Poster on the left; title, details, description and Play button to its right.
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(180.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = Brand.Bone,
                    )
                    if (!meta.isNullOrBlank()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Brand.GlowGreen,
                        )
                    }
                    if (!plot.isNullOrBlank()) {
                        Text(
                            plot,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    credits.forEach { (label, value) ->
                        if (!value.isNullOrBlank()) {
                            Text(
                                "$label: $value",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        actions()
                    }
                }
            }
        }
    }
}

/**
 * Loads the start of [entry]'s video in the background while this page is open (stopped when
 * leaving, or when playback starts), and shows a small status line.
 */
@Composable
fun PreloadStatus(entry: com.forbiddenstreams.vod.data.ResumeEntry?) {
    val container = LocalContext.current.appContainer()
    val repo = container.repository
    val url = remember(entry?.key, entry?.extension) { entry?.let { repo?.streamUrl(it) } }
    val resuming = (entry?.positionMs ?: 0L) > 0L
    if (url != null) {
        androidx.compose.runtime.DisposableEffect(url, resuming) {
            container.prefetcher.prefetch(
                url,
                if (resuming) com.forbiddenstreams.vod.player.Prefetcher.HEADER_BYTES
                else com.forbiddenstreams.vod.player.Prefetcher.START_BYTES,
            )
            onDispose { container.prefetcher.cancel(url) }
        }
    }
    val state = container.prefetcher.state?.takeIf { it.url == url } ?: return
    Text(
        if (state.done) "✓ Ready to play" else "Pre-loading… ${(state.progress * 100).toInt()}%",
        style = MaterialTheme.typography.labelLarge,
        color = if (state.done) Brand.GlowGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
    )
}
