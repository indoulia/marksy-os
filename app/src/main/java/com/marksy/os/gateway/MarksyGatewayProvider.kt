package com.marksy.os.gateway

import android.content.Context
import com.marksy.os.BuildConfig

/** Single construction point for the Marksy API client. */
object MarksyGatewayProvider {
    fun client(context: Context): MarksyGatewayClient {
        val key = SecureCredentialStore(context).getIntegrationKey()
        return key?.let { MarksyTipsApiClient(it, BuildConfig.MARKSY_API_BASE_URL.trimEnd('/')) }
            ?: UnconfiguredMarksyGatewayClient()
    }
}
