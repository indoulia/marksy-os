package com.marksy.os.rating

/** Where a stock's rating comes from: computed on the device today; a Marksy endpoint returning the same [RatingResult] later. */
fun interface RatingSource {
    suspend fun rating(symbol: String, inputs: RatingInputs): RatingResult
}

object LocalRatingSource : RatingSource {
    override suspend fun rating(symbol: String, inputs: RatingInputs): RatingResult = RatingEngine.rate(inputs)
}
