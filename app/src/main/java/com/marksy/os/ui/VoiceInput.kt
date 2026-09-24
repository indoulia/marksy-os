package com.marksy.os.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** In-app speech recognition; the external RECOGNIZE_SPEECH activity silently cancelled on this OEM build. */
class VoiceInputState internal constructor(private val context: Context) {
    var listening by mutableStateOf(false)
        private set
    /** 0..1 microphone level while listening, for the mic pulse. */
    var level by mutableFloatStateOf(0f)
        private set

    internal var onPartial: (String) -> Unit = {}
    internal var onFinal: (String) -> Unit = {}
    internal var onError: (String) -> Unit = {}
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (listening) return
        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        listening = true
        speech.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
    }

    internal fun release() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { level = 0f }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.firstResult()?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            level = 0f
            val text = results.firstResult()
            if (text != null) onFinal(text) else onError("Didn't catch that. Try again.")
        }

        override fun onError(error: Int) {
            listening = false
            level = 0f
            onError(voiceErrorMessage(error))
        }
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.ifBlank { null }
}

internal fun voiceErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice questions."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "Voice recognition needs a network connection."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognition is busy. Try again in a moment."
    SpeechRecognizer.ERROR_AUDIO -> "Couldn't access the microphone."
    else -> "Voice input failed (code $error). Try again."
}

@Composable
fun rememberVoiceInput(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit): VoiceInputState {
    val context = LocalContext.current
    val state = remember { VoiceInputState(context) }
    val partial by rememberUpdatedState(onPartial)
    val final by rememberUpdatedState(onFinal)
    val error by rememberUpdatedState(onError)
    state.onPartial = { partial(it) }
    state.onFinal = { final(it) }
    state.onError = { error(it) }
    DisposableEffect(state) { onDispose { state.release() } }
    return state
}
