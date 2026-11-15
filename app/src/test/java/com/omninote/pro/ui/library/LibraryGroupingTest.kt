package com.omninote.pro.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class LibraryGroupingTest {
    private val zone = ZoneId.of("UTC")
    // Wednesday 2026-11-18 10:00 UTC. With a Monday week start the week began on 2026-11-16.
    private val now = at(2026, 11, 18, 10, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun bucketOf(t: Long, locale: Locale = Locale.UK): RecencyBucket =
        groupByRecency(listOf(t), now, zone, locale) { it }.single().bucket

    @Test fun emptyInputGivesNoGroups() {
        assertTrue(groupByRecency(emptyList<Long>(), now, zone, Locale.UK) { it }.isEmpty())
    }

    @Test fun midnightBoundary() {
        assertEquals(RecencyBucket.TODAY, bucketOf(at(2026, 11, 18, 0, 0)))
        assertEquals(RecencyBucket.YESTERDAY, bucketOf(at(2026, 11, 17, 23, 59)))
        assertEquals(RecencyBucket.YESTERDAY, bucketOf(at(2026, 11, 17, 0, 0)))
    }

    @Test fun futureCountsAsToday() {
        assertEquals(RecencyBucket.TODAY, bucketOf(at(2026, 11, 25, 9, 0)))
    }

    @Test fun weekBoundaryMondayStart() {
        assertEquals(RecencyBucket.THIS_WEEK, bucketOf(at(2026, 11, 16, 0, 0), Locale.UK))
        assertEquals(RecencyBucket.THIS_MONTH, bucketOf(at(2026, 11, 15, 23, 59), Locale.UK))
    }

    @Test fun weekBoundarySundayStart() {
        // US weeks start on Sunday 2026-11-15, so that day is still this week.
        assertEquals(RecencyBucket.THIS_WEEK, bucketOf(at(2026, 11, 15, 12, 0), Locale.US))
        assertEquals(RecencyBucket.THIS_MONTH, bucketOf(at(2026, 11, 14, 23, 59), Locale.US))
    }

    @Test fun monthBoundary() {
        assertEquals(RecencyBucket.THIS_MONTH, bucketOf(at(2026, 11, 1, 0, 0)))
        assertEquals(RecencyBucket.EARLIER, bucketOf(at(2026, 10, 31, 23, 59)))
    }

    @Test fun yesterdayBeatsWeekWhenWeekJustStarted() {
        // Monday 2026-11-16: yesterday is Sunday, which is before this week's start but is still "Yesterday".
        val monday = at(2026, 11, 16, 8, 0)
        val g = groupByRecency(listOf(at(2026, 11, 15, 20, 0)), monday, zone, Locale.UK) { it }
        assertEquals(RecencyBucket.YESTERDAY, g.single().bucket)
    }

    @Test fun groupsAreOrderedOmitEmptyAndKeepInputOrder() {
        val a = at(2026, 11, 18, 9, 0)
        val b = at(2026, 11, 18, 8, 0)
        val c = at(2026, 9, 1, 8, 0)
        val groups = groupByRecency(listOf(a, c, b), now, zone, Locale.UK) { it }
        assertEquals(listOf(RecencyBucket.TODAY, RecencyBucket.EARLIER), groups.map { it.bucket })
        assertEquals(listOf(a, b), groups[0].items)
    }

    @Test fun sortOrders() {
        data class N(val t: String, val p: Int, val c: Long, val u: Long)
        val list = listOf(N("beta", 3, 1, 5), N("Alpha", 9, 3, 1), N("gamma", 1, 2, 9))
        fun s(x: LibrarySort) = sortItems(list, x, { it.t }, { it.p }, { it.c }, { it.u }).map { it.t }
        assertEquals(listOf("gamma", "beta", "Alpha"), s(LibrarySort.RECENT))
        assertEquals(listOf("Alpha", "beta", "gamma"), s(LibrarySort.NAME))
        assertEquals(listOf("Alpha", "beta", "gamma"), s(LibrarySort.PAGES))
        assertEquals(listOf("Alpha", "gamma", "beta"), s(LibrarySort.CREATED))
    }
}
