package com.marksy.os.ui

import com.marksy.os.data.local.WatchlistEntity
import com.marksy.os.data.local.WatchlistItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchlistRankingTest {
    private val defence = WatchlistEntity(1, "Defence", 1)
    private val smallCap = WatchlistEntity(2, "SmallCap", 2)
    private val banks = WatchlistEntity(3, "Banks", 3)
    private val lists = listOf(defence, smallCap, banks)
    private fun items(vararg perList: Pair<Long, Int>) = perList.flatMap { (id, n) -> (1..n).map { WatchlistItemEntity(id, "S$id-$it", addedAt = 0) } }

    @Test
    fun listsRankByStockCountAndTiesKeepCreationOrder() {
        val ranked = rankedWatchlists(lists, items(1L to 2, 2L to 5, 3L to 2))
        assertEquals(listOf("SmallCap", "Defence", "Banks"), ranked.map { it.name })
    }

    @Test
    fun theFullestListOpensByDefaultAndAChosenOneStaysOpen() {
        val items = items(1L to 1, 2L to 4)
        assertEquals(2L, watchlistCurrentId("", lists, items))
        assertEquals(3L, watchlistCurrentId("3", lists, items))
        // A deleted list's id falls back to the fullest one.
        assertEquals(2L, watchlistCurrentId("9", lists, items))
        assertEquals("SmallCap 4/15", watchlistLabel("", lists, items))
    }
}
