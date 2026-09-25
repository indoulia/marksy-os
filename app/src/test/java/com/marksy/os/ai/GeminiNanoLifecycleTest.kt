package com.marksy.os.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** EPIC-019 pre-device hardening: lifecycle edges of the Gemini Nano adapter with a scripted runtime (no AICore). */
class GeminiNanoLifecycleTest {
    private class Backend(
        @Volatile var availability: PromptBackend.Availability = PromptBackend.Availability.AVAILABLE,
        var reply: suspend (String) -> String? = { """{"intent":"PAYMENTS","range":"today","confidence":0.9}""" }
    ) : PromptBackend {
        val probes = AtomicInteger()
        val downloads = AtomicInteger()
        val warmups = AtomicInteger()
        val generates = AtomicInteger()
        var probe: suspend () -> Unit = {}
        var onDownload: suspend () -> Unit = {}
        var onWarmup: suspend () -> Unit = {}
        override val runtimeVersion = "fake/1"
        override suspend fun availability(): PromptBackend.Availability { probes.incrementAndGet(); probe(); return availability }
        override suspend fun modelName() = "nano-test"
        override suspend fun download() { downloads.incrementAndGet(); onDownload(); availability = PromptBackend.Availability.AVAILABLE }
        override suspend fun warmup() { warmups.incrementAndGet(); onWarmup() }
        override suspend fun generate(prompt: String): String? { generates.incrementAndGet(); return reply(prompt) }
    }

    private var now = 1_000L
    private fun model(b: Backend) = GeminiNanoModel(b, clock = { now })
    private val template = PromptTemplates.ASK_INTERPRET
    private suspend fun run(service: IntelligenceService) =
        service.run(template, mapOf("question" to "q", "previous" to "none"), parse = { it.getString("intent") }, fallback = { "FALLBACK" })

    @Test
    fun aicoreUnavailableNeverInvokesTheRuntimeAndFallsBack() = runBlocking {
        val b = Backend(PromptBackend.Availability.UNAVAILABLE)
        val m = model(b)
        val service = IntelligenceService(listOf(m))
        service.refresh()
        assertEquals(ModelState.NOT_AVAILABLE, m.state())
        val out = run(service)
        assertEquals("FALLBACK", out.value)
        assertEquals(AiInvocation.Outcome.NO_MODEL, out.invocation.outcome)
        assertEquals(0, b.generates.get())
        // prepare() on an unsupported device must not try to download anything.
        assertEquals(ModelState.NOT_AVAILABLE, m.prepare())
        assertEquals(0, b.downloads.get())
        assertEquals(0, b.warmups.get())
    }

    @Test
    fun prepareWhileTheOsIsAlreadyDownloadingDoesNotStartASecondDownload() = runBlocking {
        val b = Backend(PromptBackend.Availability.DOWNLOADING)
        val m = model(b)
        assertEquals(ModelState.DOWNLOADING, m.prepare())
        assertEquals(0, b.downloads.get())
        assertNull(IntelligenceService(listOf(m)).available(AiTask.INTERPRET_QUERY))
    }

    @Test
    fun repeatedAndConcurrentPrepareDownloadsAndWarmsUpOnce() = runBlocking {
        val b = Backend(PromptBackend.Availability.DOWNLOADABLE).apply { onDownload = { delay(50) }; onWarmup = { delay(20) } }
        val m = model(b)
        val states = (1..5).map { async(kotlinx.coroutines.Dispatchers.Default) { m.prepare() } }.awaitAll()
        assertTrue(states.all { it == ModelState.READY })
        m.prepare()
        assertEquals(1, b.downloads.get())
        assertEquals(1, b.warmups.get())
    }

    @Test
    fun cancelledDownloadLeavesARetryableStateNotAFailure() = runBlocking {
        val b = Backend(PromptBackend.Availability.DOWNLOADABLE).apply { onDownload = { awaitCancellation() } }
        val m = model(b)
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { m.prepare() }
        withTimeout(2_000) { while (b.downloads.get() == 0) delay(5) }
        job.cancelAndJoin()
        assertEquals(ModelState.UNKNOWN, m.state())
        assertEquals(0, m.diagnostics().failures)
        b.onDownload = {}
        assertEquals(ModelState.READY, m.prepare())
    }

