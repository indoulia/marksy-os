package com.marksy.os.market

/** What the stock page says about Marksy's calls on one instrument; [None] means show nothing. */
sealed interface MarksyCallView {
    data class Active(val primary: InstrumentPredictionEntryDto, val others: List<InstrumentPredictionEntryDto>, val history: List<InstrumentPredictionEntryDto>) : MarksyCallView
    data class HistoryOnly(val history: List<InstrumentPredictionEntryDto>) : MarksyCallView
    data object None : MarksyCallView
}

data class TrackRecord(val total: Int, val hit: Int, val stopped: Int, val expired: Int, val averageReturn: Double?)

/** Presentation rules over Marksy's own prediction lifecycle; outcomes are Marksy's, never recomputed here. */
object MarksyCalls {
    private val HIT = setOf("TARGET_HIT", "SUCCESS")
    private val STOPPED = setOf("STOP_HIT", "FAILURE", "INVALIDATED")
    private val EXPIRED = setOf("EXPIRED", "HORIZON_EXPIRED")

    private fun current(predictions: List<InstrumentPredictionEntryDto>) =
        predictions.filterNot { it.isSupersededByRevision }.sortedByDescending { it.asOf }

    fun view(predictions: List<InstrumentPredictionEntryDto>): MarksyCallView {
        val (open, closed) = current(predictions).partition { !it.isTerminal }
        return when {
            open.isNotEmpty() -> MarksyCallView.Active(open.first(), open.drop(1), closed)
            closed.isNotEmpty() -> MarksyCallView.HistoryOnly(closed)
            else -> MarksyCallView.None
        }
    }

    fun record(history: List<InstrumentPredictionEntryDto>): TrackRecord {
        fun count(set: Set<String>) = history.count { it.outcomeStatus in set || it.lifecycleState in set }
        return TrackRecord(history.size, count(HIT), count(STOPPED), count(EXPIRED), history.mapNotNull { it.realizedReturnPct }.takeIf { it.isNotEmpty() }?.average())
    }

    /** The recommendation whose analysis to show: the leading open call's, else the newest past one's. */
    fun analysisId(predictions: List<InstrumentPredictionEntryDto>): Int? = when (val v = view(predictions)) {
        is MarksyCallView.Active -> v.primary.recommendationId
        is MarksyCallView.HistoryOnly -> v.history.first().recommendationId
        MarksyCallView.None -> null
    }

    fun daysLeft(p: InstrumentPredictionEntryDto): Int? = p.observedDays?.let { (p.horizonDays - it).coerceAtLeast(0) }
}
