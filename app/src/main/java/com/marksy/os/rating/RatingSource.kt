package com.marksy.os.rating

/** Where a stock's rating comes from: computed on the device today; a Marksy endpoint returning the same [RatingResult] later. */
fun interface RatingSource {
    suspend fun rating(symbol: String, inputs: RatingInputs): RatingResult
}

object LocalRatingSource : RatingSource {
    override suspend fun rating(symbol: String, inputs: RatingInputs): RatingResult = RatingEngine.rate(inputs, ActiveRating.config)
}

/** The weights in use: V1 until a calibration against past calls wins on held-out calls. */
object ActiveRating {
    @Volatile var config: RatingConfig = RatingConfig.V1
}
