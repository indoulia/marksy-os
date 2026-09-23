package com.marksy.os.gateway

import org.json.JSONObject

/** Gateway configuration decoded from a scanned QR code. */
data class GatewayQrPayload(val integrationKey: String?, val baseUrl: String?) {
    val hasAny: Boolean get() = !integrationKey.isNullOrBlank() || !baseUrl.isNullOrBlank()
}

/**
 * Decodes the admin-app gateway QR payload, canonical form
 * {"integrationKey":"...","baseUrl":"..."}. Tolerant of field aliases and, as a
 * fallback, treats a non-JSON scan as the raw integration key.
 */
object GatewayQrParser {
    private val KEY_FIELDS = listOf("integrationKey", "key", "apiKey")
    private val URL_FIELDS = listOf("baseUrl", "url", "base_url")

    fun parse(scanned: String?): GatewayQrPayload {
        val raw = scanned?.trim().orEmpty()
        if (raw.isEmpty()) return GatewayQrPayload(null, null)

        if (raw.startsWith("{")) {
            runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
                return GatewayQrPayload(
                    integrationKey = json.firstNonBlank(KEY_FIELDS),
                    baseUrl = json.firstNonBlank(URL_FIELDS)
                )
            }
        }

        // Not JSON (or unparseable): the QR carried the bare integration key.
        return GatewayQrPayload(integrationKey = raw, baseUrl = null)
    }

    private fun JSONObject.firstNonBlank(fields: List<String>): String? =
        fields.asSequence()
            .map { optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }
}
