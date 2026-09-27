package com.marksy.os.rating

import kotlin.math.roundToInt

/** Tunes the short-term weights against how Marksy's past calls actually did; adopts them only if they win on calls held out. */
object RatingCalibration {
    /** Inputs as they stood on the call's date (no later data) and the return the call went on to make. */
    data class Sample(val inputs: RatingInputs, val realizedReturn: Double)

    data class Result(
        val config: RatingConfig, val adopted: Boolean,
        val train: Double, val test: Double, val baseTest: Double,
        val samples: Int, val testSamples: Int
    )

    // Point-in-time data exists only for these; fundamentals, ownership and the call's probability have no history, so they keep their weights.
    private val TUNED = listOf(Factor.TREND, Factor.SEASONALITY)
    const val MIN_GAIN = .02
    const val MIN_TEST = 30

    /** Short-term score without the coverage floor: past calls lack fundamentals, so V1 would call them "not enough data". */
    fun score(s: Sample, config: RatingConfig): Double =
        RatingEngine.rate(s.inputs, config.copy(minCoverage = 0.0)).ratings.first { it.horizon == Horizon.SHORT }.score

    /** [samples] oldest first: the newest 30% are held out, so the check runs on calls after those it was tuned on. */
    fun calibrate(samples: List<Sample>, base: RatingConfig = RatingConfig.V1, version: String = base.version + "-cal", step: Double = .05): Result {
        val split = (samples.size * .7).toInt()
        val train = samples.take(split)
        val test = samples.drop(split)
        fun metric(set: List<Sample>, cfg: RatingConfig) = spearman(set.map { score(it, cfg) }, set.map { it.realizedReturn })
        val baseShort = base.weights.getValue(Horizon.SHORT)
        val steps = (TUNED.sumOf { baseShort[it] ?: 0.0 } / step).roundToInt()
        var best = base
        var bestTrain = metric(train, base)
        for (t in 0..steps) {
            val w = baseShort + mapOf(Factor.TREND to t * step, Factor.SEASONALITY to (steps - t) * step)
            val cfg = base.copy(version = version, weights = base.weights + (Horizon.SHORT to w))
            val m = metric(train, cfg)
            if (m > bestTrain + 1e-9) { best = cfg; bestTrain = m }
        }
        val baseTest = metric(test, base)
        val bestTest = metric(test, best)
        // A gain inside the noise of the held-out sample (about 1/√n for a rank correlation) is not evidence.
        val needed = maxOf(MIN_GAIN, 1.0 / kotlin.math.sqrt(test.size.toDouble()))
        val adopted = best != base && test.size >= MIN_TEST && bestTest > 0 && bestTest > baseTest + needed
        return Result(if (adopted) best else base, adopted, bestTrain, bestTest, baseTest, samples.size, test.size)
    }

    fun spearman(a: List<Double>, b: List<Double>): Double {
        if (a.size != b.size || a.size < 3) return 0.0
        val ra = ranks(a); val rb = ranks(b)
        val ma = ra.average(); val mb = rb.average()
        val cov = ra.indices.sumOf { (ra[it] - ma) * (rb[it] - mb) }
        val va = ra.sumOf { (it - ma) * (it - ma) }; val vb = rb.sumOf { (it - mb) * (it - mb) }
        return if (va == 0.0 || vb == 0.0) 0.0 else cov / kotlin.math.sqrt(va * vb)
    }

    /** Average ranks, so ties share their rank. */
    private fun ranks(v: List<Double>): List<Double> {
        val order = v.indices.sortedBy { v[it] }
        val r = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
            val rank = (i + j) / 2.0
            for (k in i..j) r[order[k]] = rank
            i = j + 1
        }
        return r.toList()
    }
}
