package com.marksy.os.capture.projection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameInspectorTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun frame(dark: Int, light: Int, darkColour: Int = black) = IntArray(dark) { darkColour } + IntArray(light) { white }

    @Test
    fun anAllBlackFrameIsAProtectedScreen() {
        assertTrue(FrameInspector.isProtected(frame(100, 0)))
        assertTrue(FrameInspector.isProtected(frame(100, 0, darkColour = 0xFF0A0A0A.toInt())))
    }

    @Test
    fun ninetyEightPercentNearBlackIsTheThreshold() {
        assertTrue(FrameInspector.isProtected(frame(98, 2)))
        assertFalse(FrameInspector.isProtected(frame(97, 3)))
    }

    @Test
    fun aDarkThemeIsNotMistakenForAProtectedScreen() {
        assertFalse(FrameInspector.isProtected(frame(100, 0, darkColour = 0xFF121212.toInt())))
    }

    @Test
    fun anEmptyFrameIsNotCalledProtected() {
        assertFalse(FrameInspector.isProtected(IntArray(0)))
    }

    @Test
    fun samplesAreReadFromPackedRgba() {
        val rgba = byteArrayOf(0xFF.toByte(), 0, 0, 0xFF.toByte(), 0, 0, 0xFF.toByte(), 0xFF.toByte())
        assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), FrameInspector.sampleRgba(rgba, width = 2, height = 1))
    }
}
