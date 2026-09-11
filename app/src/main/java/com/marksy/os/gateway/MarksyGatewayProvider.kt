package com.marksy.os.gateway

/**
 * Single construction point for the gateway client.
 *
 * Keep this unconfigured until the existing Marksy Gateway exposes a confirmed
 * Android-facing endpoint, authentication method, and wire contract.
 */
object MarksyGatewayProvider {
    fun client(): MarksyGatewayClient = UnconfiguredMarksyGatewayClient()
}
