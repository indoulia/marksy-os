package com.marksy.os.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/** The single place that decides "is there a usable session right now" --
 * every API client reads through this, never `AuthSessionStore` directly.
 * Refreshes proactively (within [REFRESH_BUFFER_MS] of expiry) because
 * `POST /auth/refresh` only succeeds on a still-valid token; refreshing too
 * late means a hard sign-out instead of a silent renewal. */
class AuthRepository(private val client: AuthApiClient, private val store: AuthSessionStore) {
    suspend fun currentToken(): String? = withContext(Dispatchers.IO) {
        val token = store.getToken() ?: return@withContext null
        val expiresAt = store.getExpiresAtEpochMs() ?: return@withContext null
        val now = System.currentTimeMillis()
        if (expiresAt - now > REFRESH_BUFFER_MS) return@withContext token

        try {
            val refreshed = client.refresh(token)
            val expiresAtMs = parseEpochMs(refreshed.expiresAt) ?: (now + FALLBACK_TTL_MS)
            store.saveSession(refreshed.sessionToken, refreshed.userId, expiresAtMs, store.isRemembered())
            refreshed.sessionToken
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            // Includes the deterministic MRA_SESSION_EXPIRED case: refreshing an
            // already-expired token can never succeed, so there is nothing to
            // retry here -- the caller must sign in again via `login`.
            store.clearSession()
            null
        }
    }

    suspend fun login(userId: String, password: String, remember: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = client.login(userId, password)
            val expiresAtMs = parseEpochMs(session.expiresAt) ?: (System.currentTimeMillis() + FALLBACK_TTL_MS)
            store.saveSession(session.sessionToken, session.userId, expiresAtMs, remember)
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            Result.failure(error)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val token = store.getToken()
        if (token != null) {
            runCatching { client.logout(token) }
        }
        store.clearSession()
    }

    private fun parseEpochMs(iso: String): Long? =
        try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        // Generous enough that a backgrounded WorkManager run, which can be
        // delayed hours by the OS, still lands comfortably inside the window.
        const val REFRESH_BUFFER_MS = 60 * 60 * 1000L // 1 hour
        const val FALLBACK_TTL_MS = 8 * 60 * 60 * 1000L // matches marksy-api's DEFAULT_SESSION_TTL_SECONDS
    }
}
