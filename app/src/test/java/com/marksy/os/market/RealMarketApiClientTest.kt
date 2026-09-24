package com.marksy.os.market

import org.junit.Assert.assertThrows
import org.junit.Test

class RealMarketApiClientTest {
    @Test
    fun rejectsNonHttpsBaseUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            RealMarketApiClient(apiKey = "k", baseUrl = "http://insecure.example.com/api/v1")
        }
    }

    @Test
    fun rejectsBlankApiKeyAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            RealMarketApiClient(apiKey = "  ", baseUrl = "https://marksy.indoulia.com/api/v1")
        }
    }
}
