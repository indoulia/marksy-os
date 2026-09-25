package com.marksy.os.ai

import com.marksy.os.intelligence.AskMarksy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset

/** Adapter contract with a scripted runtime; real AICore execution is only possible on a supported device. */
class GeminiNanoModelTest {
    private class FakeBackend(
        var availability: PromptBackend.Availability = PromptBackend.Availability.AVAILABLE,
        var reply: suspend (String) -> String? = { """{"intent":"PAYMENTS","range":"today","confidence":0.9}""" }
    ) : PromptBackend {
        var probes = 0
        var downloads = 0
        var warmups = 0
        var failProbe: Exception? = null
        var failDownload: Exception? = null
        override val runtimeVersion = "fake/1"
        override suspend fun availability(): PromptBackend.Availability { probes++; failProbe?.let { throw it }; return availability }
        override suspend fun modelName() = "nano-test"
        override suspend fun download() { downloads++; failDownload?.let { throw it }; availability = PromptBackend.Availability.AVAILABLE }
        override suspend fun warmup() { warmups++ }
        override suspend fun generate(prompt: String) = reply(prompt)
    }

    private var now = 1_000L
    private fun model(b: FakeBackend) = GeminiNanoModel(b, clock = { now })

    @Test
    fun capabilityDetectionMapsEveryRuntimeState() = runBlocking {
        val expected = mapOf(
            PromptBackend.Availability.UNAVAILABLE to ModelState.NOT_AVAILABLE,
            PromptBackend.Availability.DOWNLOADABLE to ModelState.NOT_INSTALLED,
            PromptBackend.Availability.DOWNLOADING to ModelState.DOWNLOADING,
            PromptBackend.Availability.AVAILABLE to ModelState.READY
        )
        expected.forEach { (a, s) -> assertEquals(s, model(FakeBackend(a)).refresh()) }
    }

    @Test
    fun unprobedModelIsUnknownAndNotUsedUntilRefreshedLikeAfterProcessRecreation() = runBlocking {
        val b = FakeBackend()
        val m = model(b)
        assertEquals(ModelState.UNKNOWN, m.state())
        val service = IntelligenceService(listOf(m))
        assertNull(service.available(AiTask.INTERPRET_QUERY))
        service.refresh()
        assertEquals("gemini-nano-aicore", service.available(AiTask.INTERPRET_QUERY)?.id)
        assertEquals("nano-test", m.info.version)
        assertTrue(m.info.onDevice)
        // Within the TTL a refresh does not hit the runtime again.
        m.refresh(); assertEquals(1, b.probes)
        now += GeminiNanoModel.STATUS_TTL_MS
        m.refresh(); assertEquals(2, b.probes)
    }

    @Test
    fun probeFailureIsErrorWithoutLeakingTheMessage() = runBlocking {
        val b = FakeBackend().apply { failProbe = IOException("secret prompt text") }
        val m = model(b)
        assertEquals(ModelState.FAILED, m.refresh())
        assertEquals("IOException", m.diagnostics().lastError)
        assertFalse(m.diagnostics().supportsLocalInference)
    }

    @Test
    fun prepareDownloadsThenWarmsUpAndMeasuresInit() = runBlocking {
        val b = FakeBackend(PromptBackend.Availability.DOWNLOADABLE)
        val m = model(b)
        assertEquals(ModelState.READY, m.prepare())
        assertEquals(1, b.downloads)
        assertEquals(1, b.warmups)
        assertEquals(0L, m.diagnostics().initMs)
        assertTrue(m.diagnostics().supportsLocalInference)
    }

    @Test
    fun downloadStartedOutsideTheAppIsReprobedUntilReady() = runBlocking {
        val b = FakeBackend(PromptBackend.Availability.DOWNLOADING)
        val m = model(b)
        assertEquals(ModelState.DOWNLOADING, m.refresh())
        b.availability = PromptBackend.Availability.AVAILABLE
        now += GeminiNanoModel.STATUS_TTL_MS
        assertEquals(ModelState.READY, m.refresh())
    }

    @Test
    fun downloadFailureIsReportedAsError() = runBlocking {
        val b = FakeBackend(PromptBackend.Availability.DOWNLOADABLE).apply { failDownload = IllegalStateException("x") }
        val m = model(b)
        assertEquals(ModelState.FAILED, m.prepare())
        assertEquals("IllegalStateException", m.diagnostics().lastError)
    }

    @Test
    fun generateExtractsJsonAndRecordsLatency() = runBlocking {
        val b = FakeBackend(reply = { "Sure! ```json\n{\"intent\":\"BILLS_DUE\",\"range\":\"default\",\"confidence\":0.8}\n```" })
        val m = model(b); m.refresh()
        assertEquals("""{"intent":"BILLS_DUE","range":"default","confidence":0.8}""", m.generate("q", AiTask.INTERPRET_QUERY))
        assertEquals(1, m.diagnostics().calls)
        assertEquals(0L, m.diagnostics().lastLatencyMs)
    }

    @Test
    fun malformedOutputTimeoutAndErrorsFallBackToDeterministic() = runBlocking {
        val zone = ZoneOffset.UTC
        suspend fun interpret(b: FakeBackend, text: String = "bills"): AskMarksy.Query? {
            val m = model(b); m.refresh()
            return ModelQueryInterpreter(IntelligenceService(listOf(m), timeoutMs = 50)).interpret(text, null, now, zone)
        }
        assertNull(interpret(FakeBackend(reply = { "I think you mean bills" })))
        assertNull(interpret(FakeBackend(reply = { """{"intent":"DELETE_ALL","range":"today","confidence":0.9}""" })))
        assertNull(interpret(FakeBackend(reply = { delay(1_000); "{}" })))
        assertNull(interpret(FakeBackend(reply = { throw IOException("runtime died") })))
        assertNull(interpret(FakeBackend(PromptBackend.Availability.UNAVAILABLE)))
        // Valid output is used for wording the deterministic parser cannot place, never against explicit keywords ("bills").
        assertNull(interpret(FakeBackend()))
        assertEquals(AskMarksy.Intent.PAYMENTS, interpret(FakeBackend(), "anything come in today")?.intent)
    }

    @Test
    fun generateFailureForcesReprobe() = runBlocking {
        val b = FakeBackend(reply = { throw IOException("gone") })
        val m = model(b); m.refresh()
        runCatching { m.generate("q", AiTask.INTERPRET_QUERY) }
        assertEquals(1, m.diagnostics().failures)
        m.refresh()
        assertEquals(2, b.probes)
    }

    @Test
    fun registryShipsTheOnDeviceAdapterOnly() {
        val installed = AiModelRegistry.installed()
        assertEquals(listOf(GeminiNanoModel.ID, MediaPipeGemmaBackend.ID), installed.map { it.info.id })
        assertTrue(installed.all { it.info.onDevice })
    }
}
