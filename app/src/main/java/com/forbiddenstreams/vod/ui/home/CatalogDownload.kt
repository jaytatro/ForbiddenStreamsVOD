package com.forbiddenstreams.vod.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.forbiddenstreams.vod.R
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.MediaKind
import com.forbiddenstreams.vod.ui.Brand
import com.forbiddenstreams.vod.ui.focusHighlight
import kotlinx.coroutines.CancellationException

/**
 * Downloads the movie or series catalog before opening that screen, showing a live title
 * count. Calls [onReady] when done (instantly if it was already downloaded).
 */
@Composable
fun CatalogDownloadDialog(kind: String, onReady: () -> Unit, onCancel: () -> Unit) {
    val container = LocalContext.current.appContainer()
    var count by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val buttonFocus = remember { FocusRequester() }
    val label = if (kind == MediaKind.SERIES) "TV series" else "movies"

    LaunchedEffect(attempt) {
        error = null
        count = 0
        try {
            val repo = container.repository ?: throw IllegalStateException("Please sign in again")
            if (kind == MediaKind.SERIES) {
                repo.seriesCategories()
                repo.allSeries { count = it }
            } else {
                repo.vodCategories()
                repo.allMovies { count = it }
            }
            onReady()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't download the $label"
        }
    }
    LaunchedEffect(error) { runCatching { buttonFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(min = 340.dp, max = 480.dp),
        ) {
            Column(
                Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                val err = error
                if (err == null) {
                    Text(
                        "Downloading $label…",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Brand.ToxicGreen,
                    )
                    Text(
                        if (count > 0) "%,d titles so far".format(count) else "Connecting to your provider…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .focusRequester(buttonFocus)
                            .focusHighlight(CircleShape),
                    ) { Text("Cancel") }
                } else {
                    Text(
                        "Download failed",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(err, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { attempt++ },
                            modifier = Modifier
                                .focusRequester(buttonFocus)
                                .focusHighlight(CircleShape),
                        ) { Text("Try again") }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.focusHighlight(CircleShape)) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
