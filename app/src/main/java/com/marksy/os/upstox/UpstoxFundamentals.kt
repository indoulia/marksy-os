package com.marksy.os.upstox

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.math.sqrt

/** A ratio as Upstox formats it ("20.15", "4.39%"); the numeric forms are for Marksy's own calculations. */
data class KeyRatio(val name: String, val company: String, val sector: String?) {
    val companyValue: Double? get() = UpstoxFundamentals.number(company)
    val sectorValue: Double? get() = sector?.let(UpstoxFundamentals::number)
}

data class CompanyProfile(val about: String?, val sector: String?, val marketCapCr: Double?)
data class FinancialPeriod(val period: String, val value: Double, val change: String?)
data class FinancialSeries(val category: String, val history: List<FinancialPeriod>)
data class BalancePeriod(val period: String, val totalAssets: Double, val totalLiabilities: Double)
data class CorporateAction(val name: String, val date: String?, val amount: Double?, val ratio: String?, val details: List<Pair<String, String>>)
data class Peer(val instrumentKey: String, val sector: String?, val marketCapCr: Double?)
data class NewsItem(val heading: String, val summary: String?, val link: String?, val publishedAt: Long)

/** ROE split into net margin × asset turnover × equity multiplier for one fiscal period. */
data class DuPont(val period: String, val margin: Double, val turnover: Double, val multiplier: Double) {
    val roe: Double get() = margin * turnover * multiplier
}

/** Parsers for Upstox's `/v2/fundamentals/{isin}/…` and `/v2/news` responses, plus calculations built on them. */
object UpstoxFundamentals {
    fun ratios(body: String): List<KeyRatio> = root(body).optJSONArray("data").objects().mapNotNull { o ->
        val name = o.text("name") ?: return@mapNotNull null
        KeyRatio(name, o.text("company_value") ?: "–", o.text("sector_value"))
    }

    fun profile(body: String): CompanyProfile {
        val d = root(body).optJSONObject("data") ?: JSONObject()
        return CompanyProfile(d.text("company_profile"), d.text("sector"), d.optJSONObject("sector_market_cap_inr")?.num("value"))
    }

    /** [key] is `income_statement` or `cash_flow`. */
    fun statement(body: String, key: String): List<FinancialSeries> =
        series(root(body).optJSONObject("data")?.optJSONArray(key))

    fun balance(body: String): List<BalancePeriod> = root(body).optJSONObject("data")?.optJSONArray("history").objects().mapNotNull { o ->
        val period = o.text("period") ?: return@mapNotNull null
        BalancePeriod(period, o.num("total_asset") ?: return@mapNotNull null, o.num("total_liability") ?: return@mapNotNull null)
    }

    fun shareholding(body: String): List<FinancialSeries> = series(root(body).optJSONArray("data"))

    fun actions(body: String): List<CorporateAction> = root(body).optJSONArray("data").objects().mapNotNull { o ->
        val name = o.text("name") ?: return@mapNotNull null
        val details = o.optJSONArray("event_details").objects().mapNotNull { d -> d.text("name")?.let { n -> d.text("value")?.let { n to it } } }
        CorporateAction(name, o.text("expiry_date"), o.num("amount"), o.text("ratio"), details)
    }

    fun peers(body: String): List<Peer> = root(body).optJSONArray("data").objects().mapNotNull { o ->
        Peer(o.text("instrument_key") ?: return@mapNotNull null, o.text("sector"), o.optJSONObject("sector_market_cap_inr")?.num("value"))
    }

    fun news(body: String, instrumentKey: String): List<NewsItem> =
        root(body).optJSONObject("data")?.optJSONArray(instrumentKey).objects().mapNotNull { o ->
            NewsItem(o.text("heading") ?: return@mapNotNull null, o.text("summary"), o.text("article_link"), o.optLong("published_time"))
        }

    /** "4.39%" -> 4.39, "1,234.5" -> 1234.5; dashes and blanks are null. */
    fun number(text: String): Double? = text.replace(",", "").removeSuffix("%").trim().toDoubleOrNull()

    /** √(22.5 × EPS × BVPS), with EPS = price ÷ P/E and BVPS = price ÷ P/B; meaningless unless both are positive. */
    fun grahamNumber(price: Double, pe: Double, pb: Double): Double? =
        if (price <= 0 || pe <= 0 || pb <= 0) null else sqrt(22.5 * (price / pe) * (price / pb))

    fun dupont(yearlyIncome: List<FinancialSeries>, balance: List<BalancePeriod>): DuPont? {
        val revenue = yearlyIncome.firstOrNull { it.category == "revenue" }?.history.orEmpty().associateBy { it.period }
        val profit = yearlyIncome.firstOrNull { it.category == "net_profit" }?.history.orEmpty().associateBy { it.period }
        val b = balance.firstOrNull { it.period in revenue && it.period in profit } ?: return null
        val sales = revenue.getValue(b.period).value
        val equity = b.totalAssets - b.totalLiabilities
        if (sales <= 0 || b.totalAssets <= 0 || equity <= 0) return null
        return DuPont(b.period, profit.getValue(b.period).value / sales, sales / b.totalAssets, b.totalAssets / equity)
    }

    private fun series(array: JSONArray?): List<FinancialSeries> = array.objects().mapNotNull { o ->
        val category = o.text("category") ?: return@mapNotNull null
        FinancialSeries(category, o.optJSONArray("history").objects().mapNotNull { h ->
            FinancialPeriod(h.text("period") ?: return@mapNotNull null, h.num("value") ?: return@mapNotNull null, h.text("change"))
        })
    }

    private fun root(body: String): JSONObject = try {
        JSONObject(body).also { r ->
            if (r.optString("status") != "success") {
                val message = r.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
                throw IOException("Upstox: ${message ?: "request failed"}")
            }
        }
    } catch (e: JSONException) {
        throw IOException("Upstox returned an unreadable response", e)
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
    private fun JSONObject.num(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null
}
