package com.marksy.os.market

import org.json.JSONArray
import org.json.JSONObject

/** The one envelope every IPO fact travels in (`IpoValueOut` server-side): `value`'s shape
 * varies by field (number, string, object) by the server contract's own design. */
data class IpoValueDto(val state: String, val value: Any?, val asOf: String?) {
    companion object {
        fun parse(json: JSONObject?): IpoValueDto? {
            if (json == null) return null
            val value = if (json.isNull("value")) null else json.opt("value")
            return IpoValueDto(state = json.textOrNull("state") ?: "UNAVAILABLE", value = value, asOf = json.textOrNull("asOf"))
        }
    }
}

/** Short text for a fact: "₹120–126" for a band, "29 Sep" for a date, the number or text otherwise. */
fun IpoValueDto?.display(prefix: String = ""): String? {
    val v = this?.value ?: return null
    fun n(x: Any?) = (x as? Number)?.let(IpoDetailFormatter::number)
    return when (v) {
        is Number -> prefix + n(v)
        is String -> runCatching { java.time.LocalDate.parse(v).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)) }.getOrDefault(v)
        is JSONObject -> {
            val bounds = listOf("min" to "max", "low" to "high", "lower" to "upper", "from" to "to").firstOrNull { (a, b) -> v.opt(a) is Number && v.opt(b) is Number }
            bounds?.let { (a, b) -> "$prefix${n(v.opt(a))}–${n(v.opt(b))}" } ?: v.keys().asSequence().mapNotNull { k -> n(v.opt(k)) ?: v.optString(k).takeIf { !v.isNull(k) && it.isNotBlank() } }.joinToString(" / ").ifBlank { null }
        }
        is JSONArray -> (0 until v.length()).joinToString(", ") { v.opt(it).toString() }.ifBlank { null }
        else -> v.toString()
    }
}

data class IpoTermsDto(
    val priceBand: IpoValueDto?, val lotSize: IpoValueDto?, val issueSizeCrore: IpoValueDto?,
    val minInvestment: IpoValueDto? = null, val freshIssueCrore: IpoValueDto? = null, val offerForSaleCrore: IpoValueDto? = null,
    val exchanges: IpoValueDto? = null
) {
    companion object {
        fun parse(json: JSONObject?): IpoTermsDto? {
            if (json == null) return null
            return IpoTermsDto(
                priceBand = IpoValueDto.parse(json.optJSONObject("priceBand")),
                lotSize = IpoValueDto.parse(json.optJSONObject("lotSize")),
                issueSizeCrore = IpoValueDto.parse(json.optJSONObject("issueSizeCrore")),
                minInvestment = IpoValueDto.parse(json.optJSONObject("minInvestment")),
                freshIssueCrore = IpoValueDto.parse(json.optJSONObject("freshIssueCrore")),
                offerForSaleCrore = IpoValueDto.parse(json.optJSONObject("offerForSaleCrore")),
                exchanges = IpoValueDto.parse(json.optJSONObject("exchanges"))
            )
        }
    }
}

data class IpoListItemDto(
    val id: String,
    val companyName: String,
    val issueName: String?,
    val isSme: Boolean,
    val sector: String?,
    val stage: String?,
    val opensOn: IpoValueDto?,
    val closesOn: IpoValueDto?,
    val listsOn: IpoValueDto?,
    val terms: IpoTermsDto?,
    val raw: JSONObject? = null,
    val gmp: IpoGmpDto? = null,
    val subscription: IpoSubscriptionDto? = null,
    val retailAllocation: IpoAllocationDto? = null
) {
    companion object {
        fun parse(json: JSONObject) = IpoListItemDto(
            raw = json,
            id = json.textOrNull("id") ?: "",
            companyName = json.textOrNull("companyName") ?: "",
            issueName = json.textOrNull("issueName"),
            isSme = json.boolOrFalse("isSme"),
            sector = json.textOrNull("sector"),
            stage = json.textOrNull("stage"),
            opensOn = IpoValueDto.parse(json.optJSONObject("opensOn")),
            closesOn = IpoValueDto.parse(json.optJSONObject("closesOn")),
            listsOn = IpoValueDto.parse(json.optJSONObject("listsOn")),
            terms = IpoTermsDto.parse(json.optJSONObject("terms")),
            gmp = IpoGmpDto.parse(json.optJSONObject("gmp")),
            subscription = IpoSubscriptionDto.parse(json.optJSONObject("subscription")),
            retailAllocation = IpoAllocationDto.parse(json.optJSONObject("retailAllocationEstimate"))
        )
        fun parseList(array: JSONArray?) = array.objects().map(::parse)
    }
}

