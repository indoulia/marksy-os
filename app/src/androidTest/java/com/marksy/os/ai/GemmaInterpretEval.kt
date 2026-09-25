package com.marksy.os.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marksy.os.intelligence.AskMarksy
import com.marksy.os.intelligence.AskMarksy.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * On-device evaluation of Ask interpretation with the imported Gemma model. Run with
 * `adb shell am instrument -w -e class com.marksy.os.ai.GemmaInterpretEval com.marksy.os.test/androidx.test.runner.AndroidJUnitRunner`
 * (not connectedAndroidTest, which uninstalls the app and its data). Skips when no model is imported.
 */
@RunWith(AndroidJUnit4::class)
class GemmaInterpretEval {
    private val cases = listOf(
        "is anything left to settle" to Intent.BILLS_DUE,
        "did the bank take any money from me" to Intent.PAYMENTS,
        "whats up with infosys" to Intent.STOCK,
        "any news on reliance shares" to Intent.STOCK,
        "catch me up" to Intent.IMPORTANT,
        "did anyone reply to my email" to Intent.SOURCE,
        "any cashback or refunds" to Intent.PAYMENTS,
        "should I buy anything today" to Intent.TRADING,
        "anything I should know about" to Intent.IMPORTANT,
        "did my parcel get delivered" to Intent.DELIVERIES,
        "who wished me happy birthday" to Intent.SEARCH,
        "what is due soon" to Intent.BILLS_DUE,
        "how many emails today" to Intent.SOURCE,
        "open the stocks page" to Intent.NAVIGATE,
        // Held out from tuning
        "has any money come in today" to Intent.PAYMENTS,
        "did I get charged for netflix" to Intent.PAYMENTS,
        "how much went to rent" to Intent.PAYMENTS,
        "remind me of anything today" to Intent.PLAN,
        "is the market up today" to Intent.TRADING,
        "any job updates" to Intent.SEARCH,
        "what came in on gmail" to Intent.SOURCE,
        "any word from the landlord" to Intent.FROM_PERSON,
        "is my shipment late" to Intent.DELIVERIES,
        "tell me about tata steel" to Intent.STOCK
    )

    @Test
    fun gemmaInterpretationAccuracy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Gemma model not imported", GemmaModelFile.isInstalled(context))
        val service = IntelligenceService(listOf(GeminiNanoModel(MediaPipeGemmaBackend { context }, id = MediaPipeGemmaBackend.ID)), timeoutMs = 20_000)
        service.prepare(MediaPipeGemmaBackend.ID)
        val interpreters = listOf(ModelQueryInterpreter(service))
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val misses = mutableListOf<String>()
        cases.forEach { (question, expected) ->
            val i = AskMarksy.interpret(question, null, interpreters, now, zone)
            val line = "\"$question\" -> ${i.query.intent} (${i.interpretedBy})"
            Log.i(TAG, line)
            if (i.query.intent != expected) misses += "$line, expected $expected"
        }
        Log.i(TAG, "RESULT ${cases.size - misses.size}/${cases.size} correct")
        misses.forEach { Log.i(TAG, "MISS $it") }
    }

    private companion object { const val TAG = "GemmaEval" }
}
