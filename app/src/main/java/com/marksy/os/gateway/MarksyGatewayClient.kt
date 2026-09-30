package com.marksy.os.gateway

interface MarksyGatewayClient {
    /** Records one captured message as the signed-in customer's receipt (tip-ledger spec §5.1). */
    suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight>

    /** The app packages the server wants captured. */
    suspend fun captureList(): Result<Set<String>>
}

/**
 * Safe default until the existing Marksy Gateway exposes a confirmed Android-facing contract.
 * It prevents accidental network traffic rather than inventing an endpoint.
 */
class UnconfiguredMarksyGatewayClient : MarksyGatewayClient {
    override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> = notConfigured()

    override suspend fun captureList(): Result<Set<String>> = notConfigured()

    private fun <T> notConfigured(): Result<T> = Result.failure(IllegalStateException("Marksy Gateway endpoint is not configured"))
}