    @Test
    fun modelDroppedAfterInitialisationIsReprobedAndStopsBeingUsed() = runBlocking {
        val b = Backend()
        val m = model(b)
        val service = IntelligenceService(listOf(m))
        service.refresh()
        assertEquals("PAYMENTS", run(service).value)
        // AICore evicts the model: the next call errors, falls back, and forces a re-probe.
        b.reply = { throw IllegalStateException("model released") }
        b.availability = PromptBackend.Availability.DOWNLOADABLE
        val failed = run(service)
        assertEquals("FALLBACK", failed.value)
        assertEquals(AiInvocation.Outcome.ERROR, failed.invocation.outcome)
        service.refresh()
        assertEquals(ModelState.NOT_INSTALLED, m.state())
        assertNull(service.available(AiTask.INTERPRET_QUERY))
        assertEquals(AiInvocation.Outcome.NO_MODEL, run(service).invocation.outcome)
    }

    @Test
    fun emptyNullAndInvalidStructuredResponsesFallBack() = runBlocking {
        listOf<String?>(null, "", "   ", "{}", "[]", """{"intent":"PAYMENTS"}""", """{"intent":"PAYMENTS","range":"today","confidence":7}""").forEach { r ->
            val b = Backend(reply = { r })
            val m = model(b); m.refresh()
            val out = run(IntelligenceService(listOf(m)))
            assertEquals("reply=$r", "FALLBACK", out.value)
            assertTrue("reply=$r", out.invocation.outcome in setOf(AiInvocation.Outcome.ERROR, AiInvocation.Outcome.INVALID_OUTPUT))
        }
    }

    @Test
    fun hungInferenceIsBoundedByTheServiceTimeout() = runBlocking {
        val b = Backend(reply = { awaitCancellation() })
        val m = model(b); m.refresh()
        val started = System.nanoTime()
        val out = run(IntelligenceService(listOf(m), timeoutMs = 100))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("FALLBACK", out.value)
        assertEquals(AiInvocation.Outcome.TIMEOUT, out.invocation.outcome)
        assertTrue("took $tookMs ms", tookMs < 2_000)
        // A timeout is not a runtime failure: the model stays READY for the next question.
        assertEquals(ModelState.READY, m.state())
    }

    @Test
    fun defaultTimeoutsAreFinite() {
        assertTrue(IntelligenceService.DEFAULT_TIMEOUT_MS in 1..10_000)
        assertTrue(IntelligenceService.REFRESH_TIMEOUT_MS in 1..10_000)
    }

    @Test
    fun hungAvailabilityProbeCannotBlockRefresh() = runBlocking {
        val b = Backend().apply { probe = { awaitCancellation() } }
        val m = model(b)
        val started = System.nanoTime()
        IntelligenceService(listOf(m), refreshTimeoutMs = 100).refresh()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertEquals(ModelState.UNKNOWN, m.state())
    }

    @Test
    fun callerCancellationPropagatesAndIsNotCountedAsAFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val b = Backend(reply = { entered.complete(Unit); awaitCancellation() })
        val m = model(b); m.refresh()
        val service = IntelligenceService(listOf(m), timeoutMs = 60_000)
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { run(service) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, m.diagnostics().failures)
        assertEquals(ModelState.READY, m.state())
    }

    @Test
    fun concurrentRequestsShareOneModelWithoutReinitialising() = runBlocking {
        val b = Backend(reply = { delay(10); """{"intent":"BILLS_DUE","range":"default","confidence":0.9}""" })
        val m = model(b); m.prepare()
        val service = IntelligenceService(listOf(m))
        val results = coroutineScope { (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { run(service).value } }.awaitAll() }
        assertTrue(results.all { it == "BILLS_DUE" })
        assertEquals(8, b.generates.get())
        assertEquals(8, m.diagnostics().calls)
        assertEquals(1, b.warmups.get())
        assertEquals(1, b.probes.get())
    }

    @Test
    fun diagnosticsAndInvocationRecordsCarryNoPromptOrOutputText() = runBlocking {
        val secret = "account 1234 balance"
        val records = mutableListOf<AiInvocation>()
        val b = Backend(reply = { throw RuntimeException("echo: $it") })
        val m = model(b); m.refresh()
        IntelligenceService(listOf(m), sink = { records += it }).run(template, mapOf("question" to secret), parse = { it }, fallback = { null })
        assertFalse(m.diagnostics().toString().contains(secret))
        assertFalse(records.toString().contains(secret))
        assertEquals("RuntimeException", m.diagnostics().lastError)
    }
}
