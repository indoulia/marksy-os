package com.marksy.os.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

        // marksy-api's refresh rotates and revokes the old token, so two concurrent callers
        // refreshing the same near-expiry token would otherwise race: the second one gets a
        // 401 on the now-dead token and would clear the session the first one just renewed.
        // The mutex is shared process-wide (not per-instance) because MarksyGatewayProvider
        // constructs a fresh AuthRepository per call -- there is only ever one real session.
        refreshMutex.withLock {
            // Re-read after acquiring the lock: another caller may have already refreshed
            // this exact session while this one was waiting.
            val lockedToken = store.getToken() ?: return@withLock null
            val lockedExpiresAt = store.getExpiresAtEpochMs() ?: return@withLock null
            val lockedNow = System.currentTimeMillis()
            if (lockedExpiresAt - lockedNow > REFRESH_BUFFER_MS) return@withLock lockedToken

            try {
                val refreshed = client.refresh(lockedToken)
                val expiresAtMs = parseEpochMs(refreshed.expiresAt) ?: (lockedNow + FALLBACK_TTL_MS)
                store.saveSession(refreshed.sessionToken, refreshed.userId, expiresAtMs, store.isRemembered())
                refreshed.sessionToken
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: AuthApiException) {
                // The only case `POST /auth/refresh` fails deterministically: the token is
                // truly dead server-side (MRA_SESSION_EXPIRED, revoked, etc.) -- terminal.
                store.clearSession()
                null
            } catch (error: Exception) {
                // Network blip, 5xx, a JSON-parse failure, or a Keystore/crypto error saving
                // the new session -- none of these mean the *existing* token is invalid. Never
                // destroy a session that might still be genuinely valid, and never let a
                // non-IOException (e.g. GeneralSecurityException) crash the caller.
                if (lockedExpiresAt > lockedNow) lockedToken else null
            }
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
        } catch (error: Exception) {
            // Broad on purpose: a malformed response (JSONException) or a Keystore failure
            // while saving must surface as a login failure, never crash the caller.
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

        // Shared across every AuthRepository instance in the process, not per-instance --
        // MarksyGatewayProvider constructs a fresh AuthRepository on every call, but they all
        // back the same one real session, so the lock must too.
        val refreshMutex = Mutex()
    }
}
