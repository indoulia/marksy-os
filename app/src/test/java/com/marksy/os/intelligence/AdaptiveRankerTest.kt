package com.marksy.os.intelligence

import com.marksy.os.data.local.LearningOverrideEntity
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.SignalCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Deterministic fixtures: fixed clock, UTC, explicit profiles. */
class AdaptiveRankerTest {
    private val zone = ZoneOffset.UTC
    private val wednesday10 = LocalDateTime.of(2026, 9, 23, 10, 0).toInstant(zone).toEpochMilli()
    private val night2330 = LocalDateTime.of(2026, 9, 23, 23, 30).toInstant(zone).toEpochMilli()
    private val hour = 3_600_000L

    private var seq = 0L
    private fun event(category: String, pkg: String = "com.app", priority: Int = 50, confidence: Float = .9f, postedAt: Long, title: String = "t${seq}") =
        NotificationEventEntity(id = ++seq, sourcePackage = pkg, sourceName = pkg, sourceKey = "k$seq", eventFingerprint = "f$seq",
            title = title, body = "", postedAt = postedAt, category = category, priority = priority, confidence = confidence,
            isTrading = category == "TRADING")

    private fun ctx(events: List<NotificationEventEntity>, now: Long, profile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY) =
        AdaptiveRanker.Context.from(events, now, profile, zone)

    private fun override(pkg: String, pref: PersonalLearning.Preference) = PersonalLearning.buildProfile(
        emptyList(), listOf(LearningOverrideEntity("APP", pkg, pref.name, pkg, 0))
    )

    @Test
    fun protectedOtpIsNeverLoweredEvenByCorrectionOrFatigue() {
        val now = wednesday10
        val noise = (1..20).map { event("OTHER", pkg = "com.bank", postedAt = now - it * 60_000) }
        val otp = event("OTP", pkg = "com.bank", priority = 90, postedAt = now - 2 * hour)
        val r = AdaptiveRanker.rank(otp, ctx(noise + otp, now, override("com.bank", PersonalLearning.Preference.LESS_IMPORTANT)))

        assertTrue(r.protected)
        assertEquals(r.baseScore, r.score)
        assertTrue(r.reasons.any { it.startsWith("Protected otp event") })
        assertEquals(AdaptiveRanker.Prediction.LIKELY_IMPORTANT, r.prediction)
    }

    @Test
    fun learnedEngagementRaisesAndDisengagementLowersWithReasons() {
        val e = event("MESSAGES", pkg = "com.chat", postedAt = wednesday10 - 2 * hour)
        val up = PersonalLearning.buildProfile(listOf(SignalCount("APP", "com.chat", "OPENED", 20, 0, "Chat")), emptyList())
        val down = PersonalLearning.buildProfile(listOf(SignalCount("APP", "com.chat", "IGNORED", 20, 0, "Chat")), emptyList())
        val base = AdaptiveRanker.rank(e, ctx(listOf(e), wednesday10)).score

        val raised = AdaptiveRanker.rank(e, ctx(listOf(e), wednesday10, up))
        val lowered = AdaptiveRanker.rank(e, ctx(listOf(e), wednesday10, down))
        assertEquals(base + 15, raised.score)
        assertEquals(base - 15, lowered.score)
        assertEquals(AdaptiveRanker.Prediction.LIKELY_NOISE, lowered.prediction)
        assertTrue(lowered.reasons.any { it.startsWith("Learned: You engaged with 0% of 20 notifications from Chat") })
    }

    @Test
    fun timeOfDayContextIsDeterministic() {
        val promo = event("PROMOTIONS", postedAt = night2330 - 2 * hour)
        val work = event("WORK", postedAt = wednesday10 - 2 * hour)
        assertEquals(-5, AdaptiveRanker.rank(promo, ctx(listOf(promo), night2330)).contributions.single { it.name == "time_of_day" }.delta)
        assertEquals(+5, AdaptiveRanker.rank(work, ctx(listOf(work), wednesday10)).contributions.single { it.name == "time_of_day" }.delta)
        assertTrue(AdaptiveRanker.rank(work, ctx(listOf(work), night2330)).contributions.none { it.name == "time_of_day" })
    }

    @Test
    fun fatigueRepetitionAndLowConfidenceDampening() {
        val now = wednesday10
        val flood = (1..16).map { event("MESSAGES", pkg = "com.group", postedAt = now - it * 60_000 * 30, title = "Group") }
        val fatigued = AdaptiveRanker.rank(flood.first(), ctx(flood, now))
        assertEquals(-2, fatigued.contributions.single { it.name == "fatigue" }.delta)

        val updates = (1..3).map { event("DELIVERY", pkg = "com.ship", postedAt = now - it * 60_000, title = "Order 1") }
        assertEquals(+5, AdaptiveRanker.rank(updates.first(), ctx(updates, now)).contributions.single { it.name == "repetition" }.delta)

        val weak = event("MESSAGES", pkg = "com.group", confidence = .6f, postedAt = now, title = "Other")
        val weakRanked = AdaptiveRanker.rank(weak, ctx(flood + weak, now))
        assertEquals(weakRanked.baseScore - 1, weakRanked.score) // -2 halved
        assertEquals(weakRanked, AdaptiveRanker.rank(weak, ctx(flood + weak, now)))
    }

    @Test
    fun correctionMovesThreadBetweenInboxBuckets() {
        val e = event("BANKING", pkg = "com.bank", priority = 65, postedAt = wednesday10 - 2 * hour)
        fun bucket(p: PersonalLearning.Profile) =
            SmartInboxModel.inbox(listOf(e), nowMillis = wednesday10, profile = p, zone = zone).sections.entries.first { it.value.isNotEmpty() }.key
        assertEquals(SmartInboxModel.Bucket.IMPORTANT, bucket(PersonalLearning.Profile.EMPTY))
        assertEquals(SmartInboxModel.Bucket.INFORMATIONAL, bucket(override("com.bank", PersonalLearning.Preference.LESS_IMPORTANT)))
    }
}
