package com.marksy.os.rating

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.tanh

// Pure Kotlin on purpose: no Android or app types, so the Marksy backend can run the same engine.

/** Plain numbers the rating needs; any may be missing. Percentages are in percent, [callProbability] is 0..1. */
data class RatingInputs(
    val price: Double? = null,
    val technicalBull: Int? = null, val technicalBear: Int? = null, val technicalTotal: Int? = null,
    val return3m: Double? = null, val nifty3m: Double? = null, val yearLow: Double? = null, val yearHigh: Double? = null,
    val callUpsidePct: Double? = null, val callProbability: Double? = null,
    val pe: Double? = null, val sectorPe: Double? = null, val pb: Double? = null, val sectorPb: Double? = null,
    val evEbitda: Double? = null, val sectorEvEbitda: Double? = null,
    val roe: Double? = null, val sectorRoe: Double? = null, val roce: Double? = null, val sectorRoce: Double? = null, val leverage: Double? = null,
    val revenueGrowthYoy: Double? = null, val profitGrowthYoy: Double? = null,
    val promoterChange: Double? = null, val fiiChange: Double? = null, val mutualFundChange: Double? = null,
    val volatility: Double? = null, val beta: Double? = null,
    val monthAverage: Double? = null, val monthNegativeShare: Double? = null
)

enum class Horizon(val label: String) { SHORT("Short term"), LONG("Long term") }
enum class Verdict(val label: String) { BUY("Buy"), HOLD("Hold"), SELL("Sell"), NOT_ENOUGH_DATA("Not enough data") }
enum class Factor(val label: String) {
    TREND("Trend & momentum"), MARKSY_CALL("Marksy call"), VALUATION("Valuation"), QUALITY("Quality"),
    GROWTH("Growth"), OWNERSHIP("Ownership"), RISK("Risk"), SEASONALITY("Seasonality")
}

/** One factor's view from -1 (bearish) to +1 (bullish), with the reason in words. */
data class FactorScore(val factor: Factor, val score: Double, val reason: String)
data class Contribution(val factor: Factor, val weighted: Double, val reason: String)

data class HorizonRating(
    val horizon: Horizon, val verdict: Verdict, val score: Double, val confidence: Double, val coverage: Double,
    val support: List<Contribution>, val against: List<Contribution>
)

data class RatingResult(val version: String, val factors: List<FactorScore>, val ratings: List<HorizonRating>)

/** Weights per horizon (risk is not weighted: it shrinks the score and the confidence instead). */
data class RatingConfig(
    val version: String,
    val weights: Map<Horizon, Map<Factor, Double>>,
    val buyAt: Double = .3,
    val sellAt: Double = -.3,
    val minCoverage: Double = .5,
    val riskShrink: Double = .3
) {
    companion object {
        val V1 = RatingConfig(
            version = "marksy-rating-1",
            weights = mapOf(
                Horizon.SHORT to mapOf(
                    Factor.TREND to .35, Factor.MARKSY_CALL to .30, Factor.VALUATION to .05, Factor.QUALITY to .05,
                    Factor.GROWTH to .05, Factor.OWNERSHIP to .10, Factor.SEASONALITY to .10
                ),
                Horizon.LONG to mapOf(
                    Factor.TREND to .10, Factor.MARKSY_CALL to .10, Factor.VALUATION to .25, Factor.QUALITY to .25,
                    Factor.GROWTH to .20, Factor.OWNERSHIP to .10, Factor.SEASONALITY to .0
                )
            )
        )
    }
}

object RatingEngine {
    fun rate(inputs: RatingInputs, config: RatingConfig = RatingConfig.V1): RatingResult {
        val factors = listOfNotNull(trend(inputs), call(inputs), valuation(inputs), quality(inputs), growth(inputs), ownership(inputs), seasonality(inputs))
        val risk = risk(inputs)
        val penalty = risk?.let { -it.score } ?: 0.0
        val ratings = Horizon.entries.map { h -> horizon(h, factors, penalty, config) }
        return RatingResult(config.version, factors + listOfNotNull(risk), ratings)
    }

