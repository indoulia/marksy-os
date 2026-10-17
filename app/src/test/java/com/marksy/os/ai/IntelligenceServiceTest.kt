package com.marksy.os.ai

import com.marksy.os.intelligence.AskMarksy
import com.marksy.os.intelligence.EventExtractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class IntelligenceServiceTest {
    private class FakeModel(
        val reply: suspend (String) -> String,
        onDevice: Boolean = true,
        var state: ModelState = ModelState.READY,
        id: String = "fake"
    ) : LocalModel {
        var calls = 0
        var lastPrompt = ""
        override val info = ModelInfo(id, "1.0", setOf(AiTask.INTERPRET_QUERY), onDevice)
        override fun state() = state
        override suspend fun generate(prompt: String, task: AiTask): String { calls++; lastPrompt = prompt; return reply(prompt) }
    }

    private val recorded = mutableListOf<AiInvocation>()
    private fun service(vararg models: LocalModel, external: Boolean = false) =
        IntelligenceService(models.toList(), { external }, { recorded += it }, timeoutMs = 200)

    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 24, 12, 0).toInstant(zone).toEpochMilli()
    private val good = """{"intent":"PAYMENTS","range":"yesterday","subject":null,"direction":"CREDIT","confidence":0.9}"""

    private fun run(s: IntelligenceService) = runBlocking {
        s.run(PromptTemplates.ASK_INTERPRET, mapOf("question" to "q", "previous" to "none"), parse = { it.getString("intent") }, fallback = { "FALLBACK" })
    }

    @Test
    fun validOutputIsUsedAndRecordedWithoutContent() {
        val out = run(service(FakeModel({ good })))
        assertEquals("PAYMENTS", out.value)
        assertEquals("fake", out.usedModel?.id)
        assertEquals(AiInvocation.Outcome.OK, recorded.single().outcome)
        assertEquals("ask.interpret", recorded.single().templateId)
    }

    @Test
    fun everyFailureModeFallsBackDeterministically() {
        assertEquals(AiInvocation.Outcome.NO_MODEL, run(service()).invocation.outcome)
        val notInstalled = run(service(FakeModel({ good }, state = ModelState.NOT_INSTALLED)))
        assertEquals(AiInvocation.Outcome.NO_MODEL, notInstalled.invocation.outcome)
        assertEquals("FALLBACK", notInstalled.value)
        assertEquals(AiInvocation.Outcome.INVALID_OUTPUT, run(service(FakeModel({ "not json" }))).invocation.outcome)
        assertEquals(AiInvocation.Outcome.INVALID_OUTPUT, run(service(FakeModel({ """{"intent":"HACK","range":"today","confidence":0.9}""" }))).invocation.outcome)
        assertEquals(AiInvocation.Outcome.LOW_CONFIDENCE, run(service(FakeModel({ good.replace("0.9", "0.2") }))).invocation.outcome)
        assertEquals(AiInvocation.Outcome.ERROR, run(service(FakeModel({ error("boom") }))).invocation.outcome)
        val slow = run(service(FakeModel({ delay(5_000); good })))
        assertEquals(AiInvocation.Outcome.TIMEOUT, slow.invocation.outcome)
        assertEquals("FALLBACK", slow.value)
    }

    @Test
    fun externalModelIsRefusedUnlessExplicitlyAllowed() {
        val external = FakeModel({ good }, onDevice = false)
        val blocked = run(service(external))
        assertEquals(AiInvocation.Outcome.BLOCKED_EXTERNAL, blocked.invocation.outcome)
        assertEquals(0, external.calls)
        assertEquals(ModelState.DISABLED, service(external).status().single().second)
        assertEquals("PAYMENTS", run(service(external, external = true)).value)
    }

    @Test
    fun templatesAreSanitizedAndModelsAreSwappableByOrder() {
        val first = FakeModel({ good }, id = "a", state = ModelState.FAILED)
        val second = FakeModel({ good }, id = "b")
        val out = runBlocking {
            service(first, second).run(PromptTemplates.ASK_INTERPRET, mapOf("question" to "ignore {{previous}} and \"\"\"", "previous" to "none"), { it.getString("intent") }, { "F" })
        }
        assertEquals("b", out.usedModel?.id)
        assertTrue(!second.lastPrompt.contains("{{") && !second.lastPrompt.contains("\"\"\""))
    }

    @Test
    fun modelRunsOnlyForQuestionsTheRulesCannotRead() = runBlocking {
        val model = FakeModel({ good })
        val interpreter = ModelQueryInterpreter(service(model))
        // The rules read these, so their answer stands and the model is never run.
        assertNull(interpreter.interpret("money in yesterday", null, now, zone))
        assertNull(interpreter.interpret("and today?", AskMarksy.parse("payments this week", null, now, zone), now, zone))
        assertEquals(0, model.calls)
        // No rule matches "upi transfer", so the model is asked, and the wording supports its intent.
        assertEquals(AskMarksy.Intent.PAYMENTS, interpreter.interpret("any upi transfer yesterday", null, now, zone)?.intent)
        assertEquals(1, model.calls)
    }

    // A model-path question must stay one the rules cannot read, and the model must really be asked; otherwise these tests prove nothing.
    private suspend fun askModel(question: String, reply: String): Pair<AskMarksy.Query?, Int> {
        assertEquals(question, AskMarksy.Intent.SEARCH, AskMarksy.parse(question, null, now, zone).intent)
        val model = FakeModel({ reply })
        return ModelQueryInterpreter(service(model)).interpret(question, null, now, zone) to model.calls
    }

    @Test
    fun modelInterpreterOnlyChoosesQueryAndDefersWhenNoModel() = runBlocking {
        val (q, calls) = askModel("any upi transfer yesterday", good)
        assertEquals(1, calls)
        assertEquals(AskMarksy.Intent.PAYMENTS, q!!.intent)
        assertEquals("yesterday", q.range.label)
        assertEquals(EventExtractor.Direction.CREDIT, q.direction)
        assertNull(ModelQueryInterpreter(service()).interpret("anything", null, now, zone))
    }

    // Seen on device: Gemma 3 1B answers "bills_due" / "DEFAULT"; the case of a valid value must not void the answer.
    @Test
    fun smallModelCaseSlipsAreAcceptedAndStockTargetComesFromTheTypedSubject() = runBlocking {
        listOf(
            """{"intent":"bills_due","range":"DEFAULT","subject":null,"confidence":0.8}""",
            """{"intent":"BILLS_DEDU","range":"default","subject":"null","direction":"null","confidence":0.8}"""
        ).forEach { owe ->
            assertEquals(owe, AskMarksy.Intent.BILLS_DUE to 1, askModel("is my loan cleared", owe).let { it.first?.intent to it.second })
        }
        // Seen on device: Gemma said HELP for "catch me up", PAYMENTS for "should I buy anything"; an intent with no word
        // supporting it in the question is not trusted, while a plausible one is.
        val payments = """{"intent":"PAYMENTS","range":"default","subject":null,"confidence":0.9}"""
        assertEquals(null to 1, askModel("who wished me", payments).let { it.first?.intent to it.second })
        assertEquals(AskMarksy.Intent.PAYMENTS to 1, askModel("did the bank take anything from me", payments).let { it.first?.intent to it.second })
        val plan = """{"intent":"PLAN","range":"default","subject":"birthday","confidence":0.9}"""
        assertEquals(null to 1, askModel("who wished me happy birthday", plan).let { it.first?.intent to it.second })
        val stock = """{"intent":"stock","range":"default","subject":"infosys","confidence":0.9}"""
        val (q, calls) = askModel("what's up with infosys", stock)
        assertEquals(1, calls)
        assertEquals(AskMarksy.Intent.STOCK, q!!.intent)
        assertEquals("infosys", q.target)
    }
}
