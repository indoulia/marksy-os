package com.marksy.os.notification

import com.marksy.os.notification.TipTextCleaner.MASK_EMAIL
import com.marksy.os.notification.TipTextCleaner.MASK_NUMBER
import com.marksy.os.notification.TipTextCleaner.MASK_PAN
import com.marksy.os.notification.TipTextCleaner.MASK_PHONE
import com.marksy.os.notification.TipTextCleaner.MASK_USER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Same vectors as marksy-api tests/test_tip_text_cleaning.py; change both files together.
class TipTextCleanerTest {
    @Test
    fun contactDetailsAndTheUsernameAreMasked() {
        val text = "Priya, call +91 98765 43210 or 9123456780, mail priya.k@example.com"
        assertEquals("$MASK_USER, call $MASK_PHONE or $MASK_PHONE, mail $MASK_EMAIL", TipTextCleaner.clean(text, "priya"))
    }

    @Test
    fun panCodesAndLongDigitRunsAreMasked() {
        assertEquals("PAN $MASK_PAN folio $MASK_NUMBER", TipTextCleaner.clean("PAN ABCDE1234F folio 1234567890123", null))
    }

    @Test
    fun pricesStrikesAndShortNumbersSurviveCleaning() {
        val text = "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26 | SELL NIFTY 24500 PE @ 120 | Qty 1500"
        assertEquals(text, TipTextCleaner.clean(text, "user-1"))
    }

    @Test
    fun aMobileNumberBesidePricesIsMaskedWithoutTouchingThePrices() {
        val text = "BUY TATASTEEL CMP 612.50 SL 598 TGT 650. Queries: 98765 43210"
        assertEquals("BUY TATASTEEL CMP 612.50 SL 598 TGT 650. Queries: $MASK_PHONE", TipTextCleaner.clean(text, null))
    }

    @Test
    fun aMobileNumberSplitByANoBreakSpaceIsMasked() {
        assertEquals("Queries: $MASK_PHONE", TipTextCleaner.clean("Queries: 98765 43210", null))
    }

    @Test
    fun theUsernameIsMaskedOnlyAsAWholeWordAndOnlyWhenLongEnough() {
        assertEquals("$MASK_USER: BUY RAVIKUMAR CMP 10 SL 9", TipTextCleaner.clean("Ravi: BUY RAVIKUMAR CMP 10 SL 9", "ravi"))
        assertEquals("al buys ALKEM", TipTextCleaner.clean("al buys ALKEM", "al"))
    }

    @Test
    fun aGroupLabelDropsTheIndividualSender() {
        assertEquals("StockTips", TipTextCleaner.channelLabel(CaptureMedium.WHATSAPP, "Rahul @ StockTips", "user-1"))
    }

    @Test
    fun aOneToOneChatTitledWithAPhoneNumberIsMasked() {
        assertEquals(MASK_PHONE, TipTextCleaner.channelLabel(CaptureMedium.WHATSAPP, "+91 98765 43210", "user-1"))
    }

    @Test
    fun anAppLabelIsKeptWholeAndABlankLabelIsNull() {
        assertEquals("Angel @ One", TipTextCleaner.channelLabel(CaptureMedium.APP_NOTIFICATION, "Angel @ One", null))
        assertNull(TipTextCleaner.channelLabel(CaptureMedium.SMS, "   ", null))
    }

    @Test
    fun aLabelThatIsOnlyMasksDigitsAndPunctuationNamesNoChannel() {
        mapOf(
            "[PHONE]" to true, "[phone]" to true, "+1 [NUMBER]" to true, "[USER], [EMAIL]" to true,
            "Rahul [PHONE]" to false, "StockTips" to false, "56161" to false, "" to false
        ).forEach { (label, expected) -> assertEquals(label, expected, TipTextCleaner.isMaskOnly(label)) }
    }
}
