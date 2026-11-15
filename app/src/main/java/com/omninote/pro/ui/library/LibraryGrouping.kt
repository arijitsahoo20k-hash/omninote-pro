package com.omninote.pro.ui.library

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** Recency buckets for the library grid, newest first. */
enum class RecencyBucket(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    EARLIER("Earlier")
}

data class RecencyGroup<T>(val bucket: RecencyBucket, val items: List<T>)

/** Library sort orders. */
enum class LibrarySort(val label: String) {
    RECENT("Recent"),
    NAME("Name A–Z"),
    PAGES("Pages"),
    CREATED("Created")
}

/**
 * Splits [items] into recency groups by the calendar day of [updatedAt] in [zone]. The week starts on the first day of
 * the week for [locale]. Dates in the future count as today. Empty groups are omitted and the order of [items] is kept
 * inside each group.
 */
fun <T> groupByRecency(
    items: List<T>,
    now: Long,
    zone: ZoneId,
    locale: Locale,
    updatedAt: (T) -> Long
): List<RecencyGroup<T>> {
    if (items.isEmpty()) return emptyList()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val yesterday = today.minusDays(1)
    val weekStart = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))
    val monthStart = today.withDayOfMonth(1)
    val buckets = HashMap<RecencyBucket, MutableList<T>>()
    for (item in items) {
        val day = Instant.ofEpochMilli(updatedAt(item)).atZone(zone).toLocalDate()
        val bucket = when {
            !day.isBefore(today) -> RecencyBucket.TODAY
            day == yesterday -> RecencyBucket.YESTERDAY
            !day.isBefore(weekStart) -> RecencyBucket.THIS_WEEK
            !day.isBefore(monthStart) -> RecencyBucket.THIS_MONTH
            else -> RecencyBucket.EARLIER
        }
        buckets.getOrPut(bucket) { ArrayList() }.add(item)
    }
    return RecencyBucket.values().mapNotNull { b -> buckets[b]?.let { RecencyGroup(b, it) } }
}

/** Client-side sort. Recent, Pages and Created are descending; Name is case-insensitive ascending. */
fun <T> sortItems(
    items: List<T>,
    sort: LibrarySort,
    title: (T) -> String,
    pages: (T) -> Int,
    createdAt: (T) -> Long,
    updatedAt: (T) -> Long
): List<T> = when (sort) {
    LibrarySort.RECENT -> items.sortedByDescending(updatedAt)
    LibrarySort.NAME -> items.sortedBy { title(it).lowercase(Locale.getDefault()) }
    LibrarySort.PAGES -> items.sortedByDescending(pages)
    LibrarySort.CREATED -> items.sortedByDescending(createdAt)
}
