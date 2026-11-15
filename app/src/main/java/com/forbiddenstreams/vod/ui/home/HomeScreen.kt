package com.forbiddenstreams.vod.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forbiddenstreams.vod.AppContainer
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.CategoryRow
import com.forbiddenstreams.vod.data.FavoriteItem
import com.forbiddenstreams.vod.data.MediaKind
import com.forbiddenstreams.vod.data.PosterItem
import com.forbiddenstreams.vod.data.ResumeEntry
import com.forbiddenstreams.vod.ui.Brand
import com.forbiddenstreams.vod.ui.KeepWatchingDialog
import com.forbiddenstreams.vod.ui.KeepWatchingRow
import com.forbiddenstreams.vod.ui.Load
import com.forbiddenstreams.vod.ui.LoadingBox
import com.forbiddenstreams.vod.ui.MessageBox
import com.forbiddenstreams.vod.ui.PosterCard
import com.forbiddenstreams.vod.ui.RowTitle
import com.forbiddenstreams.vod.ui.ScreenHeader
import com.forbiddenstreams.vod.ui.focusHighlight
import com.forbiddenstreams.vod.ui.loadCatching
import com.forbiddenstreams.vod.ui.ratingBadge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val PosterWidth = 140.dp
private val PosterGap = 14.dp
private val SidePadding = 24.dp

// ---- Movies / TV Series: Netflix-style category rows ----------------------------------------

class BrowseViewModel(private val container: AppContainer, private val kind: String) : ViewModel() {
    var rows by mutableStateOf<Load<List<CategoryRow>>>(Load.Loading)
        private set

    /** Id of the row currently expanded into a grid, if any. */
    var expandedRowId by mutableStateOf<String?>(null)

    /** Movies on the Movies screen, episodes on the TV Series screen. */
    val keepWatching: Flow<List<ResumeEntry>> = container.account?.id?.let { id ->
        val wanted = if (kind == MediaKind.MOVIE) MediaKind.MOVIE else MediaKind.EPISODE
        container.store.resume(id).map { list -> list.filter { it.kind == wanted } }
    } ?: emptyFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        val repo = container.repository ?: run {
            rows = Load.Error("Not signed in")
            return
        }
        rows = Load.Loading
        viewModelScope.launch {
            rows = loadCatching("Couldn't load the catalog") {
                if (kind == MediaKind.MOVIE) repo.movieRows(force) else repo.seriesRows(force)
            }
        }
    }
}

