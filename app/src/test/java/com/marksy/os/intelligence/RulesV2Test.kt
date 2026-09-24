package com.marksy.os.intelligence

import androidx.room.Room
import com.marksy.os.data.RuleRunner
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.RuleEngine.Action
import com.marksy.os.intelligence.RuleEngine.Cmp
import com.marksy.os.intelligence.RuleEngine.Condition
import com.marksy.os.intelligence.RuleEngine.Field
import com.marksy.os.intelligence.RuleEngine.Rule
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulesV2Test {
    private val zone = ZoneOffset.UTC
    private val night = LocalDateTime.of(2026, 9, 23, 23, 30).toInstant(zone).toEpochMilli()
    private val noon = LocalDateTime.of(2026, 9, 23, 12, 0).toInstant(zone).toEpochMilli()
    private lateinit var db: MarksyDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun event(category: String, title: String, body: String, at: Long, pkg: String = "com.bank", confidence: Float = .9f, id: Long = 0) =
        NotificationEventEntity(id = id, sourcePackage = pkg, sourceName = pkg, sourceKey = "k$title$at$body", eventFingerprint = "f$title$at$body", title = title, body = body,
            postedAt = at, category = category, priority = 50, confidence = confidence, isTrading = false)

    private fun subject(e: NotificationEventEntity) = RuleEngine.Subject.of(e, zone)

    private val bigNightDebit = Condition.All(listOf(
        Condition.AnyOf(listOf(Condition.Leaf(Field.CATEGORY, Cmp.EQ, "BANKING"), Condition.Leaf(Field.CATEGORY, Cmp.EQ, "PAYMENTS"))),
        Condition.Leaf(Field.AMOUNT, Cmp.GTE, "1000"),
        Condition.Leaf(Field.HOUR, Cmp.BETWEEN, "22..6"),
        Condition.Not(Condition.Leaf(Field.ENTITY, Cmp.CONTAINS, "MERCHANT:swiggy"))
    ))

    @Test
    fun nestedAndOrNotWithAmountHourAndEntity() {
        val rule = Rule("r", "Big night debit", condition = bigNightDebit)
        assertTrue(RuleEngine.matches(rule, subject(event("BANKING", "Debited", "Rs 5,000 debited at AMAZON RETAIL.", night))))
        assertFalse(RuleEngine.matches(rule, subject(event("BANKING", "Debited", "Rs 5,000 debited at AMAZON RETAIL.", noon))))
        assertFalse(RuleEngine.matches(rule, subject(event("BANKING", "Debited", "Rs 500 debited", night))))
        assertFalse(RuleEngine.matches(rule, subject(event("PAYMENTS", "Paid", "Rs 5,000 paid at SWIGGY.", night))))
        assertFalse(RuleEngine.matches(rule, subject(event("BILLS", "Due", "Rs 5,000 due", night))))
    }

    @Test
    fun confidenceSenderAndDayConditions() {
        val c = Condition.All(listOf(
            Condition.Leaf(Field.CONFIDENCE, Cmp.GTE, "0.8"),
            Condition.Leaf(Field.SENDER, Cmp.EQ, "rahul"),
            Condition.Leaf(Field.DAY_OF_WEEK, Cmp.EQ, "WED,THU")
        ))
        assertTrue(RuleEngine.evaluate(c, subject(event("MESSAGES", "Rahul", "hi", noon, "com.whatsapp"))))
        assertFalse(RuleEngine.evaluate(c, subject(event("MESSAGES", "Rahul", "hi", noon, "com.whatsapp", confidence = .6f))))
    }

    @Test
    fun conflictResolutionIsDeterministicByPriorityThenId() {
        val e = event("PROMOTIONS", "Sale", "big sale", noon, "com.shop")
        val archive = Rule("b-archive", "Archive promos", category = "PROMOTIONS", action = Action.ARCHIVE)
        val resolve = Rule("a-resolve", "Resolve promos", category = "PROMOTIONS", action = Action.MARK_RESOLVED)
        val highlight = Rule("c-hl", "Highlight", category = "PROMOTIONS", action = Action.HIGHLIGHT)

        assertEquals(Action.MARK_RESOLVED, RuleEngine.evaluate(listOf(archive, resolve, highlight), e).stateAction) // same priority: id order
        assertEquals(Action.MARK_RESOLVED, RuleEngine.evaluate(listOf(highlight, resolve, archive), e).stateAction)
        val eval = RuleEngine.evaluate(listOf(archive.copy(priority = 5), resolve, highlight), e)
        assertEquals(Action.ARCHIVE, eval.stateAction)
        assertEquals(65, eval.priority) // highlight still applies
        assertTrue(eval.explanations.any { it.contains("overridden by \"Archive promos\"") })

        val applied = RuleApplication.apply(listOf(resolve, highlight), e)
        assertEquals("RESOLVED", applied.event.lifecycleState)
        assertFalse(applied.archived)
    }

    @Test
    fun conditionJsonRoundTripsAndMalformedTreesAreRejected() {
        val json = RuleEngine.conditionToJson(bigNightDebit)
        assertEquals(bigNightDebit, RuleEngine.conditionFromJson(JSONObject(json.toString())))
        assertNull(RuleEngine.conditionFromJson(JSONObject("""{"op":"AND","children":[]}""")))
        assertNull(RuleEngine.conditionFromJson(JSONObject("""{"op":"OR","children":[{"field":"NOPE","cmp":"EQ","value":"x"}]}""")))
        assertNull(RuleEngine.conditionFromJson(JSONObject("""{"op":"XOR"}""")))
    }

    @Test
    fun storeVersionsRulesOnlyWhenBehaviourChangesAndKeepsConditions() {
        val store = RuleStore(RuntimeEnvironment.getApplication())
        val rule = Rule("r1", "Night", category = "BANKING", condition = bigNightDebit, priority = 3)
        store.save(listOf(rule))
        assertEquals(1, store.load().single().version)
        store.save(listOf(store.load().single().copy(enabled = false, name = "Renamed")))
        assertEquals(1, store.load().single().version)
        store.save(listOf(store.load().single().copy(action = Action.ARCHIVE)))
        val loaded = store.load().single()
        assertEquals(2, loaded.version)
        assertEquals(bigNightDebit, loaded.condition)
        assertEquals(3, loaded.priority)
    }

    @Test
    fun simpleEditorShapeRoundTripsAndRefusesAdvancedTrees() {
        val s = SimpleCondition(anyWords = listOf("otp", "code"), hours = "22..6", minAmount = 1000.0, minConfidencePercent = 80)
        assertEquals(s, SimpleCondition.from(s.toCondition()))
        assertNull(SimpleCondition.from(bigNightDebit))
    }

    @Test
    fun simulationWritesNothingAndApplyToHistoryIsIdempotentAndAudited() = runBlocking {
        val dao = db.notificationEventDao()
        val now = noon + 3_600_000
        val ids = listOf(
            dao.insert(event("PROMOTIONS", "Sale", "50% off", noon, "com.shop")),
            dao.insert(event("PROMOTIONS", "Deal", "coupon", noon + 1, "com.shop")),
            dao.insert(event("BANKING", "Debited", "Rs 5 debited", noon + 2))
        )
        val runner = RuleRunner(dao, db.ruleExecutionDao(), clock = { now }, zone = { zone })
        val rule = Rule("promo", "Archive promos", enabled = false, category = "PROMOTIONS", action = Action.ARCHIVE)

        val (considered, hits) = runner.simulate(rule)
        assertEquals(3, considered)
        assertEquals(ids.take(2).toSet(), hits.map { it.eventId }.toSet())
        assertTrue(ids.none { dao.getById(it)!!.archived })

        assertEquals(2, runner.applyToHistory(rule))
        assertTrue(ids.take(2).all { dao.getById(it)!!.archived })
        assertEquals(0, runner.applyToHistory(rule))
        assertEquals(2, runner.executionCount("promo"))
        assertEquals(1, runner.applyToHistory(rule.copy(version = 2, action = Action.MARK_RESOLVED, category = "BANKING")))

        // A highlight applied to history boosts once, however often it is re-run.
        val hl = Rule("hl", "Highlight bank", category = "BANKING", action = Action.HIGHLIGHT)
        runner.applyToHistory(hl)
        runner.applyToHistory(hl)
        assertEquals(65, dao.getById(ids[2])!!.priority)
    }

    @Test
    fun overriddenCaptureExecutionDoesNotBlockLaterApplyToHistory() = runBlocking {
        val dao = db.notificationEventDao()
        val e = event("PROMOTIONS", "Sale", "big sale", noon, "com.shop")
        val rules = listOf(Rule("a", "Archive", category = "PROMOTIONS", action = Action.ARCHIVE, priority = 1), Rule("b", "Resolve", category = "PROMOTIONS", action = Action.MARK_RESOLVED))
        val applied = RuleApplication.apply(listOf(rules[1]), e)
        val id = dao.insert(applied.event.copy(lifecycleState = "ACTIVE"))
        val runner = RuleRunner(dao, db.ruleExecutionDao(), clock = { noon + 1000 }, zone = { zone })
        runner.recordCapture(id, RuleEngine.evaluate(rules, dao.getById(id)!!))
        assertEquals(1, runner.applyToHistory(rules[1]))
        assertTrue(runner.history("b").single().applied)
    }

    @Test
    fun captureAuditRecordsOverriddenRules() = runBlocking {
        val dao = db.notificationEventDao()
        val e = event("PROMOTIONS", "Sale", "big sale", noon, "com.shop")
        val rules = listOf(Rule("a", "Archive", category = "PROMOTIONS", action = Action.ARCHIVE, priority = 1), Rule("b", "Resolve", category = "PROMOTIONS", action = Action.MARK_RESOLVED))
        val applied = RuleApplication.apply(rules, e)
        val id = dao.insert(applied.event)
        val runner = RuleRunner(dao, db.ruleExecutionDao(), clock = { noon }, zone = { zone })
        runner.recordCapture(id, applied.evaluation)
        runner.recordCapture(id, applied.evaluation)
        val a = runner.history("a").single()
        val b = runner.history("b").single()
        assertTrue(a.applied)
        assertFalse(b.applied)
        assertEquals("Overridden by rule a", b.note)
    }
}
