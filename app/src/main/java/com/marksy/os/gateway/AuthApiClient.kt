package com.marksy.os.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

interface AuthApiClient {
    suspend fun login(userId: String, password: String): SessionResponseDto
    suspend fun refresh(currentToken: String): SessionResponseDto
    suspend fun logout(currentToken: String): Boolean
}

class AuthApiException(message: String) : IOException(message)

/** `POST /auth/login`, `/auth/refresh`, `/auth/logout` against `marksy-api`'s
 * already-existing session auth (EPIC-M3.12) -- the same mechanism the
 * Flutter/admin client uses. Structurally mirrors every other client in this
 * codebase: raw `HttpURLConnection`, no third-party HTTP library. */
class RealAuthApiClient(baseUrl: String) : AuthApiClient {
    private val base: String = normalizeBaseUrl(baseUrl)

    override suspend fun login(userId: String, password: String): SessionResponseDto {
        val payload = JSONObject().put("userId", userId).put("password", password)
        return SessionResponseDto.parse(execute("POST", "$base/auth/login", payload))
    }

    override suspend fun refresh(currentToken: String): SessionResponseDto =
        SessionResponseDto.parse(execute("POST", "$base/auth/refresh", bearer = currentToken))

    override suspend fun logout(currentToken: String): Boolean {
        val envelope = execute("POST", "$base/auth/logout", bearer = currentToken)
        return envelope.optJSONObject("data")?.optBoolean("revoked", false) ?: false
    }

    private suspend fun execute(method: String, url: String, payload: JSONObject? = null, bearer: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
                doInput = true
                if (payload != null) doOutput = true
            }
            try {
                if (payload != null) {
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code in REDIRECT_CODES) throw AuthApiException("Marksy auth redirect refused")
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(4096)
                    val builder = StringBuilder()
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        builder.append(buffer, 0, read)
                        if (builder.length > MAX_RESPONSE_CHARS) throw IOException("Marksy auth response exceeded the safety limit")
                    }
                    builder.toString()
                }.orEmpty()
                if (code !in 200..299) {
                    val detail = errorDetail(response)
                    if (code in 400..499) throw AuthApiException("Marksy auth returned HTTP $code$detail")
                    throw IOException("Marksy auth returned HTTP $code$detail")
                }
                JSONObject(response)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                connection.disconnect()
            }
        }

    private fun errorDetail(response: String): String = try {
        val envelope = JSONObject(response)
        val error = envelope.optJSONObject("error")
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
        message?.let { ": ${it.take(300)}" } ?: ""
    } catch (_: Exception) { "" }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_RESPONSE_CHARS = 50_000
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Base URL must not be blank" }
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: throw IllegalArgumentException("Base URL is not a valid URL")
            require(uri.scheme.equals("https", ignoreCase = true)) { "Base URL must use HTTPS" }
            require(uri.host?.isNotBlank() == true) { "Base URL must include a host" }
            return trimmed
        }
    }
}
