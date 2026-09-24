package com.marksy.os.gateway

import org.junit.Assert.assertThrows
import org.junit.Test

class RealAuthApiClientTest {
    @Test
    fun rejectsNonHttpsBaseUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            RealAuthApiClient(baseUrl = "http://insecure.example.com/api/v1")
        }
    }
}