    private fun horizon(h: Horizon, factors: List<FactorScore>, penalty: Double, config: RatingConfig): HorizonRating {
        val weights = config.weights.getValue(h)
        val used = factors.mapNotNull { f -> weights[f.factor]?.takeIf { it > 0 }?.let { w -> f to w } }
        val coverage = used.sumOf { it.second }
        if (coverage < config.minCoverage || coverage == 0.0) return HorizonRating(h, Verdict.NOT_ENOUGH_DATA, 0.0, 0.0, coverage, emptyList(), emptyList())
        val weighted = used.map { (f, w) -> Contribution(f.factor, w * f.score / coverage, f.reason) }
        val raw = weighted.sumOf { it.weighted }
        val score = raw * (1 - config.riskShrink * penalty)
        val spread = weighted.sumOf { abs(it.weighted) }
        val agreement = if (spread == 0.0) 0.0 else abs(raw) / spread
        val verdict = when { score >= config.buyAt -> Verdict.BUY; score <= config.sellAt -> Verdict.SELL; else -> Verdict.HOLD }
        return HorizonRating(
            h, verdict, score, coverage * agreement * (1 - .5 * penalty), coverage,
            weighted.filter { it.weighted > 0 }.sortedByDescending { it.weighted }.take(3),
            weighted.filter { it.weighted < 0 }.sortedBy { it.weighted }.take(3)
        )
    }

    /** Weighted mean of the parts that exist, re-scaled to the weights actually available. */
    private fun blend(vararg parts: Pair<Double?, Double>): Double? {
        val present = parts.filter { it.first != null }
        val total = present.sumOf { it.second }
        return if (present.isEmpty() || total == 0.0) null else present.sumOf { it.first!! * it.second } / total
    }

    private fun f(v: Double, digits: Int = 1) = String.format(Locale.US, "%.${digits}f", v)
    private fun signed(v: Double) = String.format(Locale.US, "%+.1f", v)
    private fun clamp(v: Double) = v.coerceIn(-1.0, 1.0)

    private fun trend(i: RatingInputs): FactorScore? {
        val technical = if (i.technicalTotal != null && i.technicalTotal > 0 && i.technicalBull != null && i.technicalBear != null)
            (i.technicalBull - i.technicalBear).toDouble() / i.technicalTotal else null
        val relative = if (i.return3m != null && i.nifty3m != null) tanh((i.return3m - i.nifty3m) / 10) else null
        val position = if (i.price != null && i.yearLow != null && i.yearHigh != null && i.yearHigh > i.yearLow)
            2 * ((i.price - i.yearLow) / (i.yearHigh - i.yearLow)).coerceIn(0.0, 1.0) - 1 else null
        val score = blend(technical to .6, relative to .3, position to .1) ?: return null
        val reason = listOfNotNull(
            technical?.let { "technicals ${i.technicalBull} bullish vs ${i.technicalBear} bearish" },
            relative?.let { "3M ${signed(i.return3m!!)}% vs NIFTY ${signed(i.nifty3m!!)}%" },
            position?.let { "${Math.round((it + 1) / 2 * 100)}% up its 52-week range" }
        ).joinToString(", ").replaceFirstChar { it.uppercase() }
        return FactorScore(Factor.TREND, clamp(score), reason)
    }

    private fun call(i: RatingInputs): FactorScore? {
        val upside = i.callUpsidePct ?: return null
        val probability = i.callProbability?.coerceIn(0.0, 1.0) ?: return null
        return FactorScore(Factor.MARKSY_CALL, sign(upside) * probability, "Target ${signed(upside)}% at ${Math.round(probability * 100)}% probability")
    }

