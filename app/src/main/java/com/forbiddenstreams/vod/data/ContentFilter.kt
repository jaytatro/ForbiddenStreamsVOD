package com.forbiddenstreams.vod.data

/**
 * Parental controls: decides what counts as adult content.
 * Titles and categories are hidden when they say "18+" or "XXX", when the provider
 * flags them (is_adult), or when a category name mentions "adult".
 */
object ContentFilter {
    private val adultMarker = Regex("""18\s*\+|\bxxx\b""", RegexOption.IGNORE_CASE)
    private val adultCategory = Regex("""\badults?\b|\bporn""", RegexOption.IGNORE_CASE)

    fun isAdultTitle(text: String?): Boolean = text != null && adultMarker.containsMatchIn(text)

    fun isAdultCategory(name: String?): Boolean =
        name != null && (adultMarker.containsMatchIn(name) || adultCategory.containsMatchIn(name))

    fun isAdult(item: PosterItem): Boolean = item.adult || isAdultTitle(item.title)

    /** Drops adult categories and adult titles; removes rows left empty. */
    fun filterRows(rows: List<CategoryRow>): List<CategoryRow> =
        rows.asSequence()
            .filterNot { isAdultCategory(it.name) }
            .map { row -> row.copy(items = row.items.filterNot(::isAdult)) }
            .filter { it.items.isNotEmpty() }
            .toList()
}
