package com.forbiddenstreams.vod.ui

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

/** Simple async state holder used by the view models. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** Runs [block] and wraps the outcome, keeping coroutine cancellation intact. */
suspend fun <T> loadCatching(fallbackMessage: String, block: suspend () -> T): Load<T> =
    try {
        Load.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Error(e.message ?: fallbackMessage)
    }

/**
 * Visible green focus ring, so D-pad navigation on Android TV is easy to follow.
 * (No zoom here: zooming before a button's focusable part changes the size scrolling
 * sees and makes lists nudge. Posters zoom safely inside [PosterCard].)
 */
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = Brand.GlowGreen
    this
        .onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
}

/**
 * Poster tile. [onLongClick] fires on a touch long-press or when OK is held on a TV remote.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String,
    image: String?,
    modifier: Modifier = Modifier,
    badge: String? = null,
    progress: Float? = null,
    showTitle: Boolean = true,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    // Holding OK on the remote = long press, acted on when OK is let go.
    val keyState = remember { LongPressKeyState() }
    val touchLongClick: (() -> Unit)? = onLongClick?.let { long -> { if (!keyState.keyHeld) long() } }
    val click: () -> Unit = { if (!keyState.consumeSuppressedClick()) onClick() }

    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "posterScale")
    val interaction = remember { MutableInteractionSource() }
    Card(
        shape = shape,
        modifier = modifier
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused?.invoke()
            }
            .then(
                if (onLongClick != null) {
                    Modifier.onPreviewKeyEvent { event -> keyState.handle(event, onLongClick) }
                } else {
                    Modifier
                },
            )
            // No ripple: on a TV the green ring is the selection indicator.
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = click,
                onLongClick = touchLongClick,
            )
            // The zoom and ring are applied *after* the focusable part, so scrolling sees the
            // poster's real size. Zooming before it made lists nudge a few pixels on every move.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focused) Modifier.selectionRing() else Modifier)
            .clip(shape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (image.isNullOrBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(8.dp),
                )
            } else {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (badge != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        badge,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
        if (showTitle) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/**
 * Bright green ring with a soft glow, drawn just *outside* the poster's edge so the
 * artwork never hides it.
 */
private fun Modifier.selectionRing(): Modifier = drawWithContent {
    drawContent()
    val gap = 3.dp.toPx()
    val width = 3.5.dp.toPx()
    val radius = 12.dp.toPx() + gap
    val inset = gap + width / 2
    val topLeft = androidx.compose.ui.geometry.Offset(-inset, -inset)
    val ringSize = androidx.compose.ui.geometry.Size(size.width + inset * 2, size.height + inset * 2)
    val corner = androidx.compose.ui.geometry.CornerRadius(radius, radius)
    // Glow
    drawRoundRect(Brand.GlowGreen.copy(alpha = 0.28f), topLeft, ringSize, corner, style = Stroke(width * 3.2f))
    // Ring
    drawRoundRect(Brand.GlowGreen, topLeft, ringSize, corner, style = Stroke(width))
}

/**
 * Tracks the remote's OK / Enter key for [PosterCard]: a press held for [LONG_PRESS_MS] or
 * more counts as a long press, and it fires only when the key is released.
 */
private class LongPressKeyState {
    /** True while OK is down on this poster (touch long-press is ignored then). */
    var keyHeld = false
        private set
    private var suppressClickUntil = 0L

    /** True (once) if the click that follows a long press should be ignored. */
    fun consumeSuppressedClick(): Boolean {
        val suppress = SystemClock.uptimeMillis() < suppressClickUntil
        suppressClickUntil = 0L
        return suppress
    }

    fun handle(event: KeyEvent, onLong: () -> Unit): Boolean {
        val native = event.nativeKeyEvent
        val isOk = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
        if (!isOk) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                keyHeld = true
                // Let the first key-down through (press feedback); swallow auto-repeats.
                native.repeatCount > 0
            }
            KeyEventType.KeyUp -> {
                keyHeld = false
                if (native.eventTime - native.downTime >= LONG_PRESS_MS) {
                    // Skip the click this key-up would trigger, then act on the long press.
                    suppressClickUntil = SystemClock.uptimeMillis() + 1_000
                    onLong()
                }
                false // let the click handling finish its press state normally
            }
            else -> false
        }
    }

    private companion object {
        const val LONG_PRESS_MS = 550L
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun MessageBox(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                message,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** Shows a star rating badge only when the provider actually has a rating. */
fun ratingBadge(rating: String?): String? {
    val value = rating?.toDoubleOrNull() ?: return null
    if (value <= 0.0) return null
    return "★ " + if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
}

/** 3_723_000 -> "1:02:03", 65_000 -> "1:05". */
fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 6300 -> "1h 45m". */
fun formatDuration(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** "2019-05-24" -> "2019". */
fun yearOf(date: String?): String? = date?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

/**
 * Main action button on detail pages: white with black text; a green ring shows when the
 * remote has it selected.
 */
@Composable
fun WhiteButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 26.dp, vertical = 12.dp),
        modifier = modifier.focusHighlight(RoundedCornerShape(10.dp)),
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, contentDescription = null)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
