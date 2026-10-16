package com.marksy.os.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marksy.os.MarksyFormat
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.ClosedPredictionDto
import com.marksy.os.market.EntityScorecardDto
import com.marksy.os.market.LatestScanDto
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.MyTipDto
import com.marksy.os.market.PicksBasis
import com.marksy.os.market.ScorecardBodyDto
import com.marksy.os.market.ScorecardEntity
import com.marksy.os.market.ScorecardPeriod
import com.marksy.os.market.ScorecardQuery
import com.marksy.os.market.ScorecardSource
import com.marksy.os.market.ScorecardSources
import com.marksy.os.upstox.UpstoxLtp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A cursor-paged list that grows as its last row scrolls into view; with [key], a row a later page repeats is kept once (a repeated list key crashes the list). */
@Stable
internal class Paged<T>(private val key: ((T) -> Any)? = null, private val fetch: suspend (String?) -> MarketDataState<Pair<List<T>, String?>>) {
    var items by mutableStateOf<List<T>>(emptyList())
    var state by mutableStateOf<MarketDataState<Unit>>(MarketDataState.Loading)
    var hasMore by mutableStateOf(true)
    /** A later page failed, so the list stopped short of its end. */
    var failed by mutableStateOf(false)
    /** The up-front page reads ended, so a count of [items] is final rather than a first page. */
    var settled by mutableStateOf(false)
    private var cursor: String? = null
    private var busy = false

    suspend fun more() {
        if (busy || !hasMore) return
        busy = true
        when (val r = fetch(cursor)) {
            is MarketDataState.Loaded -> { items = (items + r.value.first).let { all -> key?.let(all::distinctBy) ?: all }; cursor = r.value.second; hasMore = cursor != null; state = MarketDataState.Loaded(Unit) }
            is MarketDataState.Empty -> { hasMore = false; state = if (items.isEmpty()) MarketDataState.Empty else MarketDataState.Loaded(Unit) }
            is MarketDataState.Error -> { hasMore = false; failed = true; if (items.isEmpty()) state = r }
            is MarketDataState.Unavailable -> { hasMore = false; state = r }
            else -> Unit
        }
        busy = false
    }

    val loading: Boolean get() = state is MarketDataState.Loading
}

internal inline fun <T, R> MarketDataState<T>.map(f: (T) -> R): MarketDataState<R> = when (this) {
    is MarketDataState.Loaded -> MarketDataState.Loaded(f(value))
    is MarketDataState.Stale -> MarketDataState.Loaded(f(value))
    is MarketDataState.Error -> this
    MarketDataState.Empty -> MarketDataState.Empty
    MarketDataState.Loading -> MarketDataState.Loading
    MarketDataState.Unavailable -> MarketDataState.Unavailable
}

// The ledger's §8.1 record, not `/performance/summary`: that price-only label record also counts calls withdrawn before entry.
internal val TrackRecordQuery = ScorecardQuery(entity = ScorecardEntity.ENGINE, period = ScorecardPeriod.LAST_30_DAYS)
private val MyRecordQuery = ScorecardQuery(period = ScorecardPeriod.LAST_30_DAYS)
// marksy-api `CALLER_PREDICTION_ENGINE`: the engine whose calls Trading shows.
private const val PREDICTION_ENGINE = "Prediction engine"
// Pages read up front so every view and scope can show its count.
private const val MAX_PAGES = 20

internal fun predictionEngineCard(cards: List<EntityScorecardDto>): EntityScorecardDto? =
    cards.firstOrNull { ScorecardSources.isEngine(it) && it.name == PREDICTION_ENGINE }

/** One ledger call and how many predictions the ledger merged into it (a MERGED_DUPLICATE follower reads the tip it follows). */
internal data class ClosedCall(val row: ClosedPredictionDto, val predictions: Int)

/** Calls the ledger voided before entry sit apart from the rest, which keep their server order. */
internal data class ClosedSections(val shown: List<ClosedCall>, val invalidated: List<ClosedCall>)

// Rows fold only on a shared ledger tip id, never on symbol, outcome or return; a row without a ledger stays its own call.
internal fun closedSections(items: List<ClosedPredictionDto>): ClosedSections {
    val calls = items.groupBy { c -> c.ledger?.tipId?.takeIf { it.isNotEmpty() }?.let { "t:$it" } ?: "p:${c.id}" }
        .values.map { ClosedCall(it.first(), it.size) }
    val (invalidated, shown) = calls.partition { it.row.ledger?.status == LedgerCalls.STATUS_INVALIDATED }
    return ClosedSections(shown, invalidated)
}

