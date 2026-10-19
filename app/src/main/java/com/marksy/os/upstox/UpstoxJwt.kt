package com.marksy.os.upstox

import org.json.JSONObject
import java.util.Base64

/** Token expiry for display and the sandbox cut-off; reads a JWT's `exp` without checking its signature. */
object UpstoxJwt {
    const val SANDBOX_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_AHEAD_MS = 400L * 24 * 60 * 60 * 1000

    fun expiresAt(token: String): Long? = runCatching {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val exp = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).optLong("exp", 0L)
        if (exp > 0) exp * 1000 else null
    }.getOrNull()

    /** A readable `exp` up to 400 days ahead (past ones included, so an expired token stays expired), else 30 days from [now]. */
    fun sandboxExpiry(token: String, now: Long): Long =
        expiresAt(token)?.takeIf { it <= now + MAX_AHEAD_MS } ?: (now + SANDBOX_VALIDITY_MS)
}
