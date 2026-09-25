package com.marksy.os.market

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns any Marksy IPO detail JSON into readable rows, so every section the backend sends is shown
 * without a hand-written screen per field. Values come in `{state, value, asOf}` envelopes; missing
 * ones, ids and evidence bodies are left out.
 */
object IpoDetailFormatter {
    /** [paragraph] rows are free text shown full width (a list of explanations), not label and value. */
    data class Row(val depth: Int, val label: String, val value: String?, val paragraph: Boolean = false)

    private val SKIP = setOf("id", "snapshotId", "currentSnapshotId", "predictionSnapshotId", "recommendationGenerationId", "locator", "logoUrl", "evidenceId", "recommendationId", "retrievedAt")
    private val TIME_KEYS = listOf("observedAt", "publishedAt", "predictedAt", "ranAt", "measuredAt", "changedAt")
    private const val MAX_LIST = 3
    private const val LONG_TEXT = 40
    private val NOTE_KEYS = listOf("explanation", "note")
    private val ACRONYMS = setOf("qib", "nii", "hni", "gmp", "rhp", "drhp", "ofs", "ipo", "sme", "pe", "eps", "roe", "roce", "nav", "isin", "bse", "nse", "url", "sebi", "mf")
    // A value that's simply absent says nothing; "insufficient evidence" or "stale" is worth showing.
    private val SILENT_STATES = setOf("MISSING", "EMPTY", "UNAVAILABLE", "AVAILABLE", "FRESH", "OK", "NOT_APPLICABLE")
    private val ENVELOPE = setOf("state", "value", "asOf", "reason")
    private val TITLE_KEYS = listOf("label", "name", "title", "headline", "publication", "kind", "analyst", "source", "category")
    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

    fun rows(o: JSONObject, skip: Set<String> = emptySet(), depth: Int = 0, zone: ZoneId = ZoneId.systemDefault()): List<Row> {
        val keys = o.keys().asSequence().toList()
        return keys.filterNot { it in skip || it in SKIP }.flatMap { key ->
            // "premium" next to "premiumPercent": both would read "Premium".
            val sibling = key.endsWith("Percent") && key.removeSuffix("Percent") in keys
            field(key, o.opt(key), depth, zone, if (sibling) label(key.removeSuffix("Percent")) + " (%)" else label(key))
        }
    }

    private fun field(key: String, raw: Any?, depth: Int, zone: ZoneId, label: String = label(key)): List<Row> {
        // {value, explanation|note}: the value means little without the reason, so they share a line.
        val why = (raw as? JSONObject)?.let { o -> NOTE_KEYS.firstOrNull { o.optString(it).isNotBlank() } }
        if (raw is JSONObject && why != null && raw.opt("value").let { it != null && it != JSONObject.NULL && it !is JSONObject && it !is JSONArray } &&
            meaningful(raw).all { it in ENVELOPE || it in NOTE_KEYS }) {
            return listOf(Row(depth, label, "${scalar(key, raw.get("value"), zone)} — ${raw.getString(why)}"))
        }
        val v = unwrap(raw) ?: return (raw as? JSONObject)?.optString("state")?.takeIf { it.isNotBlank() && it !in SILENT_STATES }
            ?.let { listOf(Row(depth, label, words(it))) }.orEmpty()
        return when {
            key == "state" -> if (v is String && v !in setOf("AVAILABLE", "FRESH", "OK")) listOf(Row(depth, "Status", words(v))) else emptyList()
            key == "evidence" || key == "sources" -> (v as? JSONArray)?.length()?.takeIf { it > 0 }?.let { listOf(Row(depth, label, "$it item${if (it == 1) "" else "s"}")) }.orEmpty()
            v is JSONObject -> rows(v, depth = depth + 1, zone = zone).let { child ->
                when {
                    child.isEmpty() -> child
                    // An object that only reports a status ("insufficient evidence") reads as one line.
                    child.size == 1 && child[0].label == "Status" -> listOf(Row(depth, label, child[0].value))
                    else -> listOf(Row(depth, label, null)) + child
                }
            }
            v is JSONArray -> array(label, v, depth, zone)
            else -> listOf(Row(depth, label, scalar(key, v, zone)))
        }
    }

