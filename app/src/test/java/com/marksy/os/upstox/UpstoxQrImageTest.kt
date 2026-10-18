package com.marksy.os.upstox

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpstoxQrImageTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun readsTheCodeInAnImage() {
        val text = """{"type":"marksy-upstox","v":1,"api_key":"fake-key"}"""
        val size = 300
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) black else white }
        assertEquals(text, UpstoxQrImage.decode(size, size, pixels))
    }

    @Test
    fun aBlankImageHasNoCode() {
        assertNull(UpstoxQrImage.decode(100, 100, IntArray(100 * 100) { white }))
    }
}