@Composable
fun BrowseScreen(
    kind: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val container = LocalContext.current.appContainer()
    val vm: BrowseViewModel = viewModel(key = "browse_$kind") { BrowseViewModel(container, kind) }
    val keepWatching by vm.keepWatching.collectAsState(initial = emptyList())
    var dialogEntry by remember { mutableStateOf<ResumeEntry?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader(if (kind == MediaKind.MOVIE) "Movies" else "TV Series", onBack = onBack) {
            OutlinedButton(onClick = onSearch, modifier = Modifier.focusHighlight(CircleShape)) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Search")
            }
            IconButton(onClick = { vm.load(force = true) }, modifier = Modifier.focusHighlight(CircleShape)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        when (val state = vm.rows) {
            Load.Loading -> LoadingBox()
            is Load.Error -> MessageBox(state.message, actionLabel = "Retry", onAction = { vm.load(force = true) })
            is Load.Ready -> {
                if (state.value.isEmpty() && keepWatching.isEmpty()) {
                    MessageBox(
                        if (kind == MediaKind.MOVIE) "Your provider has no movies." else "Your provider has no TV series.",
                        actionLabel = "Refresh",
                        onAction = { vm.load(force = true) },
                    )
                } else {
                    CatalogRows(
                        rows = state.value,
                        keepWatching = keepWatching,
                        expandedRowId = vm.expandedRowId,
                        onExpand = { vm.expandedRowId = it },
                        onKeepWatching = { dialogEntry = it },
                        onOpen = onOpen,
                    )
                }
            }
        }
    }

    dialogEntry?.let { entry -> KeepWatchingDialog(entry, onDismiss = { dialogEntry = null }) }
}

@Composable
private fun CatalogRows(
    rows: List<CategoryRow>,
    keepWatching: List<ResumeEntry>,
    expandedRowId: String?,
    onExpand: (String?) -> Unit,
    onKeepWatching: (ResumeEntry) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val leadingItems = if (keepWatching.isNotEmpty()) 2 else 1 // hint (+ keep watching)

    // Back closes an expanded row before leaving the screen.
    BackHandler(enabled = expandedRowId != null) { onExpand(null) }

    // Bring the expanded row's title to the top.
    LaunchedEffect(expandedRowId) {
        val index = rows.indexOfFirst { it.id == expandedRowId }
        if (index >= 0) listState.animateScrollToItem(leadingItems + index)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (((maxWidth - SidePadding * 2) + PosterGap) / (PosterWidth + PosterGap)).toInt().coerceAtLeast(2)

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 40.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "hint") {
                Text(
                    "Tip: hold OK on a poster to expand its row",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = SidePadding),
                )
            }
            if (keepWatching.isNotEmpty()) {
                item(key = "keep_watching") {
                    KeepWatchingRow(keepWatching, PosterWidth, onSelect = onKeepWatching)
                }
            }

            rows.forEachIndexed { rowIndex, row ->
                if (row.id == expandedRowId) {
                    item(key = "row_${rowIndex}_header") {
                        Row(
                            Modifier.fillMaxWidth().padding(end = SidePadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RowTitle("${row.name}  ·  ${row.items.size}", Modifier.weight(1f))
                            TextButton(onClick = { onExpand(null) }, modifier = Modifier.padding(top = 14.dp)) {
                                Text("Collapse", color = Brand.GlowGreen)
                            }
                        }
                    }
                    row.items.chunked(columns).forEachIndexed { chunkIndex, chunk ->
                        item(key = "row_${rowIndex}_grid_$chunkIndex") {
                            Row(
                                Modifier.padding(horizontal = SidePadding, vertical = PosterGap / 2),
                                horizontalArrangement = Arrangement.spacedBy(PosterGap),
                            ) {
                                chunk.forEach { poster ->
                                    Poster(poster, onOpen, onLongClick = { onExpand(null) })
                                }
                            }
                        }
                    }
                } else {
                    item(key = "row_$rowIndex") {
                        Column {
                            RowTitle(row.name)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = SidePadding, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(PosterGap),
                            ) {
                                items(row.items) { poster ->
                                    Poster(poster, onOpen, onLongClick = { onExpand(row.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(item: PosterItem, onOpen: (Int) -> Unit, onLongClick: () -> Unit) {
    PosterCard(
        title = item.title,
        image = item.image,
        badge = ratingBadge(item.rating),
        modifier = Modifier.width(PosterWidth),
        onLongClick = onLongClick,
        onClick = { onOpen(item.id) },
    )
}

// ---- My List: keep watching + favorites -------------------------------------------------------

class MyListViewModel(container: AppContainer) : ViewModel() {
    private val accountId = container.account?.id
    val favorites: Flow<List<FavoriteItem>> = accountId?.let(container.store::favorites) ?: emptyFlow()
    val resume: Flow<List<ResumeEntry>> = accountId?.let(container.store::resume) ?: emptyFlow()
}

@Composable
fun MyListScreen(onBack: () -> Unit, onOpenMovie: (Int) -> Unit, onOpenSeries: (Int) -> Unit) {
    val container = LocalContext.current.appContainer()
    val vm: MyListViewModel = viewModel { MyListViewModel(container) }
    val favorites by vm.favorites.collectAsState(initial = emptyList())
    val resume by vm.resume.collectAsState(initial = emptyList())
    var dialogEntry by remember { mutableStateOf<ResumeEntry?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader("My List", onBack = onBack)

        if (favorites.isEmpty() && resume.isEmpty()) {
            MessageBox("Nothing here yet. Movies and series you favorite, and anything you start watching, show up here.")
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = (((maxWidth - SidePadding * 2) + PosterGap) / (PosterWidth + PosterGap)).toInt().coerceAtLeast(2)
                LazyColumn(contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
                    if (resume.isNotEmpty()) {
                        item(key = "keep_watching") {
                            KeepWatchingRow(resume, PosterWidth, onSelect = { dialogEntry = it })
                        }
                    }
                    if (favorites.isNotEmpty()) {
                        item(key = "favorites_title") { RowTitle("Favorites") }
                        favorites.chunked(columns).forEachIndexed { i, chunk ->
                            item(key = "favorites_$i") {
                                Row(
                                    Modifier.padding(horizontal = SidePadding, vertical = PosterGap / 2),
                                    horizontalArrangement = Arrangement.spacedBy(PosterGap),
                                ) {
                                    chunk.forEach { fav ->
                                        PosterCard(
                                            title = fav.name,
                                            image = fav.poster,
                                            badge = if (fav.kind == MediaKind.SERIES) "Series" else null,
                                            modifier = Modifier.width(PosterWidth),
                                            onClick = {
                                                if (fav.kind == MediaKind.SERIES) onOpenSeries(fav.id) else onOpenMovie(fav.id)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    dialogEntry?.let { entry -> KeepWatchingDialog(entry, onDismiss = { dialogEntry = null }) }
}
