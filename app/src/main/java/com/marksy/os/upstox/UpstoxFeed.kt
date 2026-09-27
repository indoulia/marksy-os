package com.marksy.os.upstox

import com.marksy.os.ai.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One app-wide Upstox Market Data Feed V3 WebSocket (read-only, the user's own token). Screens
 * [watch] the instrument keys they show and read [quotes]; the socket runs only while [run] is
 * active (the app is in the foreground) and reconnects with backoff.
 */
object UpstoxFeed {
    sealed interface Status {
        data object Idle : Status
        data object Connecting : Status
        data object Live : Status
        data class Reconnecting(val reason: String) : Status
        data class TokenRejected(val reason: String) : Status
    }

    private val _quotes = MutableStateFlow<Map<String, UpstoxLtp>>(emptyMap())
    val quotes: StateFlow<Map<String, UpstoxLtp>> = _quotes.asStateFlow()
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _lastTickAt = MutableStateFlow(0L)
    val lastTickAt: StateFlow<Long> = _lastTickAt.asStateFlow()
    // Upstox's own segment status; null until its market_info frame arrives.
    private val _nseOpen = MutableStateFlow<Boolean?>(null)
    val nseOpen: StateFlow<Boolean?> = _nseOpen.asStateFlow()

    /** Upstox's word when we have it, else NSE's regular session by the IST clock. */
    fun isMarketOpen(nowMs: Long = System.currentTimeMillis()): Boolean {
        _nseOpen.value?.let { return it }
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.of("Asia/Kolkata"))
        val weekday = now.dayOfWeek != java.time.DayOfWeek.SATURDAY && now.dayOfWeek != java.time.DayOfWeek.SUNDAY
        val t = now.toLocalTime()
        return weekday && !t.isBefore(java.time.LocalTime.of(9, 15)) && t.isBefore(java.time.LocalTime.of(15, 30))
    }

    private val subscriptions = FeedSubscriptions()
    @Volatile private var socket: WebSocket? = null
    private var guid = 0

    private val _stats = MutableStateFlow(FeedStats())
    val stats: StateFlow<FeedStats> = _stats.asStateFlow()

    // Ticks land in [pending] and reach [quotes] at most every PUBLISH_MS, so a busy session can't recompose every frame.
    private val publisher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private val pending = HashMap<String, UpstoxLtp>()
    @Volatile private var publishScheduled = false

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Token removed: drop every price so no screen keeps showing Upstox data. */
    fun clear() {
        socket?.cancel()
        socket = null
        synchronized(pending) { pending.clear() }
        _quotes.value = emptyMap()
        _lastTickAt.value = 0L
        _status.value = Status.Idle
    }

    private val pinned = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** Keys kept live whatever is on screen (the Home indices); screens use [acquire]/[release]. */
    fun watch(keys: Collection<String>) { pinned += keys; acquire(pinned, synchronized(pinned) { pinned.toSet() }) }

    /** [owner] shows [keys] now; the socket subscribes what's new and drops what nobody shows any more. */
    fun acquire(owner: Any, keys: Collection<String>) {
        val (added, removed) = subscriptions.set(owner, keys.toSet())
        socket?.let { ws ->
            if (added.isNotEmpty()) send(ws, "sub", added.toList())
            if (removed.isNotEmpty()) send(ws, "unsub", removed.toList())
        }
        _stats.update { it.copy(subscribed = subscriptions.all().size) }
    }

    fun release(owner: Any) = acquire(owner, emptySet())

    /** Keeps a socket open until cancelled. Returns early (no retry) if Upstox rejects the token. */
    suspend fun run(token: () -> String?) {
        var backoffMs = 2_000L
        while (true) {
            val bearer = token()
            if (bearer == null) { _status.value = Status.Idle; return }
            _status.value = Status.Connecting
            try {
                val url = authorize(bearer)
                val openedAt = System.currentTimeMillis()
                connectAndStream(url, bearer)
                // Only a connection that held for a while resets the backoff; one that drops at once keeps backing off.
                if (System.currentTimeMillis() - openedAt > 30_000L) backoffMs = 2_000L
                _stats.update { it.copy(drops = it.drops + 1, lastError = "connection closed") }
                _status.value = Status.Reconnecting("connection closed")
            } catch (e: CancellationException) {
                _status.value = Status.Idle
                throw e
            } catch (e: UpstoxAuthException) {
                DiagLog.w(TAG, "feed: token rejected")
                _status.value = Status.TokenRejected(e.message ?: "token rejected")
                return
            } catch (e: Exception) {
                DiagLog.w(TAG, "feed: ${e.javaClass.simpleName}; retry in ${backoffMs}ms")
                _stats.update { it.copy(drops = it.drops + 1, lastError = e.message ?: e.javaClass.simpleName) }
                _status.value = Status.Reconnecting(e.message ?: e.javaClass.simpleName)
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun authorize(bearer: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(AUTHORIZE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) throw UpstoxAuthException("Upstox rejected the token (expired or revoked)")
            if (code !in 200..299) throw IOException("Upstox feed authorize returned HTTP $code")
            val data = JSONObject(body).getJSONObject("data")
            data.optString("authorized_redirect_uri").ifBlank { data.optString("authorizedRedirectUri") }
                .takeIf { it.startsWith("wss://") } ?: throw IOException("Upstox feed authorize returned no socket URL")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun connectAndStream(url: String, bearer: String) = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url(url).header("Authorization", "Bearer $bearer").build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                _status.value = Status.Live
                _stats.update { it.copy(connects = it.connects + 1, connectedAt = System.currentTimeMillis()) }
                DiagLog.i(TAG, "feed: connected")
                val keys = subscriptions.all().toList()
                if (keys.isNotEmpty()) send(webSocket, "sub", keys)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = runCatching { UpstoxFeedDecoder.decodeFrame(bytes.toByteArray()) }.getOrElse {
                    DiagLog.w(TAG, "feed: undecodable frame ${it.javaClass.simpleName}")
                    _stats.update { s -> s.copy(undecodable = s.undecodable + 1) }
                    return
                }
                frame.nseOpen?.let { if (_nseOpen.value != it) DiagLog.i(TAG, "feed: NSE ${if (it) "open" else "closed"}"); _nseOpen.value = it }
                if (frame.quotes.isNotEmpty()) {
                    synchronized(pending) { pending.putAll(FeedMerge.merge(pending, frame.quotes)) }
                    _lastTickAt.value = System.currentTimeMillis()
                    schedulePublish()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                val error = if (response?.code == 401 || response?.code == 403) UpstoxAuthException("Upstox rejected the token") else IOException(t.message ?: t.javaClass.simpleName, t)
                if (cont.isActive) cont.resumeWithException(error)
            }
        })
        cont.invokeOnCancellation { ws.cancel(); socket = null }
    }

    private fun schedulePublish() {
        if (publishScheduled) return
        publishScheduled = true
        publisher.launch {
            delay(PUBLISH_MS)
            publishScheduled = false
            val batch = synchronized(pending) { HashMap(pending).also { pending.clear() } }
            if (batch.isEmpty()) return@launch
            val before = _quotes.value
            val merged = FeedMerge.merge(before, batch)
            _quotes.value = merged
            _stats.update { it.copy(ticks = it.ticks + batch.size, outOfOrder = it.outOfOrder + batch.count { (k, q) -> merged[k] !== q }) }
        }
    }

    // Upstox V3 expects the subscription request as a binary frame of JSON.
    private fun send(ws: WebSocket, method: String, keys: List<String>) {
        val message = JSONObject()
            .put("guid", "marksy-${++guid}")
            .put("method", method)
            .put("data", JSONObject().put("mode", "ltpc").put("instrumentKeys", JSONArray(keys)))
        ws.send(message.toString().toByteArray(Charsets.UTF_8).toByteString())
        DiagLog.i(TAG, "feed: $method ${keys.size} key(s)")
    }

    private const val PUBLISH_MS = 250L

    private const val TAG = "MarksyUpstox"
    private const val AUTHORIZE_URL = "https://api.upstox.com/v3/feed/market-data-feed/authorize"
}
