package com.marksy.os.market

import org.json.JSONArray
import org.json.JSONObject

// Tip-ledger reads from marksy-api Phase 3b: decimals arrive as strings, returns are fractions, percentages are 0-100.
private const val MAX_LEDGER_ITEMS = 300

// A 250-session tip has 250 progress rows, beyond the 50 the market lists keep.
private fun JSONArray?.ledgerObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until minOf(length(), MAX_LEDGER_ITEMS)).mapNotNull { optJSONObject(it) }

private fun JSONObject.count(name: String): Int = intOrNull(name) ?: 0

data class ChannelRefDto(val channelId: Int, val name: String, val type: String) {
    companion object {
        fun parse(json: JSONObject) = ChannelRefDto(json.count("channelId"), json.textOrNull("name") ?: "", json.textOrNull("type") ?: "")
    }
}

data class CallerRefDto(val callerId: Int, val name: String) {
    companion object {
        fun parse(json: JSONObject) = CallerRefDto(json.count("callerId"), json.textOrNull("name") ?: "")
    }
}

data class ProgressPointDto(
    val sessionDate: String,
    val sessionIndex: Int,
    val entryStatus: String,
    val statusAfter: String,
    val returnToDate: Double?,
    val bestReturn: Double?,
    val worstReturn: Double?,
    val toTargetPct: Double?,
    val toStopPct: Double?,
    val barBasis: String,
    val dataBasis: String
) {
    companion object {
        fun parse(json: JSONObject) = ProgressPointDto(
            sessionDate = json.textOrNull("sessionDate") ?: "",
            sessionIndex = json.count("sessionIndex"),
            entryStatus = json.textOrNull("entryStatus") ?: "",
            statusAfter = json.textOrNull("statusAfter") ?: "",
            returnToDate = json.doubleOrNull("returnToDate"),
            bestReturn = json.doubleOrNull("bestReturn"),
            worstReturn = json.doubleOrNull("worstReturn"),
            toTargetPct = json.doubleOrNull("toTargetPct"),
            toStopPct = json.doubleOrNull("toStopPct"),
            barBasis = json.textOrNull("barBasis") ?: "",
            dataBasis = json.textOrNull("dataBasis") ?: ""
        )
    }
}

/** The canonical call (spec §4): terms, lifecycle and latest progress; `channel` and `caller` are canonical. */
data class LedgerTipDto(
    val tipId: String,
    val symbol: String,
    val direction: String?,
    val entryLow: Double?,
    val entryHigh: Double?,
    val entryBasis: String?,
    val target: Double?,
    val stopLoss: Double?,
    val horizonSessions: Int?,
    val horizonBasis: String?,
    val firstSeenAt: String,
    val status: String,
    val entryStatus: String?,
    val outcome: String?,
    val reason: String?,
    val enteredSession: Int?,
    val closedSession: Int?,
    val closedAt: String?,
    val exitPrice: Double?,
    val promisedReturn: Double?,
    val actualReturn: Double?,
    val predictionId: Int?,
    val channel: ChannelRefDto?,
    val caller: CallerRefDto?,
    val latestProgress: ProgressPointDto?
) {
    companion object {
        fun parse(json: JSONObject) = LedgerTipDto(
            tipId = json.textOrNull("tipId") ?: "",
            symbol = json.textOrNull("symbol") ?: "",
            direction = json.textOrNull("direction"),
            entryLow = json.doubleOrNull("entryLow"),
            entryHigh = json.doubleOrNull("entryHigh"),
            entryBasis = json.textOrNull("entryBasis"),
            target = json.doubleOrNull("target"),
            stopLoss = json.doubleOrNull("stopLoss"),
            horizonSessions = json.intOrNull("horizonSessions"),
            horizonBasis = json.textOrNull("horizonBasis"),
            firstSeenAt = json.textOrNull("firstSeenAt") ?: "",
            status = json.textOrNull("status") ?: "",
            entryStatus = json.textOrNull("entryStatus"),
            outcome = json.textOrNull("outcome"),
            reason = json.textOrNull("reason"),
            enteredSession = json.intOrNull("enteredSession"),
            closedSession = json.intOrNull("closedSession"),
            closedAt = json.textOrNull("closedAt"),
            exitPrice = json.doubleOrNull("exitPrice"),
            promisedReturn = json.doubleOrNull("promisedReturn"),
            actualReturn = json.doubleOrNull("actualReturn"),
            predictionId = json.intOrNull("predictionId"),
            channel = json.optJSONObject("channel")?.let(ChannelRefDto::parse),
            caller = json.optJSONObject("caller")?.let(CallerRefDto::parse),
            latestProgress = json.optJSONObject("latestProgress")?.let(ProgressPointDto::parse)
        )
    }
}

