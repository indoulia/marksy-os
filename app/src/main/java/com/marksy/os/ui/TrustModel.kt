package com.marksy.os.ui

import com.marksy.os.MarksyFormat
import com.marksy.os.market.EntityScorecardDto
import com.marksy.os.market.EntityScorecardListDto
import com.marksy.os.market.HorizonBucket
import com.marksy.os.market.HorizonHitDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.ScorecardBodyDto
import com.marksy.os.market.ScorecardEntity
import com.marksy.os.market.ScorecardSources
import com.marksy.os.market.ScorecardTrustDto
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How the Trust list orders its scored sources, each high to low. */
internal enum class TrustSort(val label: String) { TRUST("Trust"), HIT_RATE("Hit rate"), TIPS("Tips"), RETURN("Return") }

internal data class TrustSplit(val scored: List<EntityScorecardDto>, val unscored: List<EntityScorecardDto>)

/** Words and orders for the Trust tab and a source's record; only the hit-rate ceiling is computed here. */
internal object TrustModel {
    private const val WILSON_Z = 1.96

    fun tabLabel(e: ScorecardEntity): String = if (e == ScorecardEntity.CALLER) "Finfluencers" else e.label

    /** marksy-api `wilson_upper_bound`: the same interval whose lower bound the server sends. */
    fun wilsonUpper(successes: Int, n: Int, z: Double = WILSON_Z): Double? {
        if (n <= 0) return null
        val p = successes.toDouble() / n
        val z2 = z * z
        val margin = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n))
        return min(1.0, (p + z2 / (2.0 * n) + margin) / (1 + z2 / n))
    }

    /** The server's score is round(100 × Wilson floor × this). */
    fun returnFactor(quality: Double): Double = 0.5 + 0.5 * quality

    private fun whole(fraction: Double): Int = (fraction * 100).roundToInt()

    private fun pct(v: Double): String = MarksyFormat.percent(v, 0, signed = false)

    fun likely(b: ScorecardBodyDto): Pair<Int, Int>? {
        val low = b.trust.wilsonLowerBound ?: return null
        val high = wilsonUpper(b.counts.successful, b.counts.completed) ?: return null
        return whole(low) to whole(high)
    }

    fun hitLine(b: ScorecardBodyDto): String? {
        val hit = b.performance.hitRatePct ?: return null
        return pct(hit) + (likely(b)?.let { (l, h) -> " · likely $l–$h%" } ?: "")
    }

    fun factors(t: ScorecardTrustDto, floor: String = "hit floor"): String? {
        if (t.trustScore == null) return null
        val wilson = t.wilsonLowerBound ?: return null
        val quality = t.returnQuality ?: return null
        return "${whole(wilson)}% $floor × ${MarksyFormat.number(returnFactor(quality), 2)} return factor"
    }

    fun scoreWhy(t: ScorecardTrustDto): String = factors(t, "hit-rate floor")
        ?.let { "Score = $it. The floor is the low end of the likely hit rate, so few tips can’t score high." }
        ?: if (t.minimumCompleted > 0) "A score needs ${t.minimumCompleted} closed tips; ${t.completed} so far." else "Not scored yet."

    fun delivered(b: ScorecardBodyDto): String? = b.performance.returnRealizationPct?.let { "${pct(it)} of promised" }

    private val bySort: Map<TrustSort, Comparator<EntityScorecardDto>> = mapOf(
        TrustSort.TRUST to compareByDescending(nullsFirst()) { it.body.trust.trustScore },
        TrustSort.HIT_RATE to compareByDescending(nullsFirst()) { it.body.performance.hitRatePct },
        TrustSort.TIPS to compareByDescending { it.body.counts.completed },
        TrustSort.RETURN to compareByDescending(nullsFirst()) { it.body.performance.avgActualReturn }
    )

    fun sorted(items: List<EntityScorecardDto>, sort: TrustSort): List<EntityScorecardDto> =
        items.sortedWith(bySort.getValue(sort).thenByDescending { it.body.counts.completed }.thenBy { it.name })

    fun split(items: List<EntityScorecardDto>): TrustSplit {
        val (scored, unscored) = items.partition { it.body.trust.trustScore != null }
        return TrustSplit(scored, unscored.sortedWith(compareByDescending<EntityScorecardDto> { it.body.counts.completed }.thenByDescending { it.body.counts.open }))
    }

    /** An unscored source's way to a score: closed share of the server's minimum, and its words. */
    fun progress(b: ScorecardBodyDto): Pair<Float, String> {
        val done = b.counts.completed
        val need = b.trust.minimumCompleted
        val open = " · ${b.counts.open} open"
        return if (need > 0) (done.toFloat() / need).coerceIn(0f, 1f) to "$done of $need closed$open" else 0f to "$done closed$open"
    }

    fun closedLine(b: ScorecardBodyDto): String =
        "${b.counts.completed} closed" + (b.performance.hitRatePct?.let { " · ${pct(it)} hit" } ?: "")

    fun horizonLabel(name: String): String = HorizonBucket.entries.firstOrNull { it.name == name }?.label ?: name

    /** Only horizons with as many closed tips as a score needs compete, so two lucky tips never win. */
    fun bestHorizon(byHorizon: List<HorizonHitDto>, minimum: Int): String? = byHorizon
        .filter { it.hitRatePct != null && it.completed > 0 && it.completed >= minimum }
        .maxWithOrNull(compareBy<HorizonHitDto> { it.hitRatePct }.thenBy { it.completed })
        ?.let { "${horizonLabel(it.horizon)} · ${pct(it.hitRatePct!!)}" }

    /** One callers read serves two tabs: engines, and everyone else as finfluencers; Marksy's channel lives in the compare strip. */
    fun members(entity: ScorecardEntity, items: List<EntityScorecardDto>): List<EntityScorecardDto> = when (entity) {
        ScorecardEntity.CHANNEL -> items.filterNot { it == marksyChannel(items) }
        ScorecardEntity.CALLER -> items.filterNot(ScorecardSources::isEngine)
        ScorecardEntity.ENGINE -> items.filter(ScorecardSources::isEngine)
    }

    fun state(entity: ScorecardEntity, channels: MarketDataState<EntityScorecardListDto>, callers: MarketDataState<EntityScorecardListDto>) =
        if (entity == ScorecardEntity.CHANNEL) channels else callers

    /** Null until the tab's list has loaded, so a count never shows a partial number. */
    fun count(entity: ScorecardEntity, channels: MarketDataState<EntityScorecardListDto>, callers: MarketDataState<EntityScorecardListDto>): Int? =
        when (val s = state(entity, channels, callers)) {
            is MarketDataState.Loaded -> members(entity, s.value.items).size
            MarketDataState.Empty -> 0
            else -> null
        }

    fun marksyChannel(channels: List<EntityScorecardDto>): EntityScorecardDto? =
        channels.firstOrNull { it.entity == ScorecardEntity.CHANNEL.param && ScorecardSources.chip(it) == "Engine" }
}
