package com.marksy.os.capture

import com.marksy.os.capture.projection.ScreenCaptureStart

/** Every user-facing capture message, keyed by the fixed codes; nothing here ever includes captured text. */
object CaptureMessages {
    fun failure(code: String?): String = when (code) {
        CaptureFailure.CAPTURE_DENIED -> "Screen capture wasn't allowed, so nothing was captured."
        CaptureFailure.PROJECTION_REVOKED -> "Screen capture was stopped before a screenshot was taken."
        CaptureFailure.PROTECTED_SCREEN -> "This app blocks screen capture."
        CaptureFailure.SOURCE_UNAVAILABLE -> "That app couldn't be opened."
        CaptureFailure.UNSUPPORTED_ANDROID -> "This phone can't capture the screen."
        CaptureFailure.IMAGE_UNREADABLE -> "That image couldn't be read."
        CaptureFailure.SESSION_TIMEOUT -> "Capture timed out. Start again once the tip is on screen."
        CaptureFailure.CONTENT_HIDDEN -> "The app hid its content, so nothing was captured."
        CaptureFailure.ONE_TIME_CODE -> "That screen shows a one-time code, so it was dropped. Nothing was kept."
        CaptureFailure.NOT_A_TIP -> "No trading call found in that image."
        CaptureFailure.STALE_SESSION -> "Capture was interrupted when Marksy closed."
        CaptureFailure.UNSUPPORTED_SOURCE -> "That app isn't on the capture list."
        else -> "Capture didn't work. Try again."
    }

    /** Null when the capture started and there is nothing to explain. */
    fun start(result: ScreenCaptureStart): String? = when (result) {
        ScreenCaptureStart.STARTED -> null
        ScreenCaptureStart.BUSY -> "Another capture is already running."
        ScreenCaptureStart.NOTIFICATIONS_OFF -> "Turn on Marksy notifications first; capture shows a notification you tap to take the screenshot."
        ScreenCaptureStart.WORKFLOW_CLOSED -> "This tip has expired or was already handled."
        ScreenCaptureStart.UNSUPPORTED -> failure(CaptureFailure.UNSUPPORTED_ANDROID)
    }

    const val ALREADY_REVIEWED = "That capture was already reviewed."

    fun ambiguity(code: String): String? = when (code) {
        Ambiguity.AMBIGUOUS_SYMBOL -> "More than one ticker matched; check the symbol."
        Ambiguity.MALFORMED_PRICE -> "A price didn't read cleanly; check the levels."
        Ambiguity.MISSING_TARGET -> "No target found."
        Ambiguity.MISSING_STOP_LOSS -> "No stop loss found."
        Ambiguity.CONFLICTING_SIDE -> "Both buy and sell appear; choose one."
        Ambiguity.LOW_OCR_CONFIDENCE -> "The text was hard to read; check every field."
        else -> null
    }

    fun method(method: CaptureMethod): String = when (method) {
        CaptureMethod.NOTIFICATION_LISTENER -> "Notification"
        CaptureMethod.USER_SHARED_IMAGE -> "Shared screenshot"
        CaptureMethod.USER_SELECTED_IMAGE -> "Picked screenshot"
        CaptureMethod.MEDIA_PROJECTION -> "Screen capture"
    }
}
