package com.marksy.os.upstox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Upstox OAuth (authorization code) with the user's own Upstox app: it reads holdings, never places orders. */
object UpstoxOAuth {
    const val AUTHORIZE_URL = "https://api.upstox.com/v2/login/authorization/dialog"
    const val TOKEN_URL = "https://api.upstox.com/v2/login/authorization/token"
    // Loopback: even a redirect the WebView failed to catch would never leave the phone.
    const val DEFAULT_REDIRECT = "http://127.0.0.1/marksy-upstox"
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val CUTOFF: LocalTime = LocalTime.of(3, 30)
    private const val MAX_RESPONSE_CHARS = 64_000

    data class Credentials(val apiKey: String, val apiSecret: String, val redirectUri: String) {
        override fun toString() = "Credentials(apiKey=***, apiSecret=***, redirectUri=$redirectUri)"
    }

    sealed interface Redirect {
        data class Code(val code: String) : Redirect { override fun toString() = "Code(***)" }
        data class Failed(val message: String) : Redirect
    }

    fun authorizeUrl(credentials: Credentials, state: String): String =
        "$AUTHORIZE_URL?response_type=code&client_id=${enc(credentials.apiKey)}&redirect_uri=${enc(credentials.redirectUri)}&state=${enc(state)}"

    /** Same scheme, host, port and path as the registered redirect, whatever the query. */
    fun isRedirect(url: String, redirectUri: String): Boolean {
        val a = uri(url) ?: return false
        val b = uri(redirectUri) ?: return false
        if (a.scheme == null || a.host == null) return false
        return a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) &&
            port(a) == port(b) && a.path.orEmpty().trimEnd('/') == b.path.orEmpty().trimEnd('/')
    }

    fun isUpstoxPage(url: String): Boolean {
        val u = uri(url) ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme.equals("https", ignoreCase = true) && (host == "upstox.com" || host.endsWith(".upstox.com"))
    }

    fun parseRedirect(url: String, expectedState: String): Redirect {
        val q = query(url) ?: return Redirect.Failed("Upstox sent an unreadable sign-in reply. Try again.")
        q["error"]?.let { return Redirect.Failed("Upstox: ${q["error_description"] ?: it}") }
        if (q["state"] != expectedState) return Redirect.Failed("That sign-in didn't come from this request. Try again.")
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: return Redirect.Failed("Upstox sent no sign-in code. Try again.")
        return Redirect.Code(code)
    }

    fun tokenRequestBody(code: String, credentials: Credentials): String = listOf(
        "code" to code,
        "client_id" to credentials.apiKey,
        "client_secret" to credentials.apiSecret,
        "redirect_uri" to credentials.redirectUri,
        "grant_type" to "authorization_code"
    ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }

    fun parseToken(body: String): String = try {
        val root = JSONObject(body)
        root.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IOException(
                root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }?.let { "Upstox: $it" }
                    ?: "Upstox returned no access token"
            )
    } catch (e: JSONException) {
        throw IOException("Upstox returned an unreadable sign-in response", e)
    }

    fun expiresAt(issuedAtMs: Long): Long {
        val issued = Instant.ofEpochMilli(issuedAtMs).atZone(IST)
        val cutoff = issued.toLocalDate().atTime(CUTOFF).atZone(IST)
        return (if (cutoff.isAfter(issued)) cutoff else cutoff.plusDays(1)).toInstant().toEpochMilli()
    }

    fun newState(random: SecureRandom = SecureRandom()): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** Trades the one-time code for today's access token; the secret travels only in this https POST body. */
    suspend fun exchange(code: String, credentials: Credentials): String = withContext(Dispatchers.IO) {
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val started = System.currentTimeMillis()
        var failure: String? = "no response"
        try {
            connection.outputStream.use { it.write(tokenRequestBody(code, credentials).toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.length > MAX_RESPONSE_CHARS) throw IOException("Upstox sign-in response exceeded the safety limit")
            parseToken(body).also { failure = null }
        } catch (e: IOException) {
            failure = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            UpstoxRestStats.record(TOKEN_URL, ok = failure == null, ms = System.currentTimeMillis() - started, error = failure)
            connection.disconnect()
        }
    }

    private fun uri(url: String): URI? = runCatching { URI(url) }.getOrNull()
    private fun port(u: URI): Int = if (u.port != -1) u.port else if (u.scheme.equals("https", ignoreCase = true)) 443 else 80
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun query(url: String): Map<String, String>? = runCatching {
        uri(url)?.rawQuery.orEmpty().split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    }.getOrNull()
}
