package com.marksy.os.connector

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * EPIC-021 Gmail API connector (read-only metadata: sender, subject, snippet). Incremental sync
 * uses Gmail's historyId; an expired history id triggers a bounded re-snapshot.
 *
 * NOT ACTIVE: it needs a [GmailTokenProvider] backed by a Google OAuth client for this app's
 * package/signing key and the restricted gmail.readonly scope. No such provider exists in the app,
 * so the registry reports NOT_CONFIGURED and Gmail keeps arriving only through its notifications.
 */
class GmailConnector(private val api: GmailApi, private val tokens: GmailTokenProvider?) : SyncConnector {
    override val descriptor = DESCRIPTOR
    override val sourcePackage = "com.google.android.gm"
    override val sourceName = "Gmail"

    override fun state() = if (tokens == null) ConnectorState.NOT_CONFIGURED else ConnectorState.ACTIVE

    override suspend fun sync(cursor: String?): SyncBatch {
        val provider = tokens ?: throw ConnectorException(ConnectorException.Kind.NOT_CONFIGURED)
        val token = provider.accessToken() ?: throw ConnectorException(ConnectorException.Kind.AUTH)
        try {
            if (cursor != null) {
                try {
                    val page = api.history(token, cursor)
                    val added = page.addedIds.distinct().filter { it !in page.deletedIds }.mapNotNull { fetch(token, it) }
                    return SyncBatch(added, page.deletedIds.distinct(), page.historyId)
                } catch (e: GmailApi.HttpError) {
                    if (e.status != 404) throw e // 404: history id too old, fall through to a fresh snapshot
                }
            }
            val historyId = api.profileHistoryId(token)
            val ids = api.recentMessageIds(token, SNAPSHOT_QUERY, SNAPSHOT_MAX)
            return SyncBatch(ids.mapNotNull { fetch(token, it) }, emptyList(), historyId)
        } catch (e: GmailApi.HttpError) {
            if (e.status == 401 || e.status == 403) {
                provider.invalidate(token)
                throw ConnectorException(ConnectorException.Kind.AUTH, "HTTP ${e.status}")
            }
            throw ConnectorException(if (e.status == 429 || e.status >= 500) ConnectorException.Kind.TRANSIENT else ConnectorException.Kind.UNAVAILABLE, "HTTP ${e.status}")
        } catch (e: IOException) {
            throw ConnectorException(ConnectorException.Kind.TRANSIENT, e.javaClass.simpleName)
        } catch (e: org.json.JSONException) {
            throw ConnectorException(ConnectorException.Kind.MALFORMED)
        }
    }

    /** Spam/trash is skipped; a message that disappeared between list and get is skipped too. */
    private suspend fun fetch(token: String, id: String): SourceRecord? {
        val m = try { api.message(token, id) } catch (e: GmailApi.HttpError) { if (e.status == 404) return null else throw e }
        if (m.labelIds.any { it == "SPAM" || it == "TRASH" }) return null
        val sender = m.from.substringBefore('<').trim().trim('"').ifBlank { m.from }.take(80)
        return SourceRecord(m.id, sender.ifBlank { "Gmail" }, listOf(m.subject, m.snippet).filter { it.isNotBlank() }.joinToString("\n"), m.internalDate)
    }

    companion object {
        const val ID = "gmail-api"
        val DESCRIPTOR = ConnectorDescriptor(ID, "Gmail (API)", "Gmail API read-only; needs Google OAuth setup (not configured)")
        const val SNAPSHOT_QUERY = "newer_than:7d -in:spam -in:trash"
        const val SNAPSHOT_MAX = 50
    }
}

interface GmailTokenProvider {
    suspend fun accessToken(): String?
    /** Called when the API rejects the token (expired/revoked) so the next call re-authorizes. */
    suspend fun invalidate(token: String)
}

