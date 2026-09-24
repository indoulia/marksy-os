package com.marksy.os.gateway

import com.marksy.os.AppContext
import com.marksy.os.BuildConfig

/** Single construction point for the Marksy API clients, both authenticated
 * via the real session login (see `AuthRepository`) rather than a static key. */
object MarksyGatewayProvider {
    private fun authRepository(): AuthRepository {
        val baseUrl = (SecureCredentialStore(AppContext.get()).getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        return AuthRepository(RealAuthApiClient(baseUrl), AuthSessionStore(AppContext.get()))
    }

    fun client(): MarksyGatewayClient {
        val baseUrl = (SecureCredentialStore(AppContext.get()).getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        if (AuthSessionStore(AppContext.get()).getToken() == null) return UnconfiguredMarksyGatewayClient()
        return runCatching { MarksyTipsApiClient(authRepository(), baseUrl) as MarksyGatewayClient }
            .getOrElse { UnconfiguredMarksyGatewayClient() }
    }

    /** Null until the user is signed in; market data is read-only and optional. */
    fun marketClient(): MarksyTipsApiClient? = client() as? MarksyTipsApiClient

    /** Null until the user is signed in; every Market screen must degrade to
     * its own Unavailable state rather than crash when this is null. */
    fun marketIntelligenceClient(): com.marksy.os.market.MarketApiClient? {
        if (AuthSessionStore(AppContext.get()).getToken() == null) return null
        val baseUrl = (SecureCredentialStore(AppContext.get()).getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        return runCatching { com.marksy.os.market.RealMarketApiClient(authRepository(), baseUrl) as com.marksy.os.market.MarketApiClient }.getOrNull()
    }
}
