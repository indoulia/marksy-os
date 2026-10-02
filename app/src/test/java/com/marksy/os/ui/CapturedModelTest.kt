package com.marksy.os.ui

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedModelTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 1, h, m, 0, 0, ist).plusDays(day - 1L).toInstant().toEpochMilli()
    private val thu1042 = at(1, 10, 42)
    private val minute = 60_000L
    private val symbols = setOf("TATAPOWER", "INFY")
    private val symbolOf: (String) -> String? = { text -> text.split(Regex("[^A-Za-z]+")).firstOrNull { it in symbols } }

    private var n = 0L
    private fun ev(
        body: String, postedAt: Long = thu1042 - minute, state: DeliveryState = DeliveryState.DELIVERED, title: String = "NiftyPro",
        pkg: String = "com.whatsapp", name: String = "WhatsApp", lifecycle: String = "ACTIVE", response: String? = null
    ) = NotificationEventEntity(
        id = ++n, sourcePackage = pkg, sourceName = name, sourceKey = "k$n", eventFingerprint = "f$n", title = title, body = body,
        postedAt = postedAt, category = "TRADING", priority = 1, confidence = 1f, isTrading = true,
        deliveryState = state.name, lifecycleState = lifecycle, marksyResponseJson = response
    )

    private fun lanes(vararg e: NotificationEventEntity, now: Long = thu1042, by: StackBy = StackBy.SOURCE, show: ShowOnly = ShowOnly.ALL) =
        CapturedModel.lanes(e.toList(), now, by, show, symbolOf)

    private fun CapturedLanes.todayIds() = today.flatMap { it.rows }.map { it.event.id }
    private fun CapturedLanes.earlierIds() = earlier.flatMap { it.rows }.map { it.event.id }

    private val call = "Buy TATAPOWER above 412 target 440 sl 405 intraday swing"
    private val sell = "Sell INFY below 1500 target 1450 sl 1520 swing"
    private val levels = """{"marksyView":{"recommendation":"BUY","entryPrice":412.0,"targetPrice":440.0,"stopLoss":405.0,"levelState":"entry_not_hit"}}"""

    @Test
    fun boundaryIsLastWeekdayClose() {
        assertEquals(at(0, 15, 30), CapturedModel.todayBoundary(thu1042))
        assertEquals(at(1, 15, 30), CapturedModel.todayBoundary(at(1, 15, 30)))
        assertEquals(at(1, 15, 30), CapturedModel.todayBoundary(at(1, 20)))
        // Saturday 3 Oct looks back to Friday 2 Oct's close.
        assertEquals(at(2, 15, 30), CapturedModel.todayBoundary(at(3, 11)))
    }

    @Test
    fun todayEndsAtTheCloseAndOlderGoesToEarlier() {
        val overnight = ev(call, postedAt = at(0, 19))
        val intraday = ev(sell, postedAt = at(0, 11))
        val l = lanes(overnight, intraday)
        assertEquals(listOf(overnight.id), l.todayIds())
        assertEquals(listOf(intraday.id), l.earlierIds())
        val after = lanes(overnight, now = at(1, 16))
        assertTrue(after.today.isEmpty())
        assertEquals(1, after.earlierIds().size)
    }

    @Test
    fun failedAndLongWaitingNeedYouButFreshPendingDoesNot() {
        val failed = ev(call, state = DeliveryState.FAILED)
        val stuck = ev("Buy INFY above 1500 target 1550 sl 1480 swing", postedAt = thu1042 - 16 * minute, state = DeliveryState.PENDING)
        val fresh = ev(sell, postedAt = thu1042 - 14 * minute, state = DeliveryState.PENDING)
        val l = lanes(failed, stuck, fresh)
        assertEquals(mapOf(failed.id to NeedKind.RETRY, stuck.id to NeedKind.WAITING), l.needsYou.associate { it.row.event.id to it.kind })
        assertEquals(listOf(fresh.id), l.todayIds())
    }

    @Test
    fun rejectedOwnOrdersGoToTheBottomFoldNotNeedsYou() {
        val rejected = ev("Order for 10 INFY was rejected by exchange", title = "Order rejected", state = DeliveryState.NOT_APPLICABLE, pkg = "com.kite", name = "Kite")
        val l = lanes(rejected)
        assertEquals(listOf(rejected.id), l.rejected.map { it.event.id })
        assertTrue(l.needsYou.isEmpty() && l.today.isEmpty() && l.earlier.isEmpty())
    }

    @Test
    fun retiredCallsStayVisibleInEarlier() {
        val retired = ev(call, lifecycle = "RESOLVED")
        val l = lanes(retired)
        assertTrue(l.today.isEmpty())
        assertEquals(listOf(retired.id), l.earlierIds())
    }

    @Test
    fun forwardedCopyInAnotherGroupFoldsIntoTheFirstRow() {
        val first = ev(call, postedAt = thu1042 - 30 * minute, title = "NiftyPro")
        val copy = ev(call.uppercase(), postedAt = thu1042 - 10 * minute, title = "Swing Club")
        val row = lanes(first, copy).today.flatMap { it.rows }.single()
        assertEquals(first.id, row.event.id)
        assertEquals(1, row.folded)
        assertEquals(listOf("Swing Club"), row.alsoIn)
    }

    @Test
    fun sameGroupRepeatOwnOrdersAndDayOldCopiesDoNotFold() {
        val a = ev(call, postedAt = thu1042 - 30 * minute)
        val b = ev(call, postedAt = thu1042 - 10 * minute)
        assertEquals(2, lanes(a, b).todayIds().size)
        val own1 = ev("Bought 10 INFY at 1500 executed", state = DeliveryState.NOT_APPLICABLE, title = "Kite")
        val own2 = ev("Bought 10 INFY at 1500 executed", state = DeliveryState.NOT_APPLICABLE, title = "Kite2")
        assertEquals(2, lanes(own1, own2).todayIds().size)
        val dayOld = ev(call, postedAt = at(0, 9), title = "G1")
        val dayNew = ev(call, postedAt = at(1, 10), title = "G2")
        val l = lanes(dayOld, dayNew, now = at(1, 10, 30))
        assertEquals(2, l.todayIds().size + l.earlierIds().size)
    }

    @Test
    fun rowShowsSideSymbolAndMarksyLevels() {
        val row = lanes(ev(call, response = levels)).today.single().rows.single()
        assertEquals("BUY TATAPOWER", row.headline)
        val lv = row.levels!!
        assertEquals(412.0, lv.entry!!, 0.0)
        assertEquals(440.0, lv.target!!, 0.0)
        assertEquals(405.0, lv.stopLoss!!, 0.0)
        assertEquals("Entry not hit", lv.status)
    }

    @Test
    fun noResponseStillGetsSideAndSymbolFromTheText() {
        val row = lanes(ev(call)).today.single().rows.single()
        assertEquals("BUY TATAPOWER", row.headline)
        assertNull(row.levels!!.entry)
        assertNull(row.levels!!.status)
    }

    @Test
    fun stackBySourceUsesChatTitleAndBySymbolUsesTheTicker() {
        val a = ev(call, title = "NiftyPro")
        val b = ev(sell, title = "Swing Club")
        assertEquals(setOf("NiftyPro", "Swing Club"), lanes(a, b).today.map { it.label }.toSet())
        assertEquals(setOf("TATAPOWER", "INFY"), lanes(a, b, by = StackBy.SYMBOL).today.map { it.label }.toSet())
    }

    @Test
    fun showFiltersNarrowTheLanes() {
        val tip = ev(call)
        val own = ev("Bought 10 INFY executed", state = DeliveryState.NOT_APPLICABLE)
        val failed = ev(sell, state = DeliveryState.FAILED)
        assertEquals(listOf(tip.id), lanes(tip, own, failed, show = ShowOnly.TIPS).todayIds())
        val needs = lanes(tip, own, failed, show = ShowOnly.NEEDS_YOU)
        assertEquals(listOf(failed.id), needs.needsYou.map { it.row.event.id })
        assertTrue(needs.today.isEmpty())
    }

    @Test
    fun healthCountsTodayAndTheNoteSummarisesIt() {
        val l = lanes(ev(call), ev(sell, state = DeliveryState.FAILED), ev("Bought 10 INFY executed", state = DeliveryState.NOT_APPLICABLE), ev(call + " z", postedAt = at(0, 11)))
        assertEquals(CaptureHealth(today = 3, sent = 1, waiting = 0, failed = 1, kept = 1, lastDeliveredAt = null), l.health)
        assertEquals("Captured · 3 today · 1 needs you", capturedNote(l))
    }

    // Review: labels differing only in case shared a LazyColumn key; a post-close copy folded into a pre-close row.
    @Test
    fun caseCollidingLabelsShareOneStackAndCopiesNeverFoldAcrossLanes() {
        val stacks = lanes(ev(call, title = "Tips"), ev(sell, title = "tips")).today
        assertEquals(1, stacks.size)
        val before = ev(call, postedAt = at(2, 14), title = "G1")
        val after = ev(call, postedAt = at(2, 16), title = "G2")
        val l = lanes(before, after, now = at(2, 17))
        assertEquals(listOf(after.id), l.todayIds())
        assertEquals(listOf(before.id), l.earlierIds())
    }
}
