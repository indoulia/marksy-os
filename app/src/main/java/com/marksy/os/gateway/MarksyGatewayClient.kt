package com.marksy.os.gateway

import com.marksy.os.data.local.NotificationEventEntity

interface MarksyGatewayClient {
    suspend fun analyze(request: MarksyTradingEventRequest): Result<MarksyInsight>
}

/**
 * Safe default until the existing Marksy Gateway exposes a confirmed Android-facing contract.
 * It prevents accidental network traffic rather than inventing an endpoint.
 */
class UnconfiguredMarksyGatewayClient : MarksyGatewayClient {
    override suspend fun analyze(request: MarksyTradingEventRequest): Result<MarksyInsight> =
        Result.failure(
            IllegalStateException("Marksy Gateway endpoint is not configured")
        )
}

fun NotificationEventEntity.toMarksyTradingEventRequest(): MarksyTradingEventRequest? {
    if (!isTrading) return null

    return MarksyTradingEventRequest(
        eventId = id,
        source = sourceName,
        sourcePackage = sourcePackage,
        title = title,
        body = body,
        category = category,
        priority = priority,
        confidence = confidence,
        occurredAt = postedAt,
        idempotencyKey = sourceKey
    )
}
