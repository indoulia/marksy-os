package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpoDetailFormatterTest {
    // Shapes as the Marksy web app reads them: values in {state, value, asOf} envelopes, lists of objects, evidence.
    private val detail = JSONObject("""
        {"summary":{"id":"ipo_1","companyName":"Acme Foods"},
         "currentSnapshotId":"snap_9",
         "companyOverview":{"state":"AVAILABLE","value":"Packaged foods maker.","asOf":"2026-09-20T10:00:00Z"},
         "keyDates":[{"label":"Allotment","date":{"state":"AVAILABLE","value":"2026-10-03"}},{"label":"Refunds","date":{"state":"MISSING","value":null}}],
         "anchorBook":{"state":"AVAILABLE","totalAmountCrore":123.5,"mutualFund":{"participated":true,"percent":42.0},"locator":"x"},
         "analystOpinions":[{"publication":"Broker Weekly","stance":"POSITIVE","positives":["Strong brand","Debt free"],"evidence":[{"id":1},{"id":2}]}],
         "risks":[],
         "outcome":{"listingReturnPercent":{"state":"AVAILABLE","value":18.25},"horizonReturnPercent":{"D5":{"state":"MISSING"}}}}
    """.trimIndent())

    @Test fun rendersEveryAvailableSectionReadablyAndSkipsIdsMissingValuesAndEvidenceBodies() {
        val rows = IpoDetailFormatter.rows(detail, skip = setOf("summary"))
        val text = rows.joinToString("\n") { "  ".repeat(it.depth) + it.label + (it.value?.let { v -> ": $v" } ?: "") }
        assertTrue(text, text.contains("Company overview: Packaged foods maker."))
        assertTrue(text, text.contains("Allotment: 3 Oct 2026"))
        assertTrue(text, text.contains("Total amount (₹ Cr): 123.5"))
        assertTrue(text, text.contains("Participated: Yes"))
        assertTrue(text, text.contains("Percent: 42%"))
        assertTrue(text, text.contains("Broker Weekly"))
        assertTrue(text, text.contains("Stance: Positive"))
        assertTrue(text, text.contains("Positives: Strong brand, Debt free"))
        assertTrue(text, text.contains("Evidence: 2 items"))
        assertTrue(text, text.contains("Listing return: 18.25%"))
        assertFalse(text, text.contains("Refunds") || text.contains("snap_9") || text.contains("Locator") || text.contains("Risks") || text.contains("D5"))
        assertEquals("Anchor book", rows.first { it.depth == 0 && it.label.startsWith("Anchor") }.label)
    }

    // Seen on device: GMP history repeated a dozen near-identical readings; "premium" and "premiumPercent" both read "Premium".
    @Test fun longListsShowTheNewestFewAndEnvelopesAndStatusesCollapseToOneLine() {
        val readings = (1..6).joinToString(",") { """{"source":"ipoji.com","premium":$it,"premiumPercent":${it * 3},"observedAt":"2026-09-2${it}T10:00:00Z","retrievedAt":"2026-09-2${it}T11:00:00Z"}""" }
        val o = JSONObject("""
            {"gmp":{"readings":[$readings]},
             "trustScore":{"value":0.55,"asOf":"2026-09-25T12:00:00Z"},
             "positiveListingProbability":{"state":"INSUFFICIENT_EVIDENCE","explanation":null},
             "trustQuality":{"value":"MEDIUM","asOf":"2026-09-25T12:00:00Z","basis":null},
             "authoritativeSourcing":{"value":1,"explanation":"12 of 12 facts come from the exchange."},
             "uncertainties":["FINANCIAL_DETERIORATION could not be evaluated: it needs revenue history.","HIGH_LEVERAGE could not be evaluated: it needs the capitalisation statement."],
             "minInvestment":14994}
        """.trimIndent())
        val rows = IpoDetailFormatter.rows(o)
        assertEquals(2, rows.count { it.paragraph })
        val text = rows.joinToString("\n") { "  ".repeat(it.depth) + it.label + (it.value?.let { v -> ": $v" } ?: "") }
        assertTrue(text, text.contains("Trust quality: Medium"))
        val sub = JSONObject("""{"subscriptionByCategory":{"qib":{"value":0.17,"asOf":"2026-09-25T12:00:00Z","evidence":[],"sourceId":null}},
            "trend":{"value":"STABLE","note":"ipoji.com quoted 13, then 13.","evidence":[{"id":1}]},"sourceUrl":"https://x.example"}""")
        val subText = IpoDetailFormatter.rows(sub).joinToString("\n") { it.label + (it.value?.let { v -> ": $v" } ?: "") }
        assertTrue(subText, subText.contains("QIB: 0.17") && subText.contains("Trend: Stable — ipoji.com quoted 13, then 13.") && subText.contains("Source URL: https://x.example"))
        assertTrue(text, text.contains("Authoritative sourcing: 1 — 12 of 12 facts come from the exchange."))
        assertTrue(text, text.contains("Premium: 6") && text.contains("Premium (%): 18%"))
        assertTrue(text, text.contains("…and 3 more"))
        assertFalse(text, text.contains("Premium: 1\n") || text.contains("Retrieved"))
        assertTrue(text, text.contains("Trust score: 0.55"))
        assertTrue(text, text.contains("Positive listing probability: Insufficient evidence"))
        assertTrue(text, text.contains("Min investment: 14,994"))
    }
}
