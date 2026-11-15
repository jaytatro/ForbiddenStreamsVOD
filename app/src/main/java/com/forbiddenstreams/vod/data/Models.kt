package com.forbiddenstreams.vod.data

import kotlinx.serialization.Serializable

object MediaKind {
    const val MOVIE = "movie"
    const val SERIES = "series"
    const val EPISODE = "episode"
}

/** Xtream Codes login. Saved on the device so the user stays signed in. */
@Serializable
data class Account(
    val server: String,
    val username: String,
    val password: String,
) {
    /** Stable id used to keep favorites and progress separate per provider account. */
    val id: String
        get() = Integer.toHexString("${server.trimEnd('/')}|$username".hashCode())
}

data class AuthInfo(
    val status: String?,
    val expiresAtEpochSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
)

data class Category(val id: String, val name: String)

data class Movie(
    val streamId: Int,
    val name: String,
    val poster: String?,
    val rating: String?,
    val extension: String,
    val categoryId: String?,
    /** When the provider added it (epoch seconds), used for the "Recently added" row. */
    val added: Long? = null,
    /** Provider's own adult flag (is_adult). */
    val adult: Boolean = false,
)

data class MovieDetails(
    val movie: Movie,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseDate: String?,
    val durationSecs: Int?,
    val backdrop: String?,
)

data class Series(
    val seriesId: Int,
    val name: String,
    val cover: String?,
    val rating: String?,
    val plot: String?,
    val categoryId: String?,
    val genre: String?,
    val releaseDate: String?,
    val cast: String?,
    val backdrop: String?,
    /** Last update (epoch seconds), used for the "Recently added" row. */
    val added: Long? = null,
    val adult: Boolean = false,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Int?,
)

data class SeriesDetails(
    val series: Series,
    /** Season number -> episodes, both sorted ascending. */
    val seasons: Map<Int, List<Episode>>,
)

/** Common shape for anything shown as a poster. Extra fields feed the info panel. */
data class PosterItem(
    val kind: String,
    val id: Int,
    val title: String,
    val image: String?,
    val rating: String?,
    val plot: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val backdrop: String? = null,
    val adult: Boolean = false,
)

/** One Netflix-style row on the Movies / TV Series screens. */
data class CategoryRow(
    val id: String,
    val name: String,
    val items: List<PosterItem>,
)

fun Movie.toPoster() = PosterItem(MediaKind.MOVIE, streamId, name, poster, rating, adult = adult)

fun Series.toPoster() = PosterItem(
    kind = MediaKind.SERIES,
    id = seriesId,
    title = name,
    image = cover,
    rating = rating,
    plot = plot,
    genre = genre,
    releaseDate = releaseDate,
    backdrop = backdrop,
    adult = adult,
)

@Serializable
data class FavoriteItem(
    val kind: String,
    val id: Int,
    val name: String,
    val poster: String? = null,
) {
    val key: String get() = "$kind:$id"
}

/** A saved playback position for a movie or an episode. */
@Serializable
data class ResumeEntry(
    /** "movie:<streamId>" or "episode:<episodeId>". */
    val key: String,
    /** [MediaKind.MOVIE] or [MediaKind.EPISODE]. */
    val kind: String,
    val streamId: String,
    val extension: String,
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val seriesId: Int? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
) {
    val progress: Float?
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
}
