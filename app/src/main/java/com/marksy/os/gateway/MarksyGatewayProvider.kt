package com.marksy.os.gateway

import com.marksy.os.BuildConfig

/** Single construction point for the Marksy API client. */
object MarksyGatewayProvider {
    fun client(): MarksyGatewayClient =
        MarksyTipsApiClient(BuildConfig.MARKSY_INTEGRATION_KEY)
}
