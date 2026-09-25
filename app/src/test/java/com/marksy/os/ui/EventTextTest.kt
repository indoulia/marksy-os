package com.marksy.os.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EventTextTest {
    // SMS apps repeat the sender as a "SENDER:" prefix; the card already shows it as the headline.
    @Test fun stripsSenderPrefixThatRepeatsTheTitle() {
        assertEquals(
            "Dear Client \nBUY | CROPSTER AGRO |",
            EventText.body("KISHAN ENTERPRISE", "KISHAN ENTERPRISE: Dear Client \nBUY | CROPSTER AGRO |")
        )
        assertEquals("Dear Customer, plan ahead", EventText.body("JK-IDBIBK-S", "jk-idbibk-s:  Dear Customer, plan ahead"))
    }

    @Test fun keepsBodyThatDoesNotStartWithTheTitle() {
        assertEquals("•  ICICI Bank  •  Bill due on 6th Oct", EventText.body("₹14,917", "•  ICICI Bank  •  Bill due on 6th Oct"))
        assertEquals("KISHAN said hi", EventText.body("KISHAN ENTERPRISE", "KISHAN said hi"))
    }

    @Test fun hidesScoringReasonsThatSayNothingAboutTheMessage() {
        assertEquals(
            listOf("Amount ₹14,917 (unknown)"),
            EventText.usefulReasons(listOf("Recent", "High classification confidence", "Amount ₹14,917 (unknown)", "Standard notification"))
        )
    }
}
