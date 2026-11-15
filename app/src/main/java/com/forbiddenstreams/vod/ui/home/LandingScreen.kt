package com.forbiddenstreams.vod.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.forbiddenstreams.vod.R
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.MediaKind
import com.forbiddenstreams.vod.ui.Brand
import com.forbiddenstreams.vod.ui.focusHighlight
import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt

/** First screen after sign-in: two big poster buttons, plus My List and Settings. */
@Composable
fun LandingScreen(
    onMovies: () -> Unit,
    onSeries: () -> Unit,
    onMyList: () -> Unit,
    onSettings: () -> Unit,
) {
    val container = LocalContext.current.appContainer()
    val moviesFocus = remember { FocusRequester() }
    var crashReport by remember { mutableStateOf(container.takeCrashReport()) }
    // Which catalog is being downloaded before opening its screen.
    var downloading by remember { mutableStateOf<String?>(null) }

    // Quiet background check so the Settings button can show an "update" badge.
    LaunchedEffect(Unit) {
        runCatching { moviesFocus.requestFocus() }
        if (!container.checkedForUpdates) {
            container.checkedForUpdates = true
            try {
                container.availableUpdate = container.updates.checkForUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or GitHub unreachable: the Settings screen can retry.
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Brand.Screen, Brand.Background), radius = 1800f))
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
            TopRow(
                updateAvailable = container.availableUpdate != null,
                onMyList = onMyList,
                onSettings = onSettings,
            )
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                val gap = 48.dp
                // Two 2:3 posters side by side, as big as the space allows.
                val cardWidth: Dp = min(maxHeight / 1.5f, (maxWidth - gap) / 2)
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    BigPosterButton(
                        art = R.drawable.poster_movies,
                        label = "Movies",
                        modifier = Modifier
                            .width(cardWidth)
                            .height(cardWidth * 1.5f)
                            .focusRequester(moviesFocus),
                        onClick = { downloading = MediaKind.MOVIE },
                    )
                    BigPosterButton(
                        art = R.drawable.poster_series,
                        label = "TV Series",
                        modifier = Modifier
                            .width(cardWidth)
                            .height(cardWidth * 1.5f),
                        onClick = { downloading = MediaKind.SERIES },
                    )
                }
            }
        }
    }

    crashReport?.let { report -> CrashReportDialog(report, onDismiss = { crashReport = null }) }

    downloading?.let { kind ->
        CatalogDownloadDialog(
            kind = kind,
            onReady = {
                downloading = null
                if (kind == MediaKind.SERIES) onSeries() else onMovies()
            },
            onCancel = { downloading = null },
        )
    }
}

@Composable
private fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The app closed unexpectedly last time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Take a photo of this and send it over so it can be fixed:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    report.lineSequence().take(22).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(CircleShape)) { Text("OK") }
        },
    )
}

@Composable
private fun TopRow(updateAvailable: Boolean, onMyList: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Brand.ToxicGreen)) { append("Forbidden ") }
                withStyle(SpanStyle(color = Brand.Bone)) { append("Streams VOD") }
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onMyList, modifier = Modifier.focusHighlight(CircleShape)) {
            Icon(Icons.Default.Favorite, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("My List")
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onSettings, modifier = Modifier.focusHighlight(CircleShape)) {
            BadgedBox(badge = {
                if (updateAvailable) Badge(containerColor = Brand.ForbiddenRed, contentColor = Brand.Bone)
            }) {
                Icon(Icons.Default.Settings, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (updateAvailable) "Update available" else "Settings")
        }
    }
}

/** Large poster-shaped button using the gritty artwork made by art/make_posters.py. */
@Composable
private fun BigPosterButton(
    @DrawableRes art: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Card(
        onClick = onClick,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Brand.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier.focusHighlight(shape),
    ) {
        Image(
            painter = painterResource(art),
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
