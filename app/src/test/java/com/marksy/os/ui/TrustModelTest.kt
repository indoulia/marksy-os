package com.marksy.os.ui

import com.marksy.os.market.EntityScorecardDto
import com.marksy.os.market.EntityScorecardListDto
import com.marksy.os.market.HorizonHitDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.ScorecardBodyDto
import com.marksy.os.market.ScorecardCountsDto
import com.marksy.os.market.ScorecardEntity
import com.marksy.os.market.ScorecardFilterEchoDto
import com.marksy.os.market.ScorecardPerformanceDto
import com.marksy.os.market.ScorecardTrustDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustModelTest {
    private fun body(
        score: Int? = null, successful: Int = 0, completed: Int = 0, open: Int = 0, hit: Double? = null, ret: Double? = null,
        wilson: Double? = null, quality: Double? = null, minimum: Int = 10, realization: Double? = null
    ) = ScorecardBodyDto(
        ScorecardCountsDto(completed + open, open, successful, completed - successful, 0, completed, 0, 0, 0, 0),
        ScorecardPerformanceDto(null, null, hit, ret, null, null, null, realization, null),
        ScorecardTrustDto(score, completed, minimum, wilson, quality, 0)
    )

    private fun card(name: String, b: ScorecardBodyDto, entity: String = "channel", engine: Boolean? = null, type: String? = null) =
        EntityScorecardDto(entity, name.hashCode(), name, null, null, b, channelType = type, engine = engine)

    private fun list(vararg items: EntityScorecardDto) =
        MarketDataState.Loaded(EntityScorecardListDto("1", "x", ScorecardFilterEchoDto("LIFETIME", null, null, "first_seen", null, null, null, null, null, ""), items.toList()))

    @Test
    fun wilsonUpperBoundMatchesMarksyApi() {
        // Reference values from marksy-api `wilson_upper_bound` (z = 1.96).
        assertEquals(0.4191166720846763, TrustModel.wilsonUpper(74, 210)!!, 1e-12)
        assertEquals(0.16217343370877002, TrustModel.wilsonUpper(3, 50)!!, 1e-12)
        assertEquals(0.2425007574992425, TrustModel.wilsonUpper(0, 12)!!, 1e-12)
        assertEquals(1.0, TrustModel.wilsonUpper(10, 10)!!, 0.0)
        assertNull(TrustModel.wilsonUpper(0, 0))
    }

    @Test
    fun theHitLineShowsTheLikelyRangeFromTheServerFloorAndTheAppCeiling() {
        val b = body(score = 22, successful = 74, completed = 210, hit = 35.24, wilson = 0.290949, quality = 0.56)
        assertEquals(29 to 42, TrustModel.likely(b))
        assertEquals("35% · likely 29–42%", TrustModel.hitLine(b))
        assertEquals("35%", TrustModel.hitLine(body(completed = 4, successful = 1, hit = 35.0)))
        assertNull(TrustModel.hitLine(body()))
    }

    @Test
    fun theScoreLineNamesTheFloorAndTheReturnFactorTheScoreMultiplies() {
        val t = ScorecardTrustDto(22, 210, 10, 0.290949, 0.5612, 0)
        assertEquals(0.7806, TrustModel.returnFactor(0.5612), 1e-9)
        assertEquals("29% hit floor × 0.78 return factor", TrustModel.factors(t))
        assertEquals(
            "Score = 29% hit-rate floor × 0.78 return factor. The floor is the low end of the likely hit rate, so few tips can’t score high.",
            TrustModel.scoreWhy(t)
        )
        assertNull(TrustModel.factors(t.copy(trustScore = null)))
        assertEquals("A score needs 20 closed tips; 4 so far.", TrustModel.scoreWhy(ScorecardTrustDto(null, 4, 20, null, null, 0)))
        assertEquals("62% of promised", TrustModel.delivered(body(realization = 61.8)))
        assertEquals("−12% of promised", TrustModel.delivered(body(realization = -12.0)))
    }

    @Test
    fun sortsPutTheHighestFirstAndUnknownsLast() {
        val a = card("A", body(score = 30, completed = 40, hit = 40.0, ret = 0.002))
        val b = card("B", body(score = 50, completed = 12, hit = 55.0, ret = null))
        val c = card("C", body(score = 10, completed = 90, hit = null, ret = 0.01))
        val all = listOf(a, b, c)
        assertEquals(listOf("B", "A", "C"), TrustModel.sorted(all, TrustSort.TRUST).map { it.name })
        assertEquals(listOf("B", "A", "C"), TrustModel.sorted(all, TrustSort.HIT_RATE).map { it.name })
        assertEquals(listOf("C", "A", "B"), TrustModel.sorted(all, TrustSort.TIPS).map { it.name })
        assertEquals(listOf("C", "A", "B"), TrustModel.sorted(all, TrustSort.RETURN).map { it.name })
    }

    @Test
    fun unscoredSourcesFoldApartClosestToAScoreFirstWithTheServerMinimum() {
        val scored = card("Scored", body(score = 5, completed = 30))
        val near = card("Near", body(completed = 5, open = 9, minimum = 20))
        val fresh = card("Fresh", body(completed = 0, open = 6, minimum = 20))
        val split = TrustModel.split(listOf(fresh, scored, near))
        assertEquals(listOf("Scored"), split.scored.map { it.name })
        assertEquals(listOf("Near", "Fresh"), split.unscored.map { it.name })
        assertEquals(0.25f to "5 of 20 closed · 9 open", TrustModel.progress(near.body))
        assertEquals(0f to "3 closed · 1 open", TrustModel.progress(body(completed = 3, open = 1, minimum = 0)))
        assertEquals("30 closed", TrustModel.closedLine(scored.body))
        assertEquals("210 closed · 35% hit", TrustModel.closedLine(body(completed = 210, successful = 74, hit = 35.24)))
    }

    @Test
    fun theBestHorizonIsTheHighestHitRateAmongHorizonsWithEnoughClosedTips() {
        val buckets = listOf(
            HorizonHitDto("INTRADAY", 100.0, 2), HorizonHitDto("UP_TO_1_WEEK", 37.0, 74),
            HorizonHitDto("UP_TO_1_MONTH", 42.4, 48), HorizonHitDto("LONGER_THAN_1_MONTH", null, 0)
        )
        assertEquals("Up to 1 month · 42%", TrustModel.bestHorizon(buckets, minimum = 10))
        assertNull(TrustModel.bestHorizon(buckets, minimum = 80))
        assertNull(TrustModel.bestHorizon(emptyList(), minimum = 10))
    }

    @Test
    fun countsShowOnlyOnceTheirListHasLoadedAndFinfluencersLeaveEnginesToTheirTab() {
        val engine = card("Prediction engine", body(score = 6), entity = "caller", engine = true)
        val person = card("Rahul", body(score = 23), entity = "caller", engine = false)
        val channels = list(card("Swing", body()), card("Pharma", body()))
        val callers = list(engine, person)

        assertNull(TrustModel.count(ScorecardEntity.CHANNEL, MarketDataState.Loading, callers))
        assertNull(TrustModel.count(ScorecardEntity.CALLER, channels, MarketDataState.Loading))
        assertNull(TrustModel.count(ScorecardEntity.ENGINE, channels, MarketDataState.Error("down")))
        assertEquals(2, TrustModel.count(ScorecardEntity.CHANNEL, channels, MarketDataState.Loading))
        assertEquals(1, TrustModel.count(ScorecardEntity.CALLER, channels, callers))
        assertEquals(1, TrustModel.count(ScorecardEntity.ENGINE, channels, callers))
        assertEquals(0, TrustModel.count(ScorecardEntity.CALLER, channels, MarketDataState.Empty))
        assertEquals(listOf("Rahul"), TrustModel.members(ScorecardEntity.CALLER, callers.value.items).map { it.name })
        assertEquals(listOf("Prediction engine"), TrustModel.members(ScorecardEntity.ENGINE, callers.value.items).map { it.name })
    }

    @Test
    fun marksyComparesAgainstItsOwnChannel() {
        val marksy = card("Marksy", body(), type = "MARKSY")
        assertEquals(marksy, TrustModel.marksyChannel(listOf(card("Swing", body(), type = "TELEGRAM_CHANNEL"), marksy)))
        assertNull(TrustModel.marksyChannel(listOf(card("Swing", body()))))
    }
}