    private fun valuation(i: RatingInputs): FactorScore? {
        // Half the sector multiple scores +1, double scores -1; a loss-making P/E is -1.
        fun relative(company: Double?, sector: Double?, allowLoss: Boolean): Double? = when {
            company == null || sector == null || sector <= 0 -> null
            company <= 0 -> if (allowLoss) -1.0 else null
            else -> clamp(-ln(company / sector) / ln(2.0))
        }
        val pe = relative(i.pe, i.sectorPe, allowLoss = true)
        val pb = relative(i.pb, i.sectorPb, allowLoss = false)
        val ev = relative(i.evEbitda, i.sectorEvEbitda, allowLoss = false)
        val score = blend(pe to 1.0, pb to 1.0, ev to 1.0) ?: return null
        val reason = listOfNotNull(
            pe?.let { "P/E ${f(i.pe!!)} vs sector ${f(i.sectorPe!!)}" }, pb?.let { "P/B ${f(i.pb!!, 2)} vs ${f(i.sectorPb!!, 2)}" },
            ev?.let { "EV/EBITDA ${f(i.evEbitda!!)} vs ${f(i.sectorEvEbitda!!)}" }
        ).joinToString(", ")
        return FactorScore(Factor.VALUATION, score, reason)
    }

    private fun quality(i: RatingInputs): FactorScore? {
        val roe = if (i.roe != null && i.sectorRoe != null) tanh((i.roe - i.sectorRoe) / 10) else null
        val roce = if (i.roce != null && i.sectorRoce != null) tanh((i.roce - i.sectorRoce) / 10) else null
        var score = blend(roe to 1.0, roce to 1.0) ?: return null
        val leveraged = i.leverage != null && i.leverage > 3
        if (leveraged) score -= .3
        val reason = listOfNotNull(
            roe?.let { "ROE ${f(i.roe!!)}% vs sector ${f(i.sectorRoe!!)}%" }, roce?.let { "ROCE ${f(i.roce!!)}% vs ${f(i.sectorRoce!!)}%" },
            if (leveraged) "high leverage ${f(i.leverage!!, 2)}×" else null
        ).joinToString(", ")
        return FactorScore(Factor.QUALITY, clamp(score), reason)
    }

    private fun growth(i: RatingInputs): FactorScore? {
        val revenue = i.revenueGrowthYoy?.let { tanh(it / 20) }
        val profit = i.profitGrowthYoy?.let { tanh(it / 20) }
        val score = blend(revenue to .4, profit to .6) ?: return null
        val reason = listOfNotNull(revenue?.let { "revenue ${signed(i.revenueGrowthYoy!!)}%" }, profit?.let { "profit ${signed(i.profitGrowthYoy!!)}%" })
            .joinToString(", ").replaceFirstChar { it.uppercase() } + " year on year"
        return FactorScore(Factor.GROWTH, score, reason)
    }

    private fun ownership(i: RatingInputs): FactorScore? {
        val parts = listOfNotNull(i.promoterChange?.let { it * 1.0 }, i.fiiChange?.let { it * .7 }, i.mutualFundChange?.let { it * .7 })
        if (parts.isEmpty()) return null
        val reason = listOfNotNull(
            i.promoterChange?.let { "promoters ${signed(it)}pp" }, i.fiiChange?.let { "FII ${signed(it)}pp" }, i.mutualFundChange?.let { "mutual funds ${signed(it)}pp" }
        ).joinToString(", ").replaceFirstChar { it.uppercase() } + " last quarter"
        return FactorScore(Factor.OWNERSHIP, tanh(parts.sum() / 2), reason)
    }

    private fun seasonality(i: RatingInputs): FactorScore? {
        val avg = i.monthAverage ?: return null
        val negative = i.monthNegativeShare ?: return null
        return FactorScore(Factor.SEASONALITY, clamp(tanh(avg / 5) * .5 + (.5 - negative)), "This month averages ${signed(avg)}%, down in ${Math.round(negative * 100)}% of years")
    }

    /** Score is minus the risk penalty (0 calm .. 1 very volatile); it never adds direction. */
    private fun risk(i: RatingInputs): FactorScore? {
        val vol = i.volatility ?: return null
        val penalty = ((vol - 20) / 40).coerceIn(0.0, 1.0)
        val band = when { penalty == 0.0 -> "low"; penalty < .5 -> "moderate"; else -> "high" }
        return FactorScore(Factor.RISK, -penalty, "Volatility ${f(vol)}% ($band)" + (i.beta?.let { ", beta ${f(it, 2)}" } ?: ""))
    }
}
