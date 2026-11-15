package com.forbiddenstreams.vod.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forbiddenstreams.vod.AppContainer
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.CategoryRow
import com.forbiddenstreams.vod.data.ContentFilter
import com.forbiddenstreams.vod.data.FavoriteItem
import com.forbiddenstreams.vod.data.MediaKind
import com.forbiddenstreams.vod.data.MovieDetails
import com.forbiddenstreams.vod.data.PosterItem
import com.forbiddenstreams.vod.data.ResumeEntry
import com.forbiddenstreams.vod.ui.Brand
import com.forbiddenstreams.vod.ui.KeepWatchingDialog
import com.forbiddenstreams.vod.ui.KeepWatchingRow
import com.forbiddenstreams.vod.ui.Load
import com.forbiddenstreams.vod.ui.LoadingBox
import com.forbiddenstreams.vod.ui.MessageBox
import com.forbiddenstreams.vod.ui.PivotScrolling
import com.forbiddenstreams.vod.ui.PosterCard
import com.forbiddenstreams.vod.ui.RowTitle
import com.forbiddenstreams.vod.ui.ScreenHeader
import com.forbiddenstreams.vod.ui.focusHighlight
import com.forbiddenstreams.vod.ui.formatDuration
import com.forbiddenstreams.vod.ui.formatTime
import com.forbiddenstreams.vod.ui.loadCatching
import com.forbiddenstreams.vod.ui.ratingBadge
import com.forbiddenstreams.vod.ui.yearOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PosterWidth = 100.dp
private val PosterGap = 12.dp
private val SidePadding = 24.dp

/** Space taken by the floating top bar. */
private val HeaderHeight = 72.dp

/** Where the focused poster settles vertically: just below its row title. */
private val RowPivot = 50.dp

/**
 * Height of the rows area: exactly one row (title + poster), so only the focused row shows
 * and the info panel above gets the rest of the screen.
 */
private val RowAreaHeight = RowPivot + PosterWidth * 1.5f + 12.dp

// ---- Movies / TV Series: info panel + Netflix-style rows ------------------------------------

class BrowseViewModel(private val container: AppContainer, private val kind: String) : ViewModel() {
    private val rawRows = MutableStateFlow<Load<List<CategoryRow>>>(Load.Loading)
    private val parental: Flow<Boolean> = container.store.parentalEnabled

    /** Rows after parental filtering. */
    var rows by mutableStateOf<Load<List<CategoryRow>>>(Load.Loading)
        private set

    /** Id of the row currently expanded into a grid, if any. */
    var expandedRowId by mutableStateOf<String?>(null)

    /** The focused poster, and extra details fetched for it (movies only). */
    var focused by mutableStateOf<PosterItem?>(null)
    var focusedResume by mutableStateOf<ResumeEntry?>(null)
    var focusedDetails by mutableStateOf<MovieDetails?>(null)
        private set

    /**
     * What the info panel shows. Updated only once focus settles, so holding the remote
     * doesn't reload backdrop art for every poster passed (that work made scrolling stutter).
     */
    var shownInfo by mutableStateOf<InfoData?>(null)
        private set

    /** Movies on the Movies screen, episodes on the TV Series screen. */
    val keepWatching: Flow<List<ResumeEntry>> = container.account?.id?.let { id ->
        val wanted = if (kind == MediaKind.MOVIE) MediaKind.MOVIE else MediaKind.EPISODE
        combine(container.store.resume(id), parental) { list, hideAdult ->
            list.filter { it.kind == wanted && !(hideAdult && ContentFilter.isAdultTitle(it.title)) }
        }
    } ?: flowOf(emptyList())

