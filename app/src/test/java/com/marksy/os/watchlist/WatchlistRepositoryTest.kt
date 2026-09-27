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

    @Test fun movingKeepsTheStockWhenTheTargetIsFullAndMergesWhenItIsAlreadyThere() = runBlocking {
        val from = repo.createList("Defence")!!
        val full = repo.createList("Full")!!
        repeat(WatchlistRepository.MAX_STOCKS) { repo.add(full, "S$it", null) }
        repo.add(from, "HAL", "Hindustan Aeronautics")
        assertEquals(WatchAdd.FULL, repo.move(from, full, "HAL"))
        assertEquals(listOf(from), repo.observeItems().first().filter { it.symbol == "HAL" }.map { it.watchlistId })

        val psu = repo.createList("PSU")!!
        assertEquals(WatchAdd.ADDED, repo.move(from, psu, "HAL"))
        val moved = repo.observeItems().first().single { it.symbol == "HAL" }
        assertEquals(psu, moved.watchlistId)
        assertEquals("Hindustan Aeronautics", moved.name)

        repo.add(from, "HAL", null)
        assertEquals(WatchAdd.ALREADY_THERE, repo.move(from, psu, "HAL"))
        assertEquals(listOf(psu), repo.observeItems().first().filter { it.symbol == "HAL" }.map { it.watchlistId })
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

    @Test fun namePillsSkipListsInUseAndTypingSuggestsFromAWiderVocabulary() {
        val pills = WatchlistNames.proposals(existing = listOf("defence"), preferred = listOf("Aerospace & Defense"))
        assertEquals("Aerospace & Defense", pills.first())
        assertEquals(false, "Defence" in pills)
        assertEquals(emptyList<String>(), WatchlistNames.suggest("sm", existing = emptyList()))
        // Suggestions go beyond the pills: "sma" also offers the Smallcap 250 index theme.
        assertEquals(listOf("SmallCap", "Smallcap 250"), WatchlistNames.suggest("sma", existing = emptyList()))
        assertEquals(listOf("Smallcap 250"), WatchlistNames.suggest("sma", existing = listOf("SmallCap")))
        assertEquals(listOf("Capital Goods", "Capital Markets"), WatchlistNames.suggest("capi", emptyList(), sectors = listOf("capital goods")))
        assertEquals(listOf("Textile Machinery"), WatchlistNames.suggest("machin", emptyList(), sectors = listOf("Textile Machinery")))
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
