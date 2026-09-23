package com.marksy.os.gateway

import com.marksy.os.AppContext
import com.marksy.os.BuildConfig

/** Single construction point for the Marksy API client. */
object MarksyGatewayProvider {
    fun client(): MarksyGatewayClient {
        val store = SecureCredentialStore(AppContext.get())
        val key = store.getIntegrationKey() ?: return UnconfiguredMarksyGatewayClient()
        val baseUrl = (store.getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        // A malformed stored base URL must not crash delivery: refuse it and stay unconfigured.
        return runCatching { MarksyTipsApiClient(key, baseUrl) as MarksyGatewayClient }
            .getOrElse { UnconfiguredMarksyGatewayClient() }
    }
}
