package com.forbiddenstreams.vod.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.forbiddenstreams.vod.data.Trailer
import com.forbiddenstreams.vod.player.TrailerActivity
import com.forbiddenstreams.vod.ui.GatedScrolling
import com.forbiddenstreams.vod.ui.PivotScrolling
import com.forbiddenstreams.vod.ui.WhiteButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forbiddenstreams.vod.AppContainer
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.FavoriteItem
import com.forbiddenstreams.vod.data.MediaKind
import com.forbiddenstreams.vod.data.MovieDetails
import com.forbiddenstreams.vod.data.ResumeEntry
import com.forbiddenstreams.vod.ui.Load
import com.forbiddenstreams.vod.ui.LoadingBox
import com.forbiddenstreams.vod.ui.MessageBox
import com.forbiddenstreams.vod.ui.Playback
import com.forbiddenstreams.vod.ui.focusHighlight
import com.forbiddenstreams.vod.ui.formatDuration
import com.forbiddenstreams.vod.ui.formatTime
import com.forbiddenstreams.vod.ui.loadCatching
import com.forbiddenstreams.vod.ui.ratingBadge
import com.forbiddenstreams.vod.ui.yearOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MovieDetailViewModel(private val container: AppContainer, private val id: Int) : ViewModel() {
    private val accountId = container.account?.id
    private val key = "${MediaKind.MOVIE}:$id"

    var details by mutableStateOf<Load<MovieDetails>>(Load.Loading)
        private set

    val isFavorite: Flow<Boolean> = accountId
        ?.let { container.store.favorites(it).map { list -> list.any { fav -> fav.key == key } } }
        ?: emptyFlow()

    val resume: Flow<ResumeEntry?> = accountId
        ?.let { container.store.resume(it).map { list -> list.firstOrNull { e -> e.key == key } } }
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
            val result = loadCatching("Couldn't load this movie") { repo.movieDetails(id) }
            details = result
            if (result is Load.Ready) loadTrailers(result.value)
        }
    }

    /** YouTube trailers for this movie (provider's own first, then search results). */
    var trailers by mutableStateOf<Load<List<Trailer>>>(Load.Loading)
        private set

    private fun loadTrailers(d: MovieDetails) {
        trailers = Load.Loading
        viewModelScope.launch {
            trailers = loadCatching("Couldn't find trailers") {
                container.trailers.find(d.movie.name, yearOf(d.releaseDate), d.youtubeTrailer)
            }
        }
    }

    fun toggleFavorite(d: MovieDetails) {
        val accountId = accountId ?: return
        viewModelScope.launch {
            container.store.toggleFavorite(accountId, FavoriteItem(MediaKind.MOVIE, id, d.movie.name, d.movie.poster))
        }
    }

    fun entryFor(d: MovieDetails, existing: ResumeEntry?): ResumeEntry =
        existing?.copy(extension = d.movie.extension) ?: ResumeEntry(
            key = key,
            kind = MediaKind.MOVIE,
            streamId = d.movie.streamId.toString(),
            extension = d.movie.extension,
            title = d.movie.name,
            poster = d.movie.poster,
        )
}

@Composable
fun MovieDetailScreen(id: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer()
    val vm: MovieDetailViewModel = viewModel { MovieDetailViewModel(container, id) }
    val isFavorite by vm.isFavorite.collectAsState(initial = false)
    val resume by vm.resume.collectAsState(initial = null)

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
            val playFocus = remember { FocusRequester() }
            // Re-run when the button swaps between Play and Resume so D-pad focus lands on it.
            val hasResume = (resume?.positionMs ?: 0L) > 0L
            LaunchedEffect(hasResume) { runCatching { playFocus.requestFocus() } }

            val meta = listOfNotNull(
                yearOf(d.releaseDate),
                formatDuration(d.durationSecs),
                d.genre,
                ratingBadge(d.movie.rating),
            ).joinToString("  •  ")

            // The page stays put while you're on Play; it scrolls only once you go down to
            // the trailers, and jumps back to the top when you return to the buttons.
            val scroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            var trailersFocused by remember { mutableStateOf(false) }
            GatedScrolling(enabled = { trailersFocused }) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .navigationBarsPadding(),
            ) {
                Box(
                    Modifier.onFocusChanged {
                        if (it.hasFocus && scroll.value != 0) scope.launch { scroll.animateScrollTo(0) }
                    },
                ) {
                DetailHeader(
                    title = d.movie.name,
                    backdrop = d.backdrop,
                    poster = d.movie.poster,
                    meta = meta,
                    plot = d.plot,
                    credits = listOf("Director" to d.director, "Cast" to d.cast),
                    onBack = onBack,
                ) {
                    val saved = resume
                    if (saved != null && saved.positionMs > 0) {
                        WhiteButton(
                            text = "Resume ${formatTime(saved.positionMs)}",
                            icon = Icons.Default.PlayArrow,
                            onClick = { Playback.start(context, vm.entryFor(d, saved)) },
                            modifier = Modifier.focusRequester(playFocus),
                        )
                        OutlinedButton(
                            onClick = { Playback.start(context, vm.entryFor(d, saved), fromStart = true) },
                            modifier = Modifier.focusHighlight(),
                        ) { Text("Start over") }
                    } else {
                        WhiteButton(
                            text = "Play",
                            icon = Icons.Default.PlayArrow,
                            onClick = { Playback.start(context, vm.entryFor(d, null)) },
                            modifier = Modifier.focusRequester(playFocus),
                        )
                    }
                }
                }
                TrailersRow(
                    vm.trailers,
                    Modifier.onFocusChanged { trailersFocused = it.hasFocus },
                )
                Spacer(Modifier.height(24.dp))
            }
            }
        }
    }
}

@Composable
private fun TrailersRow(state: Load<List<Trailer>>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.padding(top = 8.dp)) {
        Text(
            "Trailers",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        when (state) {
            Load.Loading -> Text(
                "Looking for trailers…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            is Load.Error -> Text(
                state.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            is Load.Ready -> if (state.value.isEmpty()) {
                Text(
                    "No trailers found.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                val ids = state.value.map { it.videoId }
                PivotScrolling(pivot = 24.dp) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(state.value) { index, trailer ->
                        TrailerCard(trailer) {
                            context.startActivity(TrailerActivity.intent(context, ids, index))
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun TrailerCard(trailer: Trailer, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        shape = shape,
        modifier = Modifier
            .width(240.dp)
            .focusHighlight(shape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = trailer.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(6.dp),
            )
        }
        Text(
            trailer.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp),
        )
    }
}
