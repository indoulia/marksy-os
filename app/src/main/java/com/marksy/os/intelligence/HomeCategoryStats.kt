package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import java.time.Instant
import java.time.ZoneId

/** Time window behind the Home "Today / This Week / All Time" pills. */
enum class HomePeriod(val label: String) {
    TODAY("Today"), WEEK("This Week"), ALL("All Time");

    fun startMillis(nowMillis: Long, zone: ZoneId): Long? {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (this) {
            TODAY -> today.atStartOfDay(zone).toInstant().toEpochMilli()
            WEEK -> today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
            ALL -> null
        }
    }

    companion object {
        fun forLabel(label: String): HomePeriod = entries.firstOrNull { it.label == label } ?: TODAY
    }
}

/** Counts and subtitles for the Home category cards, computed from all active events. */
data class HomeCategoryStats(
    val trading: Stat,
    val important: Stat,
    val messages: Stat,
    val emails: Stat,
    val banking: Stat,
    val delivery: Stat
) {
    data class Stat(val count: Int, val subtitle: String)

    companion object {
        private const val IMPORTANT_SCORE = 70
        private const val CRITICAL_SCORE = 90

        fun from(
            events: List<NotificationEventEntity>,
            nowMillis: Long,
            period: HomePeriod,
            zone: ZoneId = ZoneId.systemDefault()
        ): HomeCategoryStats {
            val since = period.startMillis(nowMillis, zone)
            val inPeriod = events
                .filter { !it.archived && (since == null || it.postedAt >= since) }
                .map { it to EventIntelligence.analyze(it, nowMillis).attentionScore }

            fun stat(matches: (NotificationEventEntity) -> Boolean): Stat {
                val group = inPeriod.filter { matches(it.first) }
                val important = group.count { it.second >= IMPORTANT_SCORE }
                val latest = group.maxByOrNull { it.first.postedAt }?.first?.sourceName?.ifBlank { null }
                return Stat(
                    group.size,
                    when {
                        important > 0 -> "$important important"
                        latest != null -> "Latest: $latest"
                        else -> "None yet"
                    }
                )
            }

            val importantEvents = inPeriod.filter { it.second >= IMPORTANT_SCORE }
            val critical = importantEvents.count { it.second >= CRITICAL_SCORE }
            return HomeCategoryStats(
                trading = stat { it.isTrading },
                important = Stat(
                    importantEvents.size,
                    when {
                        critical > 0 -> "$critical critical"
                        importantEvents.isNotEmpty() -> "Needs a look"
                        else -> "Nothing urgent"
                    }
                ),
                messages = stat { it.category.equals("MESSAGES", ignoreCase = true) },
                emails = stat { it.category.equals("EMAIL", ignoreCase = true) },
                banking = stat { it.category.equals("BANKING", ignoreCase = true) },
                delivery = stat { it.category.equals("DELIVERY", ignoreCase = true) }
            )
        }
    }
}