    init {
        viewModelScope.launch {
            combine(rawRows, parental) { r, hideAdult -> r to hideAdult }.collectLatest { (r, hideAdult) ->
                rows = if (r is Load.Ready && hideAdult) {
                    Load.Ready(withContext(Dispatchers.Default) { ContentFilter.filterRows(r.value) })
                } else {
                    r
                }
            }
        }
        viewModelScope.launch {
            snapshotFlow { info() }.collectLatest { next ->
                if (shownInfo != null) delay(220)
                shownInfo = next
            }
        }
        // Fetch plot / runtime / backdrop for the focused movie once focus settles.
        viewModelScope.launch {
            snapshotFlow { focused }.collectLatest { item ->
                focusedDetails = null
                if (item == null || item.kind != MediaKind.MOVIE) return@collectLatest
                delay(350)
                focusedDetails = try {
                    container.repository?.movieDetailsCached(item.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        load()
    }

    fun load(force: Boolean = false) {
        val repo = container.repository ?: run {
            rawRows.value = Load.Error("Not signed in")
            return
        }
        rawRows.value = Load.Loading
        viewModelScope.launch {
            rawRows.value = loadCatching("Couldn't load the catalog") {
                if (kind == MediaKind.MOVIE) repo.movieRows(force) else repo.seriesRows(force)
            }
        }
    }

    /** Text for the info panel. */
    fun info(): InfoData? {
        focusedResume?.let { e ->
            val stopped = "Stopped at ${formatTime(e.positionMs)}" +
                if (e.durationMs > 0) " of ${formatTime(e.durationMs)}" else ""
            val meta = listOfNotNull(e.subtitle, stopped).joinToString("  •  ")
            return InfoData(e.title, meta, "Select to resume, start over or remove.", e.poster)
        }
        val item = focused ?: return null
        val d = focusedDetails
        val meta = listOfNotNull(
            yearOf(d?.releaseDate ?: item.releaseDate),
            formatDuration(d?.durationSecs),
            (d?.genre ?: item.genre)?.takeIf { it.isNotBlank() },
            ratingBadge(item.rating ?: d?.movie?.rating),
        ).joinToString("  •  ")
        return InfoData(
            title = item.title,
            meta = meta,
            plot = d?.plot ?: item.plot,
            image = d?.backdrop ?: item.backdrop ?: item.image,
        )
    }
}

@Composable
fun BrowseScreen(
    kind: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val container = LocalContext.current.appContainer()
    val vm: BrowseViewModel = viewModel(key = "browse_$kind") { BrowseViewModel(container, kind) }
    val keepWatching by vm.keepWatching.collectAsState(initial = emptyList())
    var dialogEntry by remember { mutableStateOf<ResumeEntry?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val state = vm.rows) {
            Load.Loading -> LoadingBox()
            is Load.Error -> MessageBox(state.message, actionLabel = "Retry", onAction = { vm.load(force = true) })
            is Load.Ready -> {
                if (state.value.isEmpty() && keepWatching.isEmpty()) {
                    MessageBox(
                        if (kind == MediaKind.MOVIE) "No movies to show." else "No TV series to show.",
                        actionLabel = "Refresh",
                        onAction = { vm.load(force = true) },
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        // The art runs up behind the transparent top bar.
                        InfoPanel(
                            data = vm.shownInfo,
                            placeholder = "Hold OK on a poster to see its whole category.",
                            topInset = HeaderHeight,
                            modifier = Modifier.weight(1f),
                        )
                        CatalogRows(
                            vm = vm,
                            rows = state.value,
                            keepWatching = keepWatching,
                            onKeepWatching = { dialogEntry = it },
                            onOpen = onOpen,
                            modifier = Modifier
                                .height(RowAreaHeight)
                                .navigationBarsPadding(),
                        )
                    }
                }
            }
        }

        // Transparent top bar floating over the info panel.
        ScreenHeader(
            if (kind == MediaKind.MOVIE) "Movies" else "TV Series",
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding(),
        ) {
            OutlinedButton(onClick = onSearch, modifier = Modifier.focusHighlight(CircleShape)) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Search")
            }
            val green = IconButtonDefaults.iconButtonColors(contentColor = Brand.ToxicGreen)
            IconButton(
                onClick = { vm.load(force = true) },
                colors = green,
                modifier = Modifier.focusHighlight(CircleShape),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onSettings, colors = green, modifier = Modifier.focusHighlight(CircleShape)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    }

    dialogEntry?.let { entry -> KeepWatchingDialog(entry, onDismiss = { dialogEntry = null }) }

    // Holding OK on a poster opens its whole category full screen, in its own window so
    // closing it lands back on the same poster.
    val expandedRow = (vm.rows as? Load.Ready<List<CategoryRow>>)?.value?.firstOrNull { it.id == vm.expandedRowId }
    if (expandedRow != null) {
        CategoryFullScreen(row = expandedRow, onClose = { vm.expandedRowId = null }, onOpen = onOpen)
    }
}

@Composable
private fun CatalogRows(
    vm: BrowseViewModel,
    rows: List<CategoryRow>,
    keepWatching: List<ResumeEntry>,
    onKeepWatching: (ResumeEntry) -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Each row keeps its sideways position while it scrolls off screen and back.
    val rowStates = remember(rows) { HashMap<String, LazyListState>() }
    val firstFocus = remember { FocusRequester() }

    // Put D-pad focus on the first poster so the info panel and remote work right away.
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        PivotScrolling(pivot = RowPivot) {
            LazyColumn(
                state = listState,
                // Room at the end so the last row can also scroll up to the pivot.
                contentPadding = PaddingValues(bottom = maxHeight),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (keepWatching.isNotEmpty()) {
                    item(key = "keep_watching") {
                        PivotScrolling(pivot = SidePadding) {
                            KeepWatchingRow(
                                entries = keepWatching,
                                posterWidth = PosterWidth,
                                showTitles = false,
                                firstItemFocus = firstFocus,
                                onFocused = { e ->
                                    vm.focusedResume = e
                                    vm.focused = null
                                },
                                onSelect = onKeepWatching,
                            )
                        }
                    }
                }

                rows.forEachIndexed { rowIndex, row ->
                    val takesFirstFocus = keepWatching.isEmpty() && rowIndex == 0
                    item(key = "row_$rowIndex") {
                        Column {
                            RowTitle(row.name)
                            PivotScrolling(pivot = SidePadding) {
                                LazyRow(
                                    state = rowStates.getOrPut(row.id) { LazyListState() },
                                    contentPadding = PaddingValues(horizontal = SidePadding, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(PosterGap),
                                ) {
                                    itemsIndexed(row.items) { i, poster ->
                                        Poster(
                                            item = poster,
                                            vm = vm,
                                            onOpen = onOpen,
                                            onLongClick = { vm.expandedRowId = row.id },
                                            focusRequester = if (takesFirstFocus && i == 0) firstFocus else null,
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
}

/** A whole category as a full-screen poster grid. Back (or holding OK again) closes it. */
@Composable
private fun CategoryFullScreen(row: CategoryRow, onClose: () -> Unit, onOpen: (Int) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(row.id) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        ScreenHeader(row.name, onBack = onClose) {
            Text(
                "%,d titles".format(row.items.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PivotScrolling(pivot = 16.dp) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(start = SidePadding, end = SidePadding, top = 8.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                gridItemsIndexed(row.items) { i, poster ->
                    PosterCard(
                        title = poster.title,
                        image = poster.image,
                        badge = ratingBadge(poster.rating),
                        onLongClick = onClose,
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = { onOpen(poster.id) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun Poster(
    item: PosterItem,
    vm: BrowseViewModel,
    onOpen: (Int) -> Unit,
    onLongClick: () -> Unit,
    focusRequester: FocusRequester?,
    width: Dp = PosterWidth,
) {
    PosterCard(
        title = item.title,
        image = item.image,
        showTitle = false,
        onFocused = {
            vm.focused = item
            vm.focusedResume = null
        },
        modifier = Modifier
            .width(width)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        onLongClick = onLongClick,
        onClick = { onOpen(item.id) },
    )
}

// ---- My List: keep watching + favorites -------------------------------------------------------

class MyListViewModel(container: AppContainer) : ViewModel() {
    private val accountId = container.account?.id
    private val parental = container.store.parentalEnabled

    val favorites: Flow<List<FavoriteItem>> = accountId?.let { id ->
        combine(container.store.favorites(id), parental) { list, hide ->
            if (hide) list.filterNot { ContentFilter.isAdultTitle(it.name) } else list
        }
    } ?: emptyFlow()

    val resume: Flow<List<ResumeEntry>> = accountId?.let { id ->
        combine(container.store.resume(id), parental) { list, hide ->
            if (hide) list.filterNot { ContentFilter.isAdultTitle(it.title) } else list
        }
    } ?: emptyFlow()
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
                PivotScrolling(pivot = RowPivot) {
                    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
                        if (resume.isNotEmpty()) {
                            item(key = "keep_watching") {
                                PivotScrolling(pivot = SidePadding) {
                                    KeepWatchingRow(resume, PosterWidth, onSelect = { dialogEntry = it })
                                }
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
    }

    dialogEntry?.let { entry -> KeepWatchingDialog(entry, onDismiss = { dialogEntry = null }) }
}
