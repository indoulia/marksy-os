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

    /** Null until the gateway is provisioned; market data is read-only and optional. */
    fun marketClient(): MarksyTipsApiClient? = client() as? MarksyTipsApiClient

    /** Null until the market API key is provisioned; every Market screen must degrade to
     * its own Unavailable state rather than crash when this is null. */
    fun marketIntelligenceClient(): com.marksy.os.market.MarketApiClient? {
        val store = SecureCredentialStore(AppContext.get())
        val key = store.getMarketApiKey() ?: return null
        val baseUrl = (store.getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        return runCatching { com.marksy.os.market.RealMarketApiClient(key, baseUrl) as com.marksy.os.market.MarketApiClient }.getOrNull()
    }
}