/** Open's lists: the customer's tips, the Following feed, or Marksy's bought tips. */
enum class OpenScope(val label: String) { ALL("All"), MINE("Mine"), FOLLOWING("Following"), MARKSY("Marksy") }

/** Setups by Marksy scan: the latest session's, or earlier sessions' still buyable. */
enum class SetupsScope(val label: String) { ALL("All"), TODAY("Today"), WEEK("This week") }

/** History's lists: everything closed, the customer's own tips, or Marksy's. */
enum class HistoryScope(val label: String) { ALL("All"), MINE("Mine"), MARKSY("Marksy") }

/** Every Trading list, read once up front so the views and scopes can show their counts. */
@Stable
internal class TradingFeed(private val repo: MarketIntelligenceRepository) {
    val myOpen = Paged<MyTipDto>({ it.tip.tipId }) { c -> repo.myTips("ACTIVE", c).map { it.items to it.nextCursor } }
    val myClosed = Paged<MyTipDto>({ it.tip.tipId }) { c -> repo.myTips("CLOSED", c).map { it.items to it.nextCursor } }
    // Keyset-paged on a re-rankable score: a re-rank between pages can repeat a call (kept once) or skip one (open marksy-api follow-up).
    val marksyActive = Paged<ActivePredictionDto>({ it.predictionId }) { c -> repo.activePredictions(c).map { it.items to it.nextCursor } }
    val marksyClosed = Paged<ClosedPredictionDto> { c -> repo.closedPredictions(c).map { it.items to it.nextCursor } }
    /** The Prediction engine's last-30-days scorecard: Marksy tips' Trust chip and History's record. */
    var engine by mutableStateOf<EntityScorecardDto?>(null)
    var myRecord by mutableStateOf<ScorecardBodyDto?>(null)

    suspend fun load() = coroutineScope {
        listOf(myOpen, marksyActive, myClosed, marksyClosed).forEach { p -> launch { var pages = 0; while (p.hasMore && pages++ < MAX_PAGES) p.more(); p.settled = true } }
        launch { engine = (repo.scorecards(ScorecardEntity.ENGINE, TrackRecordQuery) as? MarketDataState.Loaded)?.value?.items?.let(::predictionEngineCard) }
        launch { myRecord = (repo.myScorecard(MyRecordQuery) as? MarketDataState.Loaded)?.value?.body }
    }
}

