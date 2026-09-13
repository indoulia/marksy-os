package com.marksy.os.gateway

import com.marksy.os.AppContext
import com.marksy.os.BuildConfig

/** Single construction point for the Marksy API client. */
object MarksyGatewayProvider {
    fun client(): MarksyGatewayClient {
        val key = SecureCredentialStore(AppContext.get()).getIntegrationKey()
        return key?.let { MarksyTipsApiClient(it, BuildConfig.MARKSY_API_BASE_URL.trimEnd('/')) }
            ?: UnconfiguredMarksyGatewayClient()
    }
}
