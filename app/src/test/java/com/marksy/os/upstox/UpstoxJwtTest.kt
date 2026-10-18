package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class UpstoxJwtTest {
    private val now = 1_790_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test
    fun readsExpInMilliseconds() {
        assertEquals(1_800_000_000_000L, UpstoxJwt.expiresAt(jwt("""{"exp":1800000000}""")))
    }

    @Test
    fun nonJwtTokensHaveNoExpiry() {
        listOf("opaque-token", "a.!!!.c", jwt("""{"sub":"x"}"""), jwt("not json")).forEach { assertNull(it, UpstoxJwt.expiresAt(it)) }
    }

    @Test
    fun sandboxExpiryUsesExpOnlyWhenPlausible() {
        assertEquals(now + 10 * day, UpstoxJwt.sandboxExpiry(jwt("""{"exp":${(now + 10 * day) / 1000}}"""), now))
        assertEquals(now - day, UpstoxJwt.sandboxExpiry(jwt("""{"exp":${(now - day) / 1000}}"""), now))
        assertEquals(now + 30 * day, UpstoxJwt.sandboxExpiry(jwt("""{"exp":${(now + 500 * day) / 1000}}"""), now))
        assertEquals(now + 30 * day, UpstoxJwt.sandboxExpiry("opaque-token", now))
    }

    @Test
    fun sandboxStatusWarnsInTheLastThreeDays() {
        assertEquals(UpstoxSandboxStatus.State.NONE, UpstoxSandboxStatus.of(null, now).state)
        assertEquals(UpstoxSandboxStatus(UpstoxSandboxStatus.State.VALID, now + 10 * day, 10), UpstoxSandboxStatus.of(now + 10 * day, now))
        assertEquals(UpstoxSandboxStatus.State.VALID, UpstoxSandboxStatus.of(now + 3 * day + 1, now).state)
        assertEquals(UpstoxSandboxStatus(UpstoxSandboxStatus.State.EXPIRING, now + 3 * day, 3), UpstoxSandboxStatus.of(now + 3 * day, now))
        assertEquals(UpstoxSandboxStatus.State.EXPIRED, UpstoxSandboxStatus.of(now, now).state)
    }
}