/** Trading's cards for one moment's quotes. */
internal data class TradingLists(
    val mineOpen: List<TipCardModel>,
    val marksyOpen: List<TipCardModel>,
    val setups: List<Pair<TipCardModel, ActivePredictionDto>>,
    val mineHistory: List<TipCardModel>,
    val marksyHistory: List<TipCardModel>,
    val mineVoided: List<TipCardModel>,
    val marksyVoided: List<TipCardModel>
) {
    val open: List<TipCardModel> get() = mineOpen + marksyOpen

    fun open(scope: OpenScope): List<TipCardModel> = when (scope) { OpenScope.MINE -> mineOpen; OpenScope.MARKSY -> marksyOpen; else -> open }

    fun history(scope: HistoryScope): List<TipCardModel> = when (scope) {
        HistoryScope.MINE -> mineHistory
        HistoryScope.MARKSY -> marksyHistory
        HistoryScope.ALL -> (mineHistory + marksyHistory).sortedByDescending { it.at }
    }

    fun voided(scope: HistoryScope): List<TipCardModel> = when (scope) { HistoryScope.MINE -> mineVoided; HistoryScope.MARKSY -> marksyVoided; HistoryScope.ALL -> mineVoided + marksyVoided }

    /** The latest scan's setups are Today's; setups from earlier sessions are This week's. */
    fun setups(scope: SetupsScope, scan: LatestScanDto?): List<TipCardModel> {
        val latest = scan?.scanSessionDate ?: setups.mapNotNull { it.second.scanSessionDate }.maxOrNull()
        return setups.filter { (_, p) ->
            when (scope) {
                SetupsScope.ALL -> true
                SetupsScope.TODAY -> latest != null && p.scanSessionDate == latest
                SetupsScope.WEEK -> latest == null || p.scanSessionDate != latest
            }
        }.map { it.first }
    }

    companion object {
        fun build(
            myOpen: List<MyTipDto>, myClosed: List<MyTipDto>, active: List<ActivePredictionDto>, closed: List<ClosedPredictionDto>,
            engine: EntityScorecardDto?, quotes: Map<String, UpstoxLtp>, today: LocalDate
        ): TradingLists {
            val engineSource = engine?.let(ScorecardSource::of)
            fun now(symbol: String) = quotes[symbol]?.lastPrice
            val (live, ended) = active.partition(LedgerCalls::isLive)
            // A live call waits for its entry in Setups; once bought it is an open tip. Without a ledger it stays a setup.
            val (waiting, bought) = live.partition { p -> p.ledger?.let(TipTrack::waiting) ?: true }
            val sections = closedSections(closed)
            val closedTips = closed.mapNotNull { it.ledger?.tipId?.takeIf(String::isNotEmpty) }.toSet()
            // An ended call the closed list already has is shown once, from the closed list.
            val endedCards = ended.filter { p -> p.ledger?.tipId?.takeIf(String::isNotEmpty)?.let { it !in closedTips } ?: true }
                .map { TipCards.marksy(it, now(it.symbol), today, engine) }
            val mineClosed = myClosed.map { TipCards.mine(it, now(it.tip.symbol), today) }
            return TradingLists(
                mineOpen = myOpen.map { TipCards.mine(it, now(it.tip.symbol), today) },
                marksyOpen = bought.map { TipCards.marksy(it, now(it.symbol), today, engine) },
                setups = waiting.sortedWith(compareByDescending<ActivePredictionDto> { it.isActionableNow }.thenByDescending { it.compositeOpportunityScore ?: -1.0 })
                    .map { TipCards.marksy(it, now(it.symbol), today, engine) to it },
                mineHistory = mineClosed.filterNot { it.voided },
                marksyHistory = sections.shown.map { TipCards.closed(it, now(it.row.symbol), engine) } + endedCards.filterNot { it.voided },
                mineVoided = mineClosed.filter { it.voided },
                marksyVoided = sections.invalidated.map { TipCards.closed(it, now(it.row.symbol), engine) } + endedCards.filter { it.voided }
            )
        }

        private fun tone(v: Double?) = when { v == null || v == 0.0 -> TrackTone.NEUTRAL; v > 0 -> TrackTone.POSITIVE; else -> TrackTone.NEGATIVE }

        /** Open: how the live tips stand from entry, and how many still wait to be bought. */
        fun openSummary(cards: List<TipCardModel>): TradingSummary {
            val returns = cards.mapNotNull { it.returnFraction }
            val waiting = cards.count { it.ledger?.let(TipTrack::waiting) == true }
            val avg = returns.takeIf { it.isNotEmpty() }?.average()
            return TradingSummary(
                "LIVE · FROM ENTRY",
                listOfNotNull("${returns.count { it > 0 }} up", "${returns.count { it < 0 }} down", "$waiting waiting to buy".takeIf { waiting > 0 }).joinToString(" · "),
                avg?.let { MarksyFormat.percent(it * 100, 1) }, tone(avg)
            )
        }

        /** The tab already counts the setups, so the strip only says where they come from. */
        fun setupsSummary(count: Int, scan: LatestScanDto?): TradingSummary = TradingSummary(
            "MARKSY · STILL BUYABLE", scan?.let(PicksBasis::label)?.let { "from $it" } ?: "from the latest scan", null, TrackTone.NEUTRAL
        )

        /** History: the server's last-30-days record, never one recomputed on the phone. */
        fun historySummary(r: ScorecardBodyDto?): TradingSummary? {
            val c = r?.counts ?: return null
            if (c.completed == 0) return null
            val avg = r.performance.avgActualReturn
            return TradingSummary(
                "LAST 30 DAYS · ${c.completed} COMPLETED",
                listOfNotNull(
                    "${c.successful} succeeded", "${c.failed} failed", "${c.expired} expired",
                    r.performance.hitRatePct?.let { MarksyFormat.percent(it, 1, signed = false) + " hit" }
                ).joinToString(" · "),
                TipTrack.percent(avg), tone(avg)
            )
        }
    }
}
