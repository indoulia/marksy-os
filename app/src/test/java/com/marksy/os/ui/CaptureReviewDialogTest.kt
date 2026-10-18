package com.marksy.os.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import com.marksy.os.capture.CaptureSourceRegistry
import com.marksy.os.capture.TipSide
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.TipCandidateEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Owner decision 2026-10-18: Buy/Sell match the symbol field's control height; every other pill keeps the small height. Native graphics for real text metrics.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureReviewDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun height(tag: String): Dp = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun buyAndSellAreAsTallAsTheSymbolField() {
        val candidate = TipCandidateEntity(
            evidenceId = 1, method = "USER_SHARED_IMAGE", sourcePackage = "com.research.app", sourceName = "Research", sourceVerified = true,
            capturedAt = 1L, evidenceRef = "sha256:ab", extractedText = "BUY TCS @ 3500 TGT 3700 SL 3400", confidence = .9,
            symbol = "TCS", side = "BUY", entry = 3500.0, target = 3700.0, stopLoss = 3400.0, horizon = null, visibleTimestamp = null,
            ambiguities = "", state = "EXTRACTED", userChoseSend = false, deliveryState = DeliveryState.NOT_APPLICABLE.name, updatedAt = 1L
        )
        val registry = CaptureSourceRegistry(capturePackages = { setOf("com.research.app") }, displayName = { "App $it" })
        compose.setContent { CaptureReviewDialog(candidate, emptyList(), registry, onDismiss = {}, onDecision = { _, _, _, _ -> }) }

        val field = height(ReviewSymbolTag)
        assertEquals(MarksySize.Control, field)
        TipSide.entries.forEach { assertEquals(it.name, field, height(reviewSideTag(it))) }
    }

    @Test
    fun anOrdinaryPillKeepsTheSmallHeight() {
        compose.setContent { Pill("Today", modifier = Modifier.testTag("pill")) {} }
        assertEquals(MarksySize.ControlSmall, height("pill"))
    }
}
