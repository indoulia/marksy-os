package com.marksy.os.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class PicksBasisTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun label(session: String?, published: String?, basis: String?) = PicksBasis.label(session, published, basis, ist)

    @Test
    fun namesTheSessionAndPublishTime() {
        assertEquals("Mon, 28 Sep close · 16:17 · provisional", label("2026-09-28", "2026-09-28T10:47:00Z", "PROVISIONAL"))
        assertEquals("Fri, 25 Sep close · 26 Sep, 16:02", label("2026-09-25", "2026-09-26T10:32:00+00:00", "FINAL"))
        assertEquals("Mon, 28 Sep close", label("2026-09-28", null, null))
        assertNull(label(null, "2026-09-28T10:47:00Z", "FINAL"))
        assertEquals("28 Sep", PicksBasis.day("2026-09-28"))
    }
}
