package com.forbiddenstreams.vod.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forbiddenstreams.vod.AppContainer
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.ContentFilter
import com.forbiddenstreams.vod.data.MediaKind
import com.forbiddenstreams.vod.data.PosterItem
import com.forbiddenstreams.vod.data.toPoster
import com.forbiddenstreams.vod.ui.Brand
import com.forbiddenstreams.vod.ui.Load
import com.forbiddenstreams.vod.ui.LoadingBox
import com.forbiddenstreams.vod.ui.MessageBox
import com.forbiddenstreams.vod.ui.PosterCard
import com.forbiddenstreams.vod.ui.focusHighlight
import com.forbiddenstreams.vod.ui.loadCatching
import com.forbiddenstreams.vod.ui.ratingBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Everything searchable on this screen, pre-normalized once so typing stays fast. */
class SearchCatalog(val entries: List<Entry>) {
    class Entry(val poster: PosterItem, val key: String)
}

data class SearchResults(val query: String, val items: List<PosterItem>) {
    val isEmpty get() = items.isEmpty()
}

/** Searches only movies or only TV series, matching the screen it was opened from. */
class SearchViewModel(private val container: AppContainer, private val kind: String) : ViewModel() {
    var query by mutableStateOf("")
    var catalog by mutableStateOf<Load<SearchCatalog>>(Load.Loading)
        private set
    var results by mutableStateOf(SearchResults("", emptyList()))
        private set

    /** Parental controls: hide 18+ results. */
    private var hideAdult by mutableStateOf(false)

    init {
        load()
        viewModelScope.launch { container.store.parentalEnabled.collect { hideAdult = it } }
        // Re-run the search as the user types (debounced) and when the catalog arrives.
        viewModelScope.launch {
            snapshotFlow { Triple(query.trim(), (catalog as? Load.Ready<SearchCatalog>)?.value, hideAdult) }
                .collectLatest { (q, cat, hide) ->
                    if (cat == null || q.isEmpty()) {
                        results = SearchResults(q, emptyList())
                        return@collectLatest
                    }
                    delay(200)
                    results = withContext(Dispatchers.Default) { search(cat, q, hide) }
                }
        }
    }

    fun load() {
        val repo = container.repository ?: run {
            catalog = Load.Error("Not signed in")
            return
        }
        catalog = Load.Loading
        viewModelScope.launch {
            catalog = loadCatching("Couldn't load the catalog") {
                // Already cached from the Movies / TV Series screen.
                if (kind == MediaKind.SERIES) {
                    val series = repo.allSeries()
                    withContext(Dispatchers.Default) {
                        SearchCatalog(series.distinctBy { it.seriesId }.map { SearchCatalog.Entry(it.toPoster(), normalize(it.name)) })
                    }
                } else {
                    val movies = repo.allMovies()
                    withContext(Dispatchers.Default) {
                        SearchCatalog(movies.distinctBy { it.streamId }.map { SearchCatalog.Entry(it.toPoster(), normalize(it.name)) })
                    }
                }
            }
        }
    }

    private fun search(cat: SearchCatalog, raw: String, hideAdult: Boolean): SearchResults {
        val q = normalize(raw)
        if (q.isEmpty()) return SearchResults(raw, emptyList())
        fun rank(list: List<SearchCatalog.Entry>): List<PosterItem> =
            list.asSequence()
                .filterNot { hideAdult && ContentFilter.isAdult(it.poster) }
                .mapNotNull { e ->
                    val i = e.key.indexOf(q)
                    when {
                        i < 0 -> null
                        i == 0 -> 0 to e // starts with the query
                        e.key[i - 1] == ' ' -> 1 to e // a word starts with it
                        else -> 2 to e
                    }
                }
                .sortedWith(compareBy({ it.first }, { it.second.key.length }))
                .take(MAX_RESULTS)
                .map { it.second.poster }
                .toList()
        return SearchResults(raw, rank(cat.entries))
    }

    private companion object {
        const val MAX_RESULTS = 120
        private val marks = Regex("\\p{Mn}+")
        private val junk = Regex("[^a-z0-9 ]+")
        private val spaces = Regex("\\s+")

        /** Lowercase, strip accents and punctuation: "Amélie (2001)" -> "amelie 2001". */
        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(marks, "")
                .replace(junk, " ")
                .replace(spaces, " ")
                .trim()
    }
}

@Composable
fun SearchScreen(kind: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val container = LocalContext.current.appContainer()
    val vm: SearchViewModel = viewModel(key = "search_$kind") { SearchViewModel(container, kind) }
    val what = if (kind == MediaKind.SERIES) "TV series" else "movies"
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.focusHighlight(CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                singleLine = true,
                placeholder = { Text("Search $what") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand.GlowGreen,
                    cursorColor = Brand.GlowGreen,
                ),
                textStyle = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
                    .focusRequester(fieldFocus),
            )
        }

        when (val cat = vm.catalog) {
            Load.Loading -> LoadingBox()
            is Load.Error -> MessageBox(cat.message, actionLabel = "Retry", onAction = vm::load)
            is Load.Ready -> {
                val results = vm.results
                when {
                    vm.query.isBlank() -> MessageBox("Start typing to search $what.")
                    results.isEmpty && results.query == vm.query.trim() ->
                        MessageBox("No $what match \"${vm.query.trim()}\".")
                    else -> ResultsGrid(results, if (kind == MediaKind.SERIES) "TV Series" else "Movies", onOpen)
                }
            }
        }
    }
}

@Composable
private fun ResultsGrid(results: SearchResults, label: String, onOpen: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(label, results.items.size) }
        items(results.items) { p -> ResultPoster(p) { onOpen(p.id) } }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Text(
        "$title  ·  $count",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Brand.ToxicGreen,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ResultPoster(item: PosterItem, onClick: () -> Unit) {
    PosterCard(
        title = item.title,
        image = item.image,
        badge = if (item.kind == MediaKind.SERIES) "Series" else ratingBadge(item.rating),
        onClick = onClick,
    )
}