interface GmailApi {
    data class Message(val id: String, val from: String, val subject: String, val snippet: String, val internalDate: Long, val labelIds: List<String>)
    data class HistoryPage(val addedIds: List<String>, val deletedIds: List<String>, val historyId: String)
    class HttpError(val status: Int) : IOException("HTTP $status")

    suspend fun profileHistoryId(token: String): String
    suspend fun recentMessageIds(token: String, query: String, max: Int): List<String>
    suspend fun history(token: String, startHistoryId: String): HistoryPage
    suspend fun message(token: String, id: String): Message
}

/** Gmail REST v1 over HttpURLConnection (the codebase's HTTP convention); parsing is separated for tests. */
class HttpGmailApi(private val get: (url: String, token: String) -> Pair<Int, String> = ::httpGet) : GmailApi {
    override suspend fun profileHistoryId(token: String) = JSONObject(call("$BASE/profile", token)).getString("historyId")

    override suspend fun recentMessageIds(token: String, query: String, max: Int) =
        parseIds(call("$BASE/messages?maxResults=$max&q=${URLEncoder.encode(query, "UTF-8")}", token))

    override suspend fun history(token: String, startHistoryId: String): GmailApi.HistoryPage {
        var page: String? = null
        val added = mutableListOf<String>(); val deleted = mutableListOf<String>()
        var historyId = startHistoryId
        repeat(MAX_PAGES) {
            val url = "$BASE/history?startHistoryId=${URLEncoder.encode(startHistoryId, "UTF-8")}&historyTypes=messageAdded&historyTypes=messageDeleted" +
                (page?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val p = parseHistory(call(url, token))
            added += p.first.addedIds; deleted += p.first.deletedIds; historyId = p.first.historyId
            page = p.second ?: return GmailApi.HistoryPage(added, deleted, historyId)
        }
        return GmailApi.HistoryPage(added, deleted, historyId)
    }

    override suspend fun message(token: String, id: String) =
        parseMessage(call("$BASE/messages/${URLEncoder.encode(id, "UTF-8")}?format=metadata&metadataHeaders=From&metadataHeaders=Subject", token))

    private fun call(url: String, token: String): String {
        val (status, body) = get(url, token)
        if (status !in 200..299) throw GmailApi.HttpError(status)
        return body
    }

    companion object {
        private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        private const val MAX_PAGES = 5

        fun parseIds(json: String): List<String> {
            val arr = JSONObject(json).optJSONArray("messages") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.ifBlank { null } }
        }

        fun parseHistory(json: String): Pair<GmailApi.HistoryPage, String?> {
            val o = JSONObject(json)
            val added = mutableListOf<String>(); val deleted = mutableListOf<String>()
            val hist = o.optJSONArray("history")
            if (hist != null) for (i in 0 until hist.length()) {
                val h = hist.getJSONObject(i)
                fun ids(field: String, into: MutableList<String>) {
                    val a = h.optJSONArray(field) ?: return
                    for (j in 0 until a.length()) a.optJSONObject(j)?.optJSONObject("message")?.optString("id")?.ifBlank { null }?.let(into::add)
                }
                ids("messagesAdded", added); ids("messagesDeleted", deleted)
            }
            return GmailApi.HistoryPage(added, deleted, o.getString("historyId")) to o.optString("nextPageToken").ifBlank { null }
        }

        fun parseMessage(json: String): GmailApi.Message {
            val o = JSONObject(json)
            val headers = o.optJSONObject("payload")?.optJSONArray("headers")
            fun header(name: String): String {
                if (headers == null) return ""
                for (i in 0 until headers.length()) headers.optJSONObject(i)?.let { if (it.optString("name").equals(name, true)) return it.optString("value") }
                return ""
            }
            val labels = o.optJSONArray("labelIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            return GmailApi.Message(o.getString("id"), header("From"), header("Subject"), o.optString("snippet"), o.optString("internalDate").toLongOrNull() ?: 0L, labels)
        }

        private fun httpGet(url: String, token: String): Pair<Int, String> {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            return try {
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally { c.disconnect() }
        }
    }
}
