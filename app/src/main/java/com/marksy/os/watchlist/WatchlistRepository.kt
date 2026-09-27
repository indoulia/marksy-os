package com.marksy.os.watchlist

import com.marksy.os.data.local.WatchAdd
import com.marksy.os.data.local.WatchlistDao
import com.marksy.os.data.local.WatchlistEntity
import com.marksy.os.data.local.WatchlistItemEntity
import kotlinx.coroutines.flow.Flow

class WatchlistRepository(private val dao: WatchlistDao, private val clock: () -> Long = System::currentTimeMillis) {
    fun observeLists(): Flow<List<WatchlistEntity>> = dao.observeLists()
    fun observeItems(): Flow<List<WatchlistItemEntity>> = dao.observeItems()

    /** Null when the name is blank or another list already has it (ignoring case). */
    suspend fun createList(name: String): Long? {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty() || dao.lists().any { it.name.equals(clean, ignoreCase = true) }) return null
        return dao.insertList(WatchlistEntity(name = clean, createdAt = clock()))
    }

    suspend fun deleteList(id: Long) = dao.deleteList(id)

    suspend fun add(listId: Long, symbol: String, name: String?): WatchAdd =
        dao.addCapped(WatchlistItemEntity(listId, symbol.trim().uppercase(), name?.trim()?.ifEmpty { null }, clock()), MAX_STOCKS)

    suspend fun remove(listId: Long, symbol: String) { dao.removeItem(listId, symbol) }

    suspend fun move(fromListId: Long, toListId: Long, symbol: String): WatchAdd =
        dao.moveCapped(fromListId, symbol, toListId, clock(), MAX_STOCKS)

    companion object {
        const val MAX_STOCKS = 15
    }
}

/** Names for a new list: fixed proposal pills, plus suggestions for what's being typed. */
object WatchlistNames {
    val COMMON = listOf(
        "Defence", "SmallCap", "MidCap", "LargeCap", "Banks", "IT", "Pharma", "Auto", "Energy", "Metals",
        "FMCG", "Realty", "Infra", "PSU", "Railways", "Chemicals", "Momentum", "Dividend", "Long term"
    )
    private val THEMES = listOf(
        "Smallcap 250", "Midcap 150", "Nifty 50", "Nifty Next 50", "Microcap", "Bluechip", "PSU Banks", "Private Banks",
        "NBFC", "Insurance", "Fintech", "Capital Goods", "Capital Markets", "EV", "Semiconductors", "Electronics",
        "Green Energy", "Renewables", "Solar", "Power", "Oil & Gas", "Cement", "Steel", "Mining", "Aerospace",
        "Shipbuilding", "Aviation", "Logistics", "Telecom", "Media", "Hospitals", "Healthcare", "Diagnostics",
        "Consumption", "Retail", "Textiles", "Paints", "Jewellery", "Hotels", "Tourism", "Agri", "Fertilisers",
        "Sugar", "Real Estate", "Housing Finance", "Digital", "Data Centres", "AI", "Exports", "Manufacturing",
        "Value", "Growth", "Turnaround", "Breakout", "Swing", "Short term", "IPO", "SME", "High dividend", "Multibagger"
    )
    private const val MIN_QUERY = 3
    private const val MAX_SUGGESTIONS = 6

    /** Pills: [preferred] first, then the common names, minus lists in use. Not affected by typing. */
    fun proposals(existing: Collection<String>, preferred: List<String> = emptyList()): List<String> =
        unused(preferred + COMMON, existing)

    /** From 3 typed chars, names containing the text from a wider vocabulary and [sectors]; prefix matches first. */
    fun suggest(typed: String, existing: Collection<String>, sectors: List<String> = emptyList()): List<String> {
        if (typed.trim().length < MIN_QUERY) return emptyList()
        val q = key(typed)
        val matches = unused(COMMON + THEMES + sectors, existing).filter { key(it) != q && q in key(it) }
        return (matches.filter { key(it).startsWith(q) } + matches.filterNot { key(it).startsWith(q) }).take(MAX_SUGGESTIONS)
    }

    private fun unused(names: List<String>, existing: Collection<String>): List<String> {
        val used = existing.map(::key).toSet()
        return names.filter { it.isNotBlank() }.distinctBy(::key).filter { key(it) !in used }
    }

    private fun key(name: String) = name.lowercase().filter(Char::isLetterOrDigit)
}

/** Which list a stock most likely belongs in: a sector-named list, then a market-cap list, then the one being viewed. */
object WatchlistPicker {
    private val GROUPS = listOf(
        setOf("defence", "defense", "aerospace", "military", "shipbuilding"),
        setOf("bank", "banks", "banking", "financial", "finance", "nbfc", "fintech", "insurance", "lending"),
        setOf("it", "tech", "technology", "software", "information", "computer", "computers", "internet"),
        setOf("pharma", "pharmaceutical", "pharmaceuticals", "healthcare", "health", "hospital", "hospitals", "medical", "biotech"),
        setOf("auto", "autos", "automobile", "automobiles", "automotive", "ev", "vehicle", "vehicles"),
        setOf("energy", "power", "oil", "gas", "utilities", "utility", "renewable", "renewables", "solar", "petroleum"),
        setOf("metal", "metals", "steel", "mining", "aluminium", "aluminum"),
        setOf("fmcg", "consumer", "staples", "food", "foods", "beverages"),
        setOf("realty", "real", "estate", "housing"),
        setOf("infra", "infrastructure", "construction", "engineering", "cement"),
        setOf("chemical", "chemicals", "fertilizer", "fertilizers", "fertiliser", "fertilisers"),
        setOf("telecom", "telecommunication", "telecommunications", "communication"),
        setOf("railway", "railways", "rail")
    )
    private val STOP = setOf("and", "the", "my", "of", "services", "stocks", "stock", "sector", "list", "watch", "ltd", "cap", "caps", "industries", "industry")

    // Rough SEBI-style bands in ₹ crore.
    private const val LARGE_CAP_CR = 100_000.0
    private const val MID_CAP_CR = 30_000.0

    fun pick(lists: List<WatchlistEntity>, counts: Map<Long, Int>, sector: String?, marketCapCr: Double?, current: Long?): Long? {
        val open = lists.filter { (counts[it.id] ?: 0) < WatchlistRepository.MAX_STOCKS }
        val sectorTerms = sector?.let(::terms).orEmpty()
        if (sectorTerms.isNotEmpty()) open.firstOrNull { list -> terms(list.name).any { it in sectorTerms } }?.let { return it.id }
        val band = marketCapCr?.let { if (it >= LARGE_CAP_CR) "large" else if (it >= MID_CAP_CR) "mid" else "small" }
        if (band != null) open.firstOrNull { capBand(it.name) == band }?.let { return it.id }
        return open.firstOrNull { it.id == current }?.id ?: open.firstOrNull()?.id
    }

    /** Words of a name, each mapped to its synonym group so "Defence" meets "Aerospace & Defense". */
    private fun terms(text: String): Set<String> =
        text.replace(Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"), " ")
            .lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOP }
            .map { word -> GROUPS.indexOfFirst { word in it }.takeIf { it >= 0 }?.let { "#$it" } ?: word }
            .toSet()

    private fun capBand(name: String): String? {
        val n = name.lowercase().filter(Char::isLetter)
        return when {
            "largecap" in n || "bluechip" in n -> "large"
            "midcap" in n -> "mid"
            "smallcap" in n || "microcap" in n -> "small"
            else -> null
        }
    }
}
