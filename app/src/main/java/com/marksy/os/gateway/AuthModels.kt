package com.marksy.os.gateway

import org.json.JSONObject

data class SessionResponseDto(
    val sessionToken: String,
    val userId: String,
    val issuedAt: String,
    val expiresAt: String,
    val readOnly: Boolean
) {
    companion object {
        fun parse(envelope: JSONObject): SessionResponseDto {
            val data = envelope.getJSONObject("data")
            return SessionResponseDto(
                sessionToken = data.optString("sessionToken", ""),
                userId = data.optString("userId", ""),
                issuedAt = data.optString("issuedAt", ""),
                expiresAt = data.optString("expiresAt", ""),
                readOnly = data.optBoolean("readOnly", false)
            )
        }
    }
}
