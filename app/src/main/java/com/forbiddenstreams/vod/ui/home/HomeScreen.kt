package com.forbiddenstreams.vod.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.forbiddenstreams.vod.R
import com.forbiddenstreams.vod.ui.Brand
import com.forbiddenstreams.vod.ui.focusHighlight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.forbiddenstreams.vod.data.Category
import com.forbiddenstreams.vod.data.FavoriteItem
import com.forbiddenstreams.vod.data.MediaKind
import com.forbiddenstreams.vod.data.PosterItem
import com.forbiddenstreams.vod.data.ResumeEntry
import com.forbiddenstreams.vod.data.toPoster
import com.forbiddenstreams.vod.ui.Load
import com.forbiddenstreams.vod.ui.LoadingBox
import com.forbiddenstreams.vod.ui.MessageBox
import com.forbiddenstreams.vod.ui.Playback
import com.forbiddenstreams.vod.ui.PosterCard
import com.forbiddenstreams.vod.ui.ScreenHeader
import com.forbiddenstreams.vod.ui.loadCatching
import com.forbiddenstreams.vod.ui.ratingBadge
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Category chips + poster grid for movies or TV series. */
@Composable
fun BrowseScreen(kind: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader(if (kind == MediaKind.MOVIE) "Movies" else "TV Series", onBack = onBack)
        BrowseTab(kind, onOpen = onOpen)
    }
}

/** Continue watching + favorites. */
@Composable
fun MyListScreen(onBack: () -> Unit, onOpenMovie: (Int) -> Unit, onOpenSeries: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader("My List", onBack = onBack)
        MyListTab(onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries)
    }
}

// ---- Movies / Series browsing ---------------------------------------------------------------

class BrowseViewModel(private val container: AppContainer, private val kind: String) : ViewModel() {
    var categories by mutableStateOf<Load<List<Category>>>(Load.Loading)
        private set
    var selected by mutableStateOf<Category?>(null)
        private set
    var items by mutableStateOf<Load<List<PosterItem>>>(Load.Loading)
        private set
    var query by mutableStateOf("")

    private var itemsJob: Job? = null

    init {
        loadCategories()
    }

    fun loadCategories(force: Boolean = false) {
        val repo = container.repository ?: run {
            categories = Load.Error("Not signed in")
            return
        }
        categories = Load.Loading
        viewModelScope.launch {
            val result = loadCatching("Couldn't load categories") {
                if (kind == MediaKind.MOVIE) repo.vodCategories(force) else repo.seriesCategories(force)
            }
            categories = result
            if (result is Load.Ready) {
                val keep = selected?.let { sel -> result.value.firstOrNull { it.id == sel.id } }
                val target = keep ?: result.value.firstOrNull()
                if (target != null) select(target, force) else items = Load.Ready(emptyList())
            }
        }
    }

    fun select(category: Category, force: Boolean = false) {
        val repo = container.repository ?: return
        selected = category
        itemsJob?.cancel()
        items = Load.Loading
        itemsJob = viewModelScope.launch {
            items = loadCatching("Couldn't load this category") {
                if (kind == MediaKind.MOVIE) {
                    repo.movies(category.id, force).map { it.toPoster() }
                } else {
                    repo.series(category.id, force).map { it.toPoster() }
                }
            }
        }
    }

    fun refresh() = loadCategories(force = true)

    fun retryItems() {
        selected?.let { select(it, force = true) }
    }
}

@Composable
private fun BrowseTab(kind: String, onOpen: (Int) -> Unit) {
    val container = LocalContext.current.appContainer()
    val vm: BrowseViewModel = viewModel(key = "browse_$kind") { BrowseViewModel(container, kind) }

    when (val cats = vm.categories) {
        Load.Loading -> LoadingBox()
        is Load.Error -> MessageBox(cats.message, actionLabel = "Retry", onAction = { vm.loadCategories(force = true) })
        is Load.Ready -> {
            if (cats.value.isEmpty()) {
                MessageBox(
                    if (kind == MediaKind.MOVIE) "Your provider has no movie categories." else "Your provider has no series categories.",
                    actionLabel = "Refresh",
                    onAction = vm::refresh,
                )
                return
            }
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = { vm.query = it },
                        singleLine = true,
                        placeholder = { Text("Search in ${vm.selected?.name ?: "category"}") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (vm.query.isNotEmpty()) {
                                IconButton(onClick = { vm.query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cats.value) { category ->
                        FilterChip(
                            modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                            selected = category.id == vm.selected?.id,
                            onClick = { vm.select(category) },
                            label = { Text(category.name) },
                        )
                    }
                }
                when (val itemsState = vm.items) {
                    Load.Loading -> LoadingBox()
                    is Load.Error -> MessageBox(itemsState.message, actionLabel = "Retry", onAction = vm::retryItems)
                    is Load.Ready -> {
                        val query = vm.query.trim()
                        val all = itemsState.value
                        val visible = remember(all, query) {
                            if (query.isEmpty()) all else all.filter { it.title.contains(query, ignoreCase = true) }
                        }
                        if (visible.isEmpty()) {
                            MessageBox(if (query.isEmpty()) "Nothing in this category." else "No matches for \"$query\".")
                        } else {
                            PosterGrid(visible, onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterGrid(posters: List<PosterItem>, onOpen: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(posters) { item ->
            PosterCard(
                title = item.title,
                image = item.image,
                badge = ratingBadge(item.rating),
                onClick = { onOpen(item.id) },
            )
        }
    }
}

// ---- My List: continue watching + favorites -------------------------------------------------

class MyListViewModel(container: AppContainer) : ViewModel() {
    private val accountId = container.account?.id
    val favorites: Flow<List<FavoriteItem>> = accountId?.let(container.store::favorites) ?: emptyFlow()
    val resume: Flow<List<ResumeEntry>> = accountId?.let(container.store::resume) ?: emptyFlow()
}

@Composable
private fun MyListTab(onOpenMovie: (Int) -> Unit, onOpenSeries: (Int) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer()
    val vm: MyListViewModel = viewModel { MyListViewModel(container) }
    val favorites by vm.favorites.collectAsState(initial = emptyList())
    val resume by vm.resume.collectAsState(initial = emptyList())

    if (favorites.isEmpty() && resume.isEmpty()) {
        MessageBox("Nothing here yet. Movies and series you favorite, and anything you start watching, show up here.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (resume.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Continue watching") }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resume) { entry ->
                        PosterCard(
                            title = entry.title,
                            image = entry.poster,
                            badge = entry.subtitle,
                            progress = entry.progress,
                            modifier = Modifier.width(140.dp),
                            onClick = { Playback.start(context, entry) },
                        )
                    }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Favorites") }
            items(favorites) { fav ->
                PosterCard(
                    title = fav.name,
                    image = fav.poster,
                    badge = if (fav.kind == MediaKind.SERIES) "Series" else null,
                    onClick = {
                        if (fav.kind == MediaKind.SERIES) onOpenSeries(fav.id) else onOpenMovie(fav.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}
