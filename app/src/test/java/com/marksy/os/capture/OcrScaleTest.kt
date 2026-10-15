package com.marksy.os.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrScaleTest {
    @Test
    fun imagesWithinTheLimitKeepTheirSize() {
        assertEquals(1080 to 2048, OcrScale.fit(1080, 2048))
        assertEquals(640 to 480, OcrScale.fit(640, 480))
    }

    @Test
    fun longEdgeIsCappedAndAspectKept() {
        assertEquals(922 to 2048, OcrScale.fit(1440, 3200))
        assertEquals(2048 to 1536, OcrScale.fit(4096, 3072))
    }

    @Test
    fun decodeSampleNeverDropsBelowTheLimit() {
        assertEquals(1, OcrScale.sampleSize(1440, 3200))
        assertEquals(2, OcrScale.sampleSize(8000, 6000))
        assertEquals(4, OcrScale.sampleSize(9000, 4000))
        assertEquals(1, OcrScale.sampleSize(500, 500))
    }

    @Test
    fun meanConfidenceIgnoresMissingValues() {
        assertEquals(0.8f, OcrScale.meanConfidence(listOf(0.9f, 0.7f))!!, 0.0001f)
        assertEquals(0.5f, OcrScale.meanConfidence(listOf(0.5f, Float.NaN, -1f))!!, 0.0001f)
        assertNull(OcrScale.meanConfidence(emptyList()))
    }
}
