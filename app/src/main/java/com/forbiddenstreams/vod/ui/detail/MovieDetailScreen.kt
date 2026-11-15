package com.forbiddenstreams.vod.ui.detail

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
            details = loadCatching("Couldn't load this movie") { repo.movieDetails(id) }
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

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
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
                        Button(
                            onClick = { Playback.start(context, vm.entryFor(d, saved)) },
                            modifier = Modifier.focusRequester(playFocus).focusHighlight(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Resume ${formatTime(saved.positionMs)}")
                        }
                        OutlinedButton(
                            onClick = { Playback.start(context, vm.entryFor(d, saved), fromStart = true) },
                            modifier = Modifier.focusHighlight(),
                        ) { Text("Start over") }
                    } else {
                        Button(
                            onClick = { Playback.start(context, vm.entryFor(d, null)) },
                            modifier = Modifier.focusRequester(playFocus).focusHighlight(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Play")
                        }
                    }
                    OutlinedButton(onClick = { vm.toggleFavorite(d) }, modifier = Modifier.focusHighlight()) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isFavorite) "Favorited" else "Favorite")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
