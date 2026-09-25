package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import java.util.Locale

/**
 * EPIC-020 places, learned only from text the user already received (deliveries, rides, calendar
 * "Location:" lines). Device location is never read. Street addresses are coarsened to their last
 * two comma parts (locality, city) so no house/flat number is ever stored.
 */
object LocationMemory {
    enum class Role { HOME, WORK, PLACE }

    data class Place(val key: String, val label: String, val role: Role)

    fun extract(event: NotificationEventEntity): Place? {
        val pkg = event.sourcePackage.lowercase(Locale.ROOT)
        // Chats are excluded by source, not only category: text there is usually someone else's whereabouts.
        if (event.category == "MESSAGES" || CHAT_PACKAGES.any { it in pkg }) return null
        // Calendar records teach only through their "Location:" field; a title like "Trip to Goa" is a plan, not a place.
        val calendar = "calendar" in pkg
        val text = if (calendar) event.body else "${event.title}\n${event.body}"
        // A marker value like "Location: Priya Sharma" or "Sharma, Priya" is a person: require a venue word or an address shape.
        MARKERS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }?.let { v ->
            return normalize(v)?.takeIf { it.role != Role.PLACE || VENUE.containsMatchIn(v) || (v.contains(',') && v.any(Char::isDigit)) }
        }
        if (calendar) return null
        val phrase = (DELIVERY.takeIf { event.category == "DELIVERY" }?.find(text)?.groupValues?.get(1)
            ?: RIDE.find(text)?.groupValues?.get(1)
            ?: return null).split(PHRASE_END).first()
        // "Delivered to Priya" names a person, not a place: phrases only count for home/work or an address.
        return normalize(phrase)?.takeIf { it.role != Role.PLACE || phrase.contains(',') }
    }

    fun normalize(raw: String): Place? {
        var s = raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', ',', '!', ';', ':', ')', '(').trim()
        s = s.replace(Regex("^(?:your|my|the)\\s+", RegexOption.IGNORE_CASE), "")
        val lower = s.lowercase(Locale.ROOT)
        if (HOME.matches(lower)) return Place("home", "Home", Role.HOME)
        if (WORK.matches(lower)) return Place("work", "Work", Role.WORK)
        if (s.any { it.isDigit() }) {
            // Digit-bearing tokens ("4B", "5th") go entirely, and unit-only parts ("Flat", "Tower") are never a locality.
            val parts = s.split(',').map { p -> p.trim().split(' ').filterNot { w -> w.any(Char::isDigit) }.joinToString(" ").trim() }
                .filter { p -> p.length >= 3 && !p.lowercase(Locale.ROOT).split(' ').all { it in UNIT_WORDS } }
            // A digit-bearing place with no comma parts is a bare street address: too precise to keep.
            if (!s.contains(',') || parts.isEmpty()) return null
            // "12 MG Road, Pune" keeps only the city; longer addresses keep locality and city.
            s = parts.takeLast(if (s.split(',').size >= 3) 2 else 1).joinToString(", ")
        }
        s = s.take(MAX_LABEL).trim()
        val key = PersonalMemory.key(s)
        if (key.length < 3 || key in NOT_PLACES || key.split(' ').all { it in NOT_PLACES }) return null
        return Place(key, s.replaceFirstChar { it.titlecase(Locale.ROOT) }, Role.PLACE)
    }

    private const val PLACE = "([^\\n.;|!?]{2,80})"
    private val MARKERS = listOf(
        Regex("(?:^|\\n|\\s)(?:location|where|venue)\\s*[:\\-]\\s*$PLACE", RegexOption.IGNORE_CASE),
        Regex("📍\\s*$PLACE")
    )
    private val DELIVERY = Regex("\\b(?:delivered|delivering|arriving|dropped off)\\s+(?:to|at)\\s+$PLACE", RegexOption.IGNORE_CASE)
    private val RIDE = Regex("\\b(?:ride|trip|cab|auto|drop)\\s+(?:to|at)\\s+$PLACE", RegexOption.IGNORE_CASE)
    private val VENUE = Regex("(?i)\\b(?:office|mall|cafe|restaurant|hotel|hall|room|tower|park|centre|center|hospital|clinic|station|airport|school|college|university|campus|building|floor|plaza|wework|cowork\\w*|club|gym|studio|market|stadium|theatre|theater|temple|church|mosque)\\b")
    // "ride to Office is arriving" -> "Office".
    private val PHRASE_END = Regex("\\s+(?:is|has|was|will|by|on|for|from|and|in|arriving|confirmed|today|tomorrow|at \\d)\\b", RegexOption.IGNORE_CASE)
    // "home in Baner" is home; "Home Centre" (a shop) is not.
    private val HOME = Regex("^(?:home|house|residence)(?:\\s+(?:in|at|address)\\b.*|\\s*,.*)?$")
    private val WORK = Regex("^(?:work|office|workplace)(?:\\s+(?:in|at|address)\\b.*|\\s*,.*)?$")
    private val NOT_PLACES = setOf("you", "your", "door", "doorstep", "the door", "reception", "security", "today", "tomorrow", "tonight", "now", "soon", "online", "tbd", "none", "n a", "virtual", "zoom", "teams", "microsoft", "google", "meet", "meeting", "call", "link", "video", "webex")
    private val UNIT_WORDS = setOf("flat", "floor", "tower", "room", "wing", "block", "apt", "apartment", "unit", "suite", "level", "plot", "no", "conference", "meeting", "desk", "gate")
    private val CHAT_PACKAGES = listOf("whatsapp", "telegram", "securesms", "com.facebook.orca", "instagram", "snapchat", "discord", "com.slack", "com.microsoft.teams", "signal")
    private const val MAX_LABEL = 60
}