data class IpoRiskRunDto(val status: String, val ranAt: String?, val findingsCount: Int) {
    companion object {
        fun parse(json: JSONObject?): IpoRiskRunDto? {
            if (json == null) return null
            return IpoRiskRunDto(status = json.textOrNull("status") ?: "UNKNOWN", ranAt = json.textOrNull("ranAt"), findingsCount = json.intOrNull("findingsCount") ?: 0)
        }
    }
}

/** [raw] keeps every section the backend sends (anchor book, peers, analyst views…) for the detail page. */
data class IpoDetailDto(
    val summary: IpoListItemDto, val riskEngineRan: Boolean, val riskRun: IpoRiskRunDto?, val raw: JSONObject? = null,
    val keyDates: List<IpoKeyDate> = emptyList(), val decisionContexts: List<IpoDecisionContext> = emptyList(),
    val outcome: IpoOutcomeDto? = null, val anchorCrore: Double? = null, val overview: IpoValueDto? = null
) {
    companion object {
        fun parse(json: JSONObject) = IpoDetailDto(
            summary = IpoListItemDto.parse(json.optJSONObject("summary") ?: JSONObject()),
            riskEngineRan = json.boolOrFalse("riskEngineRan"),
            riskRun = IpoRiskRunDto.parse(json.optJSONObject("riskRun")),
            raw = json,
            keyDates = json.optJSONArray("keyDates").objects().map { IpoKeyDate(it.textOrNull("label") ?: "", IpoValueDto.parse(it.optJSONObject("date"))) },
            decisionContexts = json.optJSONArray("decisionContexts").objects().map(IpoDecisionContext::parse),
            outcome = IpoOutcomeDto.parse(json.optJSONObject("outcome")),
            anchorCrore = json.optJSONObject("anchorBook")?.opt("totalAmountCrore").asDouble(),
            overview = IpoValueDto.parse(json.optJSONObject("companyOverview"))
        )
    }
}

data class IpoHistoryEntryDto(val predictedAt: String, val decision: String?, val expectedReturnPercent: IpoValueDto?, val raw: JSONObject? = null) {
    companion object {
        fun parse(json: JSONObject) = IpoHistoryEntryDto(
            predictedAt = json.textOrNull("predictedAt") ?: "",
            decision = json.textOrNull("decision"),
            expectedReturnPercent = IpoValueDto.parse(json.optJSONObject("expectedReturnPercent")),
            raw = json
        )
        fun parseList(array: JSONArray?) = array.objects().map(::parse)
    }
}

data class IpoAttentionItemDto(val ipoId: String, val companyName: String, val kind: String, val detail: String) {
    companion object {
        fun parse(json: JSONObject) = IpoAttentionItemDto(
            ipoId = json.textOrNull("ipoId") ?: "",
            companyName = json.textOrNull("companyName") ?: "",
            kind = json.textOrNull("kind") ?: "",
            detail = json.textOrNull("detail") ?: ""
        )
        fun parseList(array: JSONArray?) = array.objects().map(::parse)
    }
}

data class IpoStageCountsDto(val byStage: Map<String, Int>, val total: Int) {
    companion object {
        fun parse(json: JSONObject): IpoStageCountsDto {
            val byStage = json.optJSONObject("byStage")
            val map = byStage?.keys()?.asSequence()?.associateWith { byStage.optInt(it) } ?: emptyMap()
            return IpoStageCountsDto(byStage = map, total = json.intOrNull("total") ?: 0)
        }
    }
}

/** One IPO the signed-in reader watches (`GET /ipos/tracked`); [stage] is the reader's, [issueStage] the issue's. */
data class IpoTrackedItemDto(val ipoId: String, val companyName: String, val stage: String?, val issueStage: String?) {
    companion object {
        fun parse(json: JSONObject) = IpoTrackedItemDto(
            ipoId = json.textOrNull("ipoId") ?: "",
            companyName = json.textOrNull("companyName") ?: "",
            stage = json.textOrNull("stage"),
            issueStage = json.textOrNull("issueStage")
        )
        fun parseList(array: JSONArray?) = array.objects().map(::parse).filter { it.ipoId.isNotBlank() }
    }
}

/** The answer to both watch and unwatch; idempotent, so a retried toggle reads [tracking]. */
data class IpoTrackingStateDto(val ipoId: String, val tracking: Boolean) {
    companion object {
        fun parse(json: JSONObject) = IpoTrackingStateDto(json.textOrNull("ipoId") ?: "", json.boolOrFalse("tracking"))
    }
}

internal fun Any?.asDouble(): Double? = when (this) { is Number -> toDouble(); is String -> toDoubleOrNull(); else -> null }

/** The fact as a number; Marksy sends some decimals as strings. */
fun IpoValueDto?.number(): Double? = this?.value.asDouble()

fun IpoValueDto?.localDate(): java.time.LocalDate? =
    (this?.value as? String)?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }

