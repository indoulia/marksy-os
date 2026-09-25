package com.marksy.os.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BirthdaySyncTest {
    @Test fun readsContactBirthdayWithOrWithoutYear() {
        assertEquals(3 to 14, BirthdaySync.monthDay("1990-03-14"))
        assertEquals(3 to 14, BirthdaySync.monthDay("--03-14"))
        assertEquals(12 to 1, BirthdaySync.monthDay("2001-12-01T00:00:00Z"))
        assertNull(BirthdaySync.monthDay("someday"))
        assertNull(BirthdaySync.monthDay("1990-13-40"))
    }
}
