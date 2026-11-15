package com.forbiddenstreams.vod.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.forbiddenstreams.vod.ui.Brand

/** What the info panel shows for the focused poster. */
data class InfoData(
    val title: String,
    val meta: String?,
    val plot: String?,
    val image: String?,
)

/**
 * Netflix-style panel above the rows: title, year / runtime / genre / rating and plot of
 * the focused title, over its backdrop art.
 */
@Composable
fun InfoPanel(data: InfoData?, placeholder: String, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth()) {
        // Backdrop on the right, fading into the background on the left and bottom.
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(data?.image).crossfade(300).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.62f)
                .alpha(0.6f),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to background,
                        0.38f to background.copy(alpha = 0.92f),
                        0.7f to background.copy(alpha = 0.25f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to background)),
        )

        AnimatedContent(
            targetState = data,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "info",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.58f)
                .padding(start = 24.dp, end = 12.dp, top = topInset),
        ) { info ->
            if (info == null) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        info.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = Brand.Bone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!info.meta.isNullOrBlank()) {
                        Text(
                            info.meta,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Brand.GlowGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!info.plot.isNullOrBlank()) {
                        Text(
                            info.plot,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