/** (lower, upper) of a band-shaped fact. */
fun IpoValueDto?.bounds(): Pair<Double, Double>? {
    val o = this?.value as? JSONObject ?: return null
    return listOf("lower" to "upper", "low" to "high", "min" to "max", "from" to "to").firstNotNullOfOrNull { (a, b) ->
        val lo = o.opt(a).asDouble()
        val hi = o.opt(b).asDouble()
        if (lo != null && hi != null) lo to hi else null
    }
}

/** Why a fact can't be read as current, or null when it can. */
fun IpoValueDto?.stateNote(): String? = when {
    this == null || value == null -> when (this?.state) { "INSUFFICIENT_EVIDENCE" -> "not enough evidence"; "CONFLICTING" -> "sources disagree"; else -> "not out yet" }
    state == "STALE" -> IpoLifecycle.dateOf(asOf)?.let { "as of ${IpoLifecycle.dm(it)}" } ?: "may be out of date"
    state == "CONFLICTING" -> "sources disagree"
    state == "INSUFFICIENT_EVIDENCE" -> "not enough evidence"
    else -> null
}

data class IpoGmpReading(val source: String, val premium: Double, val premiumPercent: Double?, val observedAt: String?)

/** Every source's current quote; there is deliberately no single "the GMP". */
data class IpoGmpDto(val state: String, val readings: List<IpoGmpReading>) {
    companion object {
        fun parse(json: JSONObject?): IpoGmpDto? = json?.let { g ->
            IpoGmpDto(g.textOrNull("state") ?: "UNAVAILABLE", g.optJSONArray("readings").objects().mapNotNull { r ->
                r.opt("premium").asDouble()?.let { IpoGmpReading(r.textOrNull("source") ?: "Unknown", it, r.opt("premiumPercent").asDouble(), r.textOrNull("observedAt")) }
            })
        }
    }
}

data class IpoSubscriptionReading(val times: Double, val observedAt: String?)

data class IpoSubscriptionDto(val state: String, val asOf: String?, val series: Map<String, List<IpoSubscriptionReading>>, val latest: Map<String, IpoSubscriptionReading>) {
    companion object {
        private fun reading(o: JSONObject?) = o?.opt("timesSubscribed").asDouble()?.let { IpoSubscriptionReading(it, o?.textOrNull("observedAt")) }
        fun parse(json: JSONObject?): IpoSubscriptionDto? {
            if (json == null) return null
            val series = json.optJSONObject("series")
            val latest = json.optJSONObject("latest")
            return IpoSubscriptionDto(
                state = json.textOrNull("state") ?: "UNAVAILABLE", asOf = json.textOrNull("asOf"),
                series = series?.keys()?.asSequence()?.associateWith { k -> series.optJSONArray(k).objects().mapNotNull(::reading) }.orEmpty(),
                latest = latest?.keys()?.asSequence()?.mapNotNull { k -> reading(latest.optJSONObject(k))?.let { k to it } }?.toMap().orEmpty()
            )
        }
    }
}

data class IpoAllocationDto(val probability: Double?, val oversubscription: Double?) {
    companion object {
        fun parse(json: JSONObject?): IpoAllocationDto? = json?.let {
            IpoAllocationDto(IpoValueDto.parse(it.optJSONObject("probability")).number(), IpoValueDto.parse(it.optJSONObject("oversubscription")).number())
        }
    }
}

data class IpoKeyDate(val label: String, val date: IpoValueDto?)

data class IpoDecisionContext(val context: String, val question: String?, val verdict: String, val confidence: Double?, val reason: String?, val answeredBy: String?) {
    companion object {
        fun parse(json: JSONObject): IpoDecisionContext {
            fun first(key: String): String? = json.optJSONArray(key)?.let { a ->
                (0 until a.length()).firstNotNullOfOrNull { i -> a.opt(i).let { (it as? JSONObject)?.textOrNull("description") ?: (it as? String)?.takeIf(String::isNotBlank) } }
            }
            return IpoDecisionContext(
                context = json.textOrNull("context") ?: "", question = json.textOrNull("question"), verdict = json.textOrNull("verdict") ?: "NO_DECISION",
                confidence = IpoValueDto.parse(json.optJSONObject("confidence")).number(),
                reason = first("supporting") ?: first("opposing") ?: first("uncertainties"), answeredBy = json.textOrNull("answeredBy")
            )
        }
    }
}

data class IpoOutcomeDto(val issuePrice: Double?, val listingPrice: Double?, val listingReturnPercent: Double?, val expectedReturnPercent: Double?) {
    companion object {
        fun parse(json: JSONObject?): IpoOutcomeDto? = json?.let { o ->
            fun n(k: String) = IpoValueDto.parse(o.optJSONObject(k)).number()
            IpoOutcomeDto(n("issuePrice"), n("listingPrice"), n("listingReturnPercent"), n("expectedReturnPercent"))
        }
    }
}
