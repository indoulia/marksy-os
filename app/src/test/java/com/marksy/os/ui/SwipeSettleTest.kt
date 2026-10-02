package com.marksy.os.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeSettleTest {
    private fun settle(moved: Float, trayPx: Float, velocity: Float = 0f, anchor: Float = 0f) =
        swipeSettle(moved, velocity, anchor, trayPx, nudgePx = 8f, openPx = 64f, flingPx = 1200f)

    // Device report: a group's one-column tray (52) is narrower than the 64 open distance, so slow pulls always sprang back.
    @Test
    fun slowPullOpensANarrowGroupTray() {
        assertEquals(-52f, settle(moved = -45f, trayPx = 52f))
    }

    @Test
    fun rowTrayStillNeedsADeliberatePull() {
        assertEquals(0f, settle(moved = -40f, trayPx = 156f))
        assertEquals(-156f, settle(moved = -70f, trayPx = 156f))
    }
}
