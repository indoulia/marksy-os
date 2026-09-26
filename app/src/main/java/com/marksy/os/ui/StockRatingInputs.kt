package com.marksy.os.ui

import com.marksy.os.market.InstrumentPredictionEntryDto
import com.marksy.os.market.MarksyCallView
import com.marksy.os.market.MarksyCalls
import com.marksy.os.rating.RatingInputs
import com.marksy.os.upstox.FinancialSeries
import com.marksy.os.upstox.Seasonality
import com.marksy.os.upstox.Signal
import com.marksy.os.upstox.Technicals
import com.marksy.os.upstox.UpstoxCandles
import com.marksy.os.upstox.UpstoxFundamentals
import java.time.LocalDate
import java.time.ZoneId

/** Maps what the stock page already holds into the rating engine's plain numbers; the only app-specific part of the rating. */
internal object StockRatingInputs {
    fun from(live: StockLive, f: StockFundamentals, predictions: List<InstrumentPredictionEntryDto>?, today: LocalDate, zone: ZoneId): RatingInputs {
        val readings = Technicals.rate(live.daily)?.all
        val nifty = live.index.lastOrNull()?.let { UpstoxCandles.returns(live.index, it.close) }
        val call = (predictions?.let(MarksyCalls::view) as? MarksyCallView.Active)?.primary
        fun ratio(name: String) = f.ratios.firstOrNull { it.name.equals(name, ignoreCase = true) }
        fun growth(series: List<FinancialSeries>, category: String): Double? {
            val h = series.firstOrNull { it.category == category }?.history ?: return null
            if (h.size < 2 || h[1].value <= 0) return null
            return (h[0].value - h[1].value) / h[1].value * 100
        }
        fun change(category: String): Double? = f.shareholding.firstOrNull { it.category == category }?.history?.takeIf { it.size >= 2 }?.let { it[0].value - it[1].value }
        val season = live.monthly.takeIf { it.size >= 13 }?.let { Seasonality.summary(Seasonality.table(it, zone), today.monthValue) }
        return RatingInputs(
            price = live.quote?.lastPrice,
            technicalBull = readings?.count { it.signal == Signal.BULLISH }, technicalBear = readings?.count { it.signal == Signal.BEARISH }, technicalTotal = readings?.size,
            return3m = live.returns["3M"], nifty3m = nifty?.get("3M"), yearLow = live.yearRange?.first, yearHigh = live.yearRange?.second,
            callUpsidePct = call?.let { c -> c.targetPrice?.takeIf { c.entryPrice > 0 }?.let { (it - c.entryPrice) / c.entryPrice * 100 } },
            callProbability = call?.probabilityAtPublication?.let { if (it > 1) it / 100 else it },
            pe = ratio("P/E")?.companyValue, sectorPe = ratio("P/E")?.sectorValue, pb = ratio("P/B")?.companyValue, sectorPb = ratio("P/B")?.sectorValue,
            evEbitda = ratio("EV/EBITDA")?.companyValue, sectorEvEbitda = ratio("EV/EBITDA")?.sectorValue,
            roe = ratio("ROE")?.companyValue, sectorRoe = ratio("ROE")?.sectorValue, roce = ratio("ROCE")?.companyValue, sectorRoce = ratio("ROCE")?.sectorValue,
            leverage = UpstoxFundamentals.dupont(f.yearly, f.balance)?.multiplier,
            revenueGrowthYoy = growth(f.yearly, "revenue"), profitGrowthYoy = growth(f.yearly, "net_profit"),
            promoterChange = change("promoters"), fiiChange = change("fii"), mutualFundChange = change("mutual_funds"),
            volatility = Technicals.volatility(live.daily), beta = Technicals.beta(live.daily, live.index, zone),
            monthAverage = season?.average, monthNegativeShare = season?.let { it.negative.toDouble() / it.years }
        )
    }
}