/** One of the signed-in customer's own receipts; nobody else's is ever served (invariant 10). */
data class ReceiptRefDto(
    val receiptId: String,
    val kind: String,
    val medium: String,
    val channelLabel: String?,
    val appPackage: String?,
    val devicePostedAt: String?,
    val recordedAt: String
) {
    companion object {
        fun parse(json: JSONObject) = ReceiptRefDto(
            receiptId = json.textOrNull("receiptId") ?: "",
            kind = json.textOrNull("kind") ?: "",
            medium = json.textOrNull("medium") ?: "",
            channelLabel = json.textOrNull("channelLabel"),
            appPackage = json.textOrNull("appPackage"),
            devicePostedAt = json.textOrNull("devicePostedAt"),
            recordedAt = json.textOrNull("recordedAt") ?: ""
        )
    }
}

/** §8.2's headline: Lifetime, every horizon, first-seen basis. */
data class ScorecardHeadlineDto(
    val total: Int,
    val open: Int,
    val completed: Int,
    val successful: Int,
    val failed: Int,
    val expired: Int,
    val invalidated: Int,
    val successPct: Double?,
    val hitRatePct: Double?,
    val avgActualReturn: Double?,
    val trustScore: Int?
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardHeadlineDto(
            total = json.count("total"),
            open = json.count("open"),
            completed = json.count("completed"),
            successful = json.count("successful"),
            failed = json.count("failed"),
            expired = json.count("expired"),
            invalidated = json.count("invalidated"),
            successPct = json.doubleOrNull("successPct"),
            hitRatePct = json.doubleOrNull("hitRatePct"),
            avgActualReturn = json.doubleOrNull("avgActualReturn"),
            trustScore = json.intOrNull("trustScore")
        )
    }
}

data class MyTipDto(
    val tip: LedgerTipDto,
    val channelHeadline: ScorecardHeadlineDto?,
    val callerHeadline: ScorecardHeadlineDto?,
    val receivedVia: List<ReceiptRefDto>,
    val alsoReceivedBy: Int
) {
    companion object {
        fun parse(json: JSONObject) = MyTipDto(
            tip = LedgerTipDto.parse(json),
            channelHeadline = json.optJSONObject("channelHeadline")?.let(ScorecardHeadlineDto::parse),
            callerHeadline = json.optJSONObject("callerHeadline")?.let(ScorecardHeadlineDto::parse),
            receivedVia = json.optJSONArray("receivedVia").ledgerObjects().map(ReceiptRefDto::parse),
            alsoReceivedBy = json.count("alsoReceivedBy")
        )
    }
}

data class MyTipPageDto(val items: List<MyTipDto>, val nextCursor: String?) {
    companion object {
        fun parse(envelope: JSONObject) = MyTipPageDto(
            envelope.optJSONArray("data").ledgerObjects().map(MyTipDto::parse),
            envelope.optJSONObject("meta")?.textOrNull("nextCursor")
        )
    }
}

data class EngineCallsDto(val callerId: Int, val name: String, val channelId: Int, val scorecard: ScorecardHeadlineDto, val tips: List<LedgerTipDto>) {
    companion object {
        fun parse(json: JSONObject) = EngineCallsDto(
            json.count("callerId"), json.textOrNull("name") ?: "", json.count("channelId"),
            ScorecardHeadlineDto.parse(json.optJSONObject("scorecard") ?: JSONObject()),
            json.optJSONArray("tips").ledgerObjects().map(LedgerTipDto::parse)
        )
    }
}

