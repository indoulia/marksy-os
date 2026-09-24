package com.marksy.os.data

import com.marksy.os.data.local.EventActionDao
import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.intelligence.DailyBriefing
import java.time.ZoneId

class BriefingRepository(
    private val eventDao: NotificationEventDao,
    private val actionDao: EventActionDao,
    private val learning: LearningRepository?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    suspend fun briefing(kind: DailyBriefing.Kind? = null): DailyBriefing.Briefing {
        val now = clock()
        val z = zone()
        // Two weeks is enough for pending/upcoming look-back and is bounded by retention anyway.
        val events = eventDao.findInRange(now - LOOKBACK_MS, now + 1, LIMIT)
        val profile = learning?.profile(now) ?: com.marksy.os.intelligence.PersonalLearning.Profile.EMPTY
        return DailyBriefing.build(kind ?: DailyBriefing.defaultKind(now, z), events, actionDao.expected(), profile, now, z)
    }

    private companion object {
        const val LOOKBACK_MS = 14L * 24 * 60 * 60 * 1000
        const val LIMIT = 2000
    }
}
