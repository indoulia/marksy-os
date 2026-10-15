package com.marksy.os.capture.projection

/** A FLAG_SECURE window reaches screen capture as black; such a frame is reported, never retried silently. */
object FrameInspector {
    const val PROTECTED_SHARE = 0.98
    private const val NEAR_BLACK = 16
    private const val GRID = 64

    fun isProtected(pixels: IntArray): Boolean = pixels.isNotEmpty() && pixels.count(::nearBlack) >= pixels.size * PROTECTED_SHARE

    private fun nearBlack(argb: Int): Boolean =
        (argb shr 16 and 0xFF) <= NEAR_BLACK && (argb shr 8 and 0xFF) <= NEAR_BLACK && (argb and 0xFF) <= NEAR_BLACK

    /** ARGB samples on a grid over a packed RGBA_8888 frame (4 bytes per pixel, no row padding). */
    fun sampleRgba(rgba: ByteArray, width: Int, height: Int, grid: Int = GRID): IntArray {
        val stepX = maxOf(1, width / grid)
        val stepY = maxOf(1, height / grid)
        val samples = ArrayList<Int>()
        for (y in 0 until height step stepY) for (x in 0 until width step stepX) {
            val i = (y * width + x) * 4
            fun channel(offset: Int) = rgba[i + offset].toInt() and 0xFF
            samples += (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
        return samples.toIntArray()
    }
}
