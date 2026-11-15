package com.forbiddenstreams.vod.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.forbiddenstreams.vod.AppContainer
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.Episode
import com.forbiddenstreams.vod.data.FavoriteItem
import com.forbiddenstreams.vod.data.MediaKind
import com.forbiddenstreams.vod.data.ResumeEntry
import com.forbiddenstreams.vod.data.SeriesDetails
import com.forbiddenstreams.vod.ui.Load
import com.forbiddenstreams.vod.ui.LoadingBox
import com.forbiddenstreams.vod.ui.MessageBox
import com.forbiddenstreams.vod.ui.Playback
import com.forbiddenstreams.vod.ui.GatedScrolling
import com.forbiddenstreams.vod.ui.WhiteButton
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import com.forbiddenstreams.vod.ui.focusHighlight
import com.forbiddenstreams.vod.ui.formatDuration
import com.forbiddenstreams.vod.ui.loadCatching
import com.forbiddenstreams.vod.ui.ratingBadge
import com.forbiddenstreams.vod.ui.yearOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SeriesDetailViewModel(private val container: AppContainer, private val id: Int) : ViewModel() {
    private val accountId = container.account?.id
    private val favoriteKey = "${MediaKind.SERIES}:$id"

    var details by mutableStateOf<Load<SeriesDetails>>(Load.Loading)
        private set
    var season by mutableIntStateOf(-1)

    val isFavorite: Flow<Boolean> = accountId
        ?.let { container.store.favorites(it).map { list -> list.any { fav -> fav.key == favoriteKey } } }
        ?: emptyFlow()

    /** This series' saved episode positions, most recent first. */
    val resume: Flow<List<ResumeEntry>> = accountId
        ?.let { container.store.resume(it).map { list -> list.filter { e -> e.seriesId == id } } }
        ?: emptyFlow()

    init {
        load()
    }

    fun load() {
        val repo = container.repository ?: run {
            details = Load.Error("Not signed in")
            return
        }
        details = Load.Loading
        viewModelScope.launch {
            val result = loadCatching("Couldn't load this series") { repo.seriesDetails(id) }
            if (result is Load.Ready && season !in result.value.seasons) {
                season = result.value.seasons.keys.firstOrNull() ?: -1
            }
            details = result
        }
    }

    fun toggleFavorite(d: SeriesDetails) {
        val accountId = accountId ?: return
        viewModelScope.launch {
            container.store.toggleFavorite(
                accountId,
                FavoriteItem(MediaKind.SERIES, id, d.series.name, d.series.cover),
            )
        }
    }

    fun entryFor(d: SeriesDetails, episode: Episode, existing: ResumeEntry?): ResumeEntry =
        existing?.copy(extension = episode.extension) ?: ResumeEntry(
            key = "${MediaKind.EPISODE}:${episode.id}",
            kind = MediaKind.EPISODE,
            streamId = episode.id,
            extension = episode.extension,
            title = d.series.name,
            subtitle = "S${episode.season} E${episode.number}",
            poster = d.series.cover,
            seriesId = id,
        )
}

@Composable
fun SeriesDetailScreen(id: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer()
    val vm: SeriesDetailViewModel = viewModel { SeriesDetailViewModel(container, id) }
    val isFavorite by vm.isFavorite.collectAsState(initial = false)
    val resumeList by vm.resume.collectAsState(initial = emptyList())

    when (val state = vm.details) {
        Load.Loading -> LoadingBox()
        is Load.Error -> Box(Modifier.fillMaxSize()) {
            MessageBox(state.message, actionLabel = "Retry", onAction = vm::load)
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        is Load.Ready -> {
            val d = state.value
            val s = d.series
            val resumeByKey = remember(resumeList) { resumeList.associateBy { it.key } }
            val latest = resumeList.firstOrNull()
            val episodes = d.seasons[vm.season].orEmpty()
            val primaryFocus = remember { FocusRequester() }
            LaunchedEffect(latest != null) { runCatching { primaryFocus.requestFocus() } }

            val meta = listOfNotNull(
                yearOf(s.releaseDate),
                d.seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
                s.genre,
                ratingBadge(s.rating),
            ).joinToString("  •  ")

            // The page stays put while you're on Play; it scrolls only once you go down to the
            // seasons / episodes, and returns to the top when you come back up.
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            var headerFocused by remember { mutableStateOf(true) }
            GatedScrolling(enabled = { !headerFocused }) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Box(
                        Modifier.onFocusChanged {
                            headerFocused = it.hasFocus
                            if (it.hasFocus && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        },
                    ) {
                    DetailHeader(
                        title = s.name,
                        backdrop = s.backdrop,
                        poster = s.cover,
                        meta = meta,
                        plot = s.plot,
                        credits = listOf("Cast" to s.cast),
                        onBack = onBack,
                    ) {
                        if (latest != null) {
                            WhiteButton(
                                text = "Continue ${latest.subtitle.orEmpty()}".trim(),
                                icon = Icons.Default.PlayArrow,
                                onClick = { Playback.start(context, latest) },
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                        } else {
                            val first = d.seasons.values.firstOrNull()?.firstOrNull()
                            if (first != null) {
                                WhiteButton(
                                    text = "Play S${first.season} E${first.number}",
                                    icon = Icons.Default.PlayArrow,
                                    onClick = { Playback.start(context, vm.entryFor(d, first, null)) },
                                    modifier = Modifier.focusRequester(primaryFocus),
                                )
                            }
                        }
                    }
                }
                }

                if (d.seasons.isEmpty()) {
                    item { MessageBox("Your provider hasn't listed any episodes for this series.", Modifier.height(160.dp)) }
                } else {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(d.seasons.keys.toList()) { number ->
                                FilterChip(
                                    modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                                    selected = number == vm.season,
                                    onClick = { vm.season = number },
                                    label = { Text("Season $number") },
                                )
                            }
                        }
                    }
                    items(episodes) { episode ->
                        val saved = resumeByKey["${MediaKind.EPISODE}:${episode.id}"]
                        EpisodeRow(
                            episode = episode,
                            fallbackImage = s.backdrop ?: s.cover,
                            progress = saved?.progress,
                            onClick = { Playback.start(context, vm.entryFor(d, episode, saved)) },
                        )
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    fallbackImage: String?,
    progress: Float?,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Card(
        onClick = onClick,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .focusHighlight(shape),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .width(144.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = episode.image ?: fallbackImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center),
                )
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${episode.number}. ${episode.title}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                formatDuration(episode.durationSecs)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!episode.plot.isNullOrBlank()) {
                    Text(
                        episode.plot,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
