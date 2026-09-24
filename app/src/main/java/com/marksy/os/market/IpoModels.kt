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

data class IpoTermsDto(val priceBand: IpoValueDto?, val lotSize: IpoValueDto?, val issueSizeCrore: IpoValueDto?) {
    companion object {
        fun parse(json: JSONObject?): IpoTermsDto? {
            if (json == null) return null
            return IpoTermsDto(
                priceBand = IpoValueDto.parse(json.optJSONObject("priceBand")),
                lotSize = IpoValueDto.parse(json.optJSONObject("lotSize")),
                issueSizeCrore = IpoValueDto.parse(json.optJSONObject("issueSizeCrore"))
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
    val terms: IpoTermsDto?
) {
    companion object {
        fun parse(json: JSONObject) = IpoListItemDto(
            id = json.textOrNull("id") ?: "",
            companyName = json.textOrNull("companyName") ?: "",
            issueName = json.textOrNull("issueName"),
            isSme = json.boolOrFalse("isSme"),
            sector = json.textOrNull("sector"),
            stage = json.textOrNull("stage"),
            opensOn = IpoValueDto.parse(json.optJSONObject("opensOn")),
            closesOn = IpoValueDto.parse(json.optJSONObject("closesOn")),
            listsOn = IpoValueDto.parse(json.optJSONObject("listsOn")),
            terms = IpoTermsDto.parse(json.optJSONObject("terms"))
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

data class IpoDetailDto(val summary: IpoListItemDto, val riskEngineRan: Boolean, val riskRun: IpoRiskRunDto?) {
    companion object {
        fun parse(json: JSONObject) = IpoDetailDto(
            summary = IpoListItemDto.parse(json.optJSONObject("summary") ?: JSONObject()),
            riskEngineRan = json.boolOrFalse("riskEngineRan"),
            riskRun = IpoRiskRunDto.parse(json.optJSONObject("riskRun"))
        )
    }
}

data class IpoHistoryEntryDto(val predictedAt: String, val decision: String?, val expectedReturnPercent: IpoValueDto?) {
    companion object {
        fun parse(json: JSONObject) = IpoHistoryEntryDto(
            predictedAt = json.textOrNull("predictedAt") ?: "",
            decision = json.textOrNull("decision"),
            expectedReturnPercent = IpoValueDto.parse(json.optJSONObject("expectedReturnPercent"))
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
