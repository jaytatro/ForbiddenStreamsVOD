package com.forbiddenstreams.vod.data

/**
 * In-memory cache in front of [XtreamClient], so switching tabs and categories
 * doesn't re-download large lists. Called from the main thread only.
 */
class XtreamRepository(val client: XtreamClient) {

    private var vodCategories: List<Category>? = null
    private var seriesCategories: List<Category>? = null
    private val movies = HashMap<String, List<Movie>>()
    private val series = HashMap<String, List<Series>>()

    suspend fun vodCategories(force: Boolean = false): List<Category> =
        vodCategories.takeUnless { force } ?: client.vodCategories().also { vodCategories = it }

    suspend fun seriesCategories(force: Boolean = false): List<Category> =
        seriesCategories.takeUnless { force } ?: client.seriesCategories().also { seriesCategories = it }

    suspend fun movies(categoryId: String, force: Boolean = false): List<Movie> =
        movies[categoryId].takeUnless { force } ?: client.movies(categoryId).also { movies[categoryId] = it }

    suspend fun series(categoryId: String, force: Boolean = false): List<Series> =
        series[categoryId].takeUnless { force } ?: client.series(categoryId).also { series[categoryId] = it }

    suspend fun movieDetails(id: Int): MovieDetails = client.movieDetails(id)

    suspend fun seriesDetails(id: Int): SeriesDetails = client.seriesDetails(id)

    fun streamUrl(entry: ResumeEntry): String =
        if (entry.kind == MediaKind.EPISODE) {
            client.episodeUrl(entry.streamId, entry.extension)
        } else {
            client.movieUrl(entry.streamId, entry.extension)
        }
}
