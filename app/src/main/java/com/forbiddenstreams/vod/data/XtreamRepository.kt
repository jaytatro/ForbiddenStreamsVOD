package com.forbiddenstreams.vod.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * In-memory cache in front of [XtreamClient].
 *
 * The whole movie and series catalogs are fetched once (one request each) and grouped
 * into category rows locally. That powers the Netflix-style rows and instant search.
 * Called from the main thread; heavy work is moved to background dispatchers.
 */
class XtreamRepository(val client: XtreamClient) {

    private var vodCategories: List<Category>? = null
    private var seriesCategories: List<Category>? = null
    private var allMovies: List<Movie>? = null
    private var allSeries: List<Series>? = null

    suspend fun vodCategories(force: Boolean = false): List<Category> =
        vodCategories.takeUnless { force } ?: client.vodCategories().also { vodCategories = it }

    suspend fun seriesCategories(force: Boolean = false): List<Category> =
        seriesCategories.takeUnless { force } ?: client.seriesCategories().also { seriesCategories = it }

    suspend fun allMovies(force: Boolean = false): List<Movie> =
        allMovies.takeUnless { force } ?: loadAll(
            whole = { client.movies(null) },
            categories = { vodCategories(force) },
            perCategory = { client.movies(it) },
        ).also { allMovies = it }

    suspend fun allSeries(force: Boolean = false): List<Series> =
        allSeries.takeUnless { force } ?: loadAll(
            whole = { client.series(null) },
            categories = { seriesCategories(force) },
            perCategory = { client.series(it) },
        ).also { allSeries = it }

    suspend fun movieRows(force: Boolean = false): List<CategoryRow> {
        val categories = vodCategories(force)
        val movies = allMovies(force)
        return withContext(Dispatchers.Default) {
            buildRows(categories, movies, { it.categoryId }, { it.added }) { it.toPoster() }
        }
    }

    suspend fun seriesRows(force: Boolean = false): List<CategoryRow> {
        val categories = seriesCategories(force)
        val series = allSeries(force)
        return withContext(Dispatchers.Default) {
            buildRows(categories, series, { it.categoryId }, { it.added }) { it.toPoster() }
        }
    }

    suspend fun movieDetails(id: Int): MovieDetails = client.movieDetails(id)

    suspend fun seriesDetails(id: Int): SeriesDetails = client.seriesDetails(id)

    fun streamUrl(entry: ResumeEntry): String =
        if (entry.kind == MediaKind.EPISODE) {
            client.episodeUrl(entry.streamId, entry.extension)
        } else {
            client.movieUrl(entry.streamId, entry.extension)
        }

    /**
     * Prefer one request for the whole catalog. Some panels return nothing without a
     * category, so fall back to fetching each category (a few at a time).
     */
    private suspend fun <T> loadAll(
        whole: suspend () -> List<T>,
        categories: suspend () -> List<Category>,
        perCategory: suspend (String) -> List<T>,
    ): List<T> {
        val all = whole()
        if (all.isNotEmpty()) return all
        val cats = categories()
        if (cats.isEmpty()) return emptyList()
        val gate = Semaphore(4)
        return coroutineScope {
            cats.map { c ->
                async { gate.withPermit { runCatching { perCategory(c.id) }.getOrDefault(emptyList()) } }
            }.awaitAll().flatten()
        }
    }

    private fun <T> buildRows(
        categories: List<Category>,
        items: List<T>,
        categoryOf: (T) -> String?,
        addedOf: (T) -> Long?,
        toPoster: (T) -> PosterItem,
    ): List<CategoryRow> {
        val grouped = items.groupBy(categoryOf)
        val rows = mutableListOf<CategoryRow>()

        // "Recently added" first, like a streaming service home row.
        val recent = items.filter { (addedOf(it) ?: 0L) > 0L }
            .sortedByDescending { addedOf(it) }
            .take(RECENT_COUNT)
        if (recent.size >= 5) rows += CategoryRow(RECENT_ROW_ID, "Recently added", recent.map(toPoster))

        val known = HashSet<String>()
        categories.distinctBy { it.id }.forEach { c ->
            known += c.id
            val list = grouped[c.id]
            if (!list.isNullOrEmpty()) rows += CategoryRow(c.id, c.name, list.map(toPoster))
        }
        val other = grouped.filterKeys { it == null || it !in known }.values.flatten()
        if (other.isNotEmpty()) rows += CategoryRow(OTHER_ROW_ID, "Other", other.map(toPoster))
        return rows
    }

    companion object {
        const val RECENT_ROW_ID = "__recent"
        const val OTHER_ROW_ID = "__other"
        private const val RECENT_COUNT = 40
    }
}