data class ChannelCallsDto(val channelId: Int, val name: String, val type: String, val scorecard: ScorecardHeadlineDto, val tips: List<LedgerTipDto>) {
    companion object {
        fun parse(json: JSONObject) = ChannelCallsDto(
            json.count("channelId"), json.textOrNull("name") ?: "", json.textOrNull("type") ?: "",
            ScorecardHeadlineDto.parse(json.optJSONObject("scorecard") ?: JSONObject()),
            json.optJSONArray("tips").ledgerObjects().map(LedgerTipDto::parse)
        )
    }
}

/** `/instruments/{symbol}` `calls` (spec §9): every Marksy engine, and every external channel with a call here. */
data class InstrumentCallsDto(val asOf: String, val closedWithinDays: Int, val engines: List<EngineCallsDto>, val channels: List<ChannelCallsDto>) {
    companion object {
        fun parse(json: JSONObject) = InstrumentCallsDto(
            asOf = json.textOrNull("asOf") ?: "",
            closedWithinDays = json.count("closedWithinDays"),
            engines = json.optJSONObject("marksy")?.optJSONArray("engines").ledgerObjects().map(EngineCallsDto::parse),
            channels = json.optJSONObject("external")?.optJSONArray("channels").ledgerObjects().map(ChannelCallsDto::parse)
        )
    }
}

/** The filter the server applied (§8.3 "Every response echoes the applied filter and the resolved start/end"). */
data class ScorecardFilterEchoDto(
    val period: String,
    val startDate: String?,
    val endDate: String?,
    val basis: String,
    val horizon: String?,
    val channelId: Int?,
    val callerId: Int?,
    val startAt: String?,
    val endAt: String?,
    val asOf: String
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardFilterEchoDto(
            period = json.textOrNull("period") ?: "LIFETIME",
            startDate = json.textOrNull("startDate"),
            endDate = json.textOrNull("endDate"),
            basis = json.textOrNull("basis") ?: "first_seen",
            horizon = json.textOrNull("horizon"),
            channelId = json.intOrNull("channelId"),
            callerId = json.intOrNull("callerId"),
            startAt = json.textOrNull("startAt"),
            endAt = json.textOrNull("endAt"),
            asOf = json.textOrNull("asOf") ?: ""
        )
    }
}

data class ScorecardCountsDto(
    val total: Int,
    val open: Int,
    val successful: Int,
    val failed: Int,
    val expired: Int,
    val completed: Int,
    val exited: Int,
    val invalidated: Int,
    val unscorable: Int,
    val dataUnresolved: Int
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardCountsDto(
            json.count("total"), json.count("open"), json.count("successful"), json.count("failed"), json.count("expired"),
            json.count("completed"), json.count("exited"), json.count("invalidated"), json.count("unscorable"), json.count("dataUnresolved")
        )
    }
}

data class ScorecardPerformanceDto(
    val successPct: Double?,
    val failurePct: Double?,
    val hitRatePct: Double?,
    val avgActualReturn: Double?,
    val totalActualReturn: Double?,
    val avgPromisedReturn: Double?,
    val totalPromisedReturn: Double?,
    val returnRealizationPct: Double?,
    val avgDaysToCompletion: Double?
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardPerformanceDto(
            json.doubleOrNull("successPct"), json.doubleOrNull("failurePct"), json.doubleOrNull("hitRatePct"),
            json.doubleOrNull("avgActualReturn"), json.doubleOrNull("totalActualReturn"), json.doubleOrNull("avgPromisedReturn"),
            json.doubleOrNull("totalPromisedReturn"), json.doubleOrNull("returnRealizationPct"), json.doubleOrNull("avgDaysToCompletion")
        )
    }
}

