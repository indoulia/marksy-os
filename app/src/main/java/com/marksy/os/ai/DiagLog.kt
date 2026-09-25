package com.marksy.os.ai

import android.util.Log

/**
 * Diagnostic log for the intelligence/connector layers. Callers pass ids, states and error
 * categories only — never prompts, notification/email text, tokens or locations.
 */
object DiagLog {
    fun i(tag: String, msg: String) = emit { Log.i(tag, msg) }
    fun w(tag: String, msg: String) = emit { Log.w(tag, msg) }

    // android.util.Log is a throwing stub in plain JVM unit tests; logging must never change behaviour.
    private inline fun emit(block: () -> Unit) { try { block() } catch (_: RuntimeException) { } }
}