    private fun array(label: String, a: JSONArray, depth: Int, zone: ZoneId): List<Row> {
        val all = (0 until a.length()).mapNotNull { unwrap(a.opt(it)) }
        if (all.isEmpty()) return emptyList()
        if (all.none { it is JSONObject || it is JSONArray }) {
            val texts = all.map { scalar("", it, zone) }
            // Sentences read one per line; short values ("Strong brand", "NSE") join on one.
            return if (texts.any { it.length > LONG_TEXT }) listOf(Row(depth, label, null)) + texts.map { Row(depth + 1, it, null, paragraph = true) }
                else listOf(Row(depth, label, texts.joinToString(", ")))
        }
        // Readings and histories repeat; the newest few carry the signal.
        val timeKey = (all.firstOrNull() as? JSONObject)?.let { first -> TIME_KEYS.firstOrNull { first.has(it) } }
        val items = if (timeKey != null && all.size > MAX_LIST) all.filterIsInstance<JSONObject>().sortedByDescending { it.optString(timeKey) }.take(MAX_LIST) else all
        val more = if (items.size < all.size) listOf(Row(depth + 1, "…and ${all.size - items.size} more", null)) else emptyList()
        val body = items.filterIsInstance<JSONObject>().flatMap { item ->
            val titleKey = TITLE_KEYS.firstOrNull { item.optString(it).isNotBlank() && unwrap(item.opt(it)) is String }
            val title = titleKey?.let { scalar("", item.getString(it), zone) }
            val rest = rows(item, skip = setOfNotNull(titleKey), depth = depth + 2, zone = zone)
            when {
                // A dated label with no date yet ("Refunds") says nothing.
                rest.isEmpty() -> emptyList()
                // {label: "Allotment", date: {...}} reads best as one line.
                title != null && rest.size == 1 && rest[0].value != null -> listOf(Row(depth + 1, title, rest[0].value))
                title != null -> listOf(Row(depth + 1, title, null)) + rest
                else -> rest.map { it.copy(depth = it.depth - 1) }
            }
        }
        return if (body.isEmpty()) emptyList() else listOf(Row(depth, label, null)) + body + more
    }

    /** `{state, value}` / `{value, asOf}` envelopes collapse to their value; nulls and valueless envelopes vanish. */
    private fun unwrap(v: Any?): Any? {
        if (v == null || v == JSONObject.NULL) return null
        if (v is JSONObject && (v.has("state") || v.has("value")) && meaningful(v).all { it in ENVELOPE }) return unwrap(v.opt("value"))
        if (v is String && v.isBlank()) return null
        return v
    }

    /** Keys that carry something: nulls, blanks, empty lists, ids and evidence don't stop an object being an envelope. */
    private fun meaningful(o: JSONObject): List<String> = o.keys().asSequence().filterNot { k ->
        val x = o.opt(k)
        o.isNull(k) || k in SKIP || k.endsWith("Id") || k == "evidence" || k == "sources" || (x is JSONArray && x.length() == 0) || (x is String && x.isBlank())
    }.toList()

    private fun scalar(key: String, v: Any, zone: ZoneId): String = when (v) {
        is Boolean -> if (v) "Yes" else "No"
        is Number -> number(v) + if (key.lowercase().endsWith("percent")) "%" else ""
        is String -> date(v, zone) ?: if (v.length > 2 && v.all { it.isUpperCase() || it == '_' || it.isDigit() }) words(v) else v
        else -> v.toString()
    }

    /** Two decimals at most, Indian digit grouping: 1499400.5 -> "14,99,400.5". */
    internal fun number(n: Number): String {
        val plain = BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val sign = if (plain.startsWith("-")) "-" else ""
        val whole = plain.removePrefix("-").substringBefore('.')
        val frac = plain.substringAfter('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val grouped = if (whole.length <= 3) whole else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        return sign + grouped + frac
    }

    private fun date(s: String, zone: ZoneId): String? =
        runCatching { LocalDate.parse(s).format(DATE) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).atZoneSameInstant(zone).format(DATE_TIME) }.getOrNull()

    private fun words(s: String) = s.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** "totalAmountCrore" -> "Total amount (₹ Cr)", "listingReturnPercent" -> "Listing return". */
    internal fun label(key: String): String {
        val crore = key.endsWith("Crore")
        val base = key.removeSuffix("Crore").let { if (it != "percent" && it.endsWith("Percent")) it.removeSuffix("Percent") else it }
        val spaced = base.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase(Locale.ROOT).split(' ')
            .joinToString(" ") { if (it in ACRONYMS) it.uppercase(Locale.ROOT) else it }.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return spaced + if (crore) " (₹ Cr)" else ""
    }
}