data class ScorecardTrustDto(
    val trustScore: Int?,
    val completed: Int,
    val minimumCompleted: Int,
    val wilsonLowerBound: Double?,
    val returnQuality: Double?,
    val invalidated: Int
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardTrustDto(
            json.intOrNull("trustScore"), json.count("completed"), json.count("minimumCompleted"),
            json.doubleOrNull("wilsonLowerBound"), json.doubleOrNull("returnQuality"), json.count("invalidated")
        )
    }
}

data class ScorecardBodyDto(val counts: ScorecardCountsDto, val performance: ScorecardPerformanceDto, val trust: ScorecardTrustDto) {
    companion object {
        fun parse(json: JSONObject) = ScorecardBodyDto(
            ScorecardCountsDto.parse(json.optJSONObject("counts") ?: JSONObject()),
            ScorecardPerformanceDto.parse(json.optJSONObject("performance") ?: JSONObject()),
            ScorecardTrustDto.parse(json.optJSONObject("trust") ?: JSONObject())
        )
    }
}

data class ScorecardDto(
    val version: String,
    val scope: String,
    val scopeId: String?,
    val name: String?,
    val channelId: Int?,
    val channelName: String?,
    val filter: ScorecardFilterEchoDto,
    val body: ScorecardBodyDto
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardDto(
            version = json.textOrNull("version") ?: "",
            scope = json.textOrNull("scope") ?: "",
            // An int for channel/caller/engine scopes, null on the customer's own card.
            scopeId = if (json.isNull("scopeId")) null else json.opt("scopeId")?.toString(),
            name = json.textOrNull("name"),
            channelId = json.intOrNull("channelId"),
            channelName = json.textOrNull("channelName"),
            filter = ScorecardFilterEchoDto.parse(json.optJSONObject("filter") ?: JSONObject()),
            body = ScorecardBodyDto.parse(json)
        )
    }
}

data class EntityScorecardDto(val entity: String, val id: Int, val name: String, val channelId: Int?, val channelName: String?, val body: ScorecardBodyDto) {
    companion object {
        fun parse(json: JSONObject) = EntityScorecardDto(
            json.textOrNull("entity") ?: "", json.count("id"), json.textOrNull("name") ?: "",
            json.intOrNull("channelId"), json.textOrNull("channelName"), ScorecardBodyDto.parse(json)
        )
    }
}

data class EntityScorecardListDto(val version: String, val entity: String, val filter: ScorecardFilterEchoDto, val items: List<EntityScorecardDto>) {
    companion object {
        fun parse(json: JSONObject) = EntityScorecardListDto(
            json.textOrNull("version") ?: "", json.textOrNull("entity") ?: "",
            ScorecardFilterEchoDto.parse(json.optJSONObject("filter") ?: JSONObject()),
            json.optJSONArray("items").ledgerObjects().map(EntityScorecardDto::parse)
        )
    }
}

data class ScorecardSummaryDto(val version: String, val filter: ScorecardFilterEchoDto, val all: ScorecardBodyDto, val marksy: ScorecardBodyDto, val external: ScorecardBodyDto) {
    companion object {
        fun parse(json: JSONObject) = ScorecardSummaryDto(
            json.textOrNull("version") ?: "",
            ScorecardFilterEchoDto.parse(json.optJSONObject("filter") ?: JSONObject()),
            ScorecardBodyDto.parse(json.optJSONObject("all") ?: JSONObject()),
            ScorecardBodyDto.parse(json.optJSONObject("marksy") ?: JSONObject()),
            ScorecardBodyDto.parse(json.optJSONObject("external") ?: JSONObject())
        )
    }
}

/** `GET /tips/{id}`'s ledger part: null `ledger` and no progress for a legacy row. */
data class TipDetailDto(val ledger: LedgerTipDto?, val progress: List<ProgressPointDto>) {
    companion object {
        fun parse(json: JSONObject) = TipDetailDto(
            json.optJSONObject("ledger")?.let(LedgerTipDto::parse),
            json.optJSONArray("progress").ledgerObjects().map(ProgressPointDto::parse)
        )
    }
}
