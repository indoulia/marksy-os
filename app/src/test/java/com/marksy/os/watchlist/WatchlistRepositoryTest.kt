package com.marksy.os.watchlist

import androidx.room.Room
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.WatchAdd
import com.marksy.os.data.local.WatchlistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchlistRepositoryTest {
    private lateinit var db: MarksyDatabase
    private lateinit var repo: WatchlistRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        repo = WatchlistRepository(db.watchlistDao(), clock = { 1L })
    }

    @After fun tearDown() = db.close()

    @Test fun aListHoldsAtMostFifteenStocksAndNoDuplicates() = runBlocking {
        val defence = repo.createList("Defence")!!
        repeat(WatchlistRepository.MAX_STOCKS) { assertEquals(WatchAdd.ADDED, repo.add(defence, "S$it", null)) }
        assertEquals(WatchAdd.FULL, repo.add(defence, "HAL", null))
        assertEquals(WatchAdd.ALREADY_THERE, repo.add(defence, "S0", null))
        // The same stock can still go in another list.
        assertEquals(WatchAdd.ADDED, repo.add(repo.createList("Momentum")!!, "S0", null))
        assertEquals(16, repo.observeItems().first().size)
    }

    @Test fun listNamesAreUniqueIgnoringCaseAndDeletingAListDropsItsStocks() = runBlocking {
        val id = repo.createList(" SmallCap ")!!
        assertNull(repo.createList("smallcap"))
        assertNull(repo.createList("  "))
        repo.add(id, "IRFC", "Indian Railway Finance")
        repo.deleteList(id)
        assertEquals(0, repo.observeLists().first().size)
        assertEquals(0, repo.observeItems().first().size)
    }

    @Test fun namePillsSkipListsInUseAndNarrowFromThreeChars() {
        val all = WatchlistNames.suggest("", existing = listOf("defence"), preferred = listOf("Aerospace & Defense"))
        assertEquals("Aerospace & Defense", all.first())
        assertEquals(false, "Defence" in all)
        assertEquals(all, WatchlistNames.suggest("Ae", existing = listOf("defence"), preferred = listOf("Aerospace & Defense")))
        assertEquals(listOf("Defence"), WatchlistNames.suggest("def", existing = emptyList()))
        assertEquals(listOf("SmallCap", "MidCap", "LargeCap"), WatchlistNames.suggest("cap", existing = emptyList()))
        assertEquals(listOf("Capital Goods"), WatchlistNames.suggest("capi", existing = emptyList(), sectors = listOf("Capital Goods", "capital goods")))
        assertEquals(emptyList<String>(), WatchlistNames.suggest("zzz", existing = emptyList()))
    }

    @Test fun pickPrefersSectorThenCapBandThenCurrentList() {
        val lists = listOf(WatchlistEntity(1, "SmallCap", 0), WatchlistEntity(2, "Defence", 0), WatchlistEntity(3, "PSUBanks", 0), WatchlistEntity(4, "Large caps", 0))
        assertEquals(2L, WatchlistPicker.pick(lists, emptyMap(), sector = "Aerospace & Defense", marketCapCr = 300_000.0, current = 1))
        assertEquals(3L, WatchlistPicker.pick(lists, emptyMap(), sector = "Banking", marketCapCr = null, current = 1))
        assertEquals(4L, WatchlistPicker.pick(lists, emptyMap(), sector = "Pharmaceuticals", marketCapCr = 150_000.0, current = 2))
        assertEquals(1L, WatchlistPicker.pick(lists, emptyMap(), sector = null, marketCapCr = 4_000.0, current = 2))
        assertEquals(2L, WatchlistPicker.pick(lists, emptyMap(), sector = "Pharmaceuticals", marketCapCr = null, current = 2))
        // A full list is skipped.
        assertEquals(1L, WatchlistPicker.pick(lists, mapOf(2L to WatchlistRepository.MAX_STOCKS), sector = "Defence", marketCapCr = null, current = 1))
    }
}
