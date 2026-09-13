package com.marksy.os.gateway

import com.marksy.os.BuildConfig

/** Single construction point for the Marksy API client. */
object MarksyGatewayProvider {
    fun client(): MarksyGatewayClient =
        BuildConfig.MARKSY_INTEGRATION_KEY.trim().takeIf { it.isNotBlank() }
            ?.let { MarksyTipsApiClient(it, BuildConfig.MARKSY_API_BASE_URL.trimEnd('/')) }
            ?: UnconfiguredMarksyGatewayClient()
}
