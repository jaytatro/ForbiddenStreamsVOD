package com.forbiddenstreams.vod.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.ResumeEntry
import kotlinx.coroutines.launch

/** "Keep watching" row. Selecting an item opens [KeepWatchingDialog]. */
@Composable
fun KeepWatchingRow(
    entries: List<ResumeEntry>,
    posterWidth: Dp,
    onSelect: (ResumeEntry) -> Unit,
    modifier: Modifier = Modifier,
    showTitles: Boolean = true,
    firstItemFocus: FocusRequester? = null,
    onFocused: ((ResumeEntry) -> Unit)? = null,
) {
    Column(modifier) {
        RowTitle("Keep watching")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(entries) { index, entry ->
                PosterCard(
                    title = entry.title,
                    image = entry.poster,
                    badge = entry.subtitle,
                    progress = entry.progress,
                    showTitle = showTitles,
                    onFocused = onFocused?.let { { it(entry) } },
                    modifier = Modifier
                        .width(posterWidth)
                        .then(if (index == 0 && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier),
                    onClick = { onSelect(entry) },
                )
            }
        }
    }
}

/** Section title used above every row. */
@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp),
    )
}

/** Resume / Start over / Remove pop-up for a Keep watching item. */
@Composable
fun KeepWatchingDialog(entry: ResumeEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer()
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { resumeFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(min = 320.dp, max = 460.dp),
        ) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Brand.ToxicGreen,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val where = buildString {
                    entry.subtitle?.let { append(it).append("  •  ") }
                    append("Stopped at ").append(formatTime(entry.positionMs))
                    if (entry.durationMs > 0) append(" of ").append(formatTime(entry.durationMs))
                }
                Text(where, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entry.progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(1.dp))

                Button(
                    onClick = {
                        onDismiss()
                        Playback.start(context, entry)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(resumeFocus)
                        .focusHighlight(CircleShape),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Resume", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        Playback.start(context, entry, fromStart = true)
                    },
                    modifier = Modifier.fillMaxWidth().focusHighlight(CircleShape),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start over")
                }
                OutlinedButton(
                    onClick = {
                        val accountId = container.account?.id
                        if (accountId != null) {
                            container.appScope.launch { container.store.removeResume(accountId, entry.key) }
                        }
                        onDismiss()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().focusHighlight(CircleShape),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Remove")
                }
            }
        }
    }
}
