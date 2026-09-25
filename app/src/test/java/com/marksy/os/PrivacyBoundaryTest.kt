package com.marksy.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EPIC-019/020/021 static privacy guard: the intelligence, memory and connector layers must never
 * read device location or SMS, and the manifest must not gain precise-location or SMS permissions.
 * Coarse location exists only for the weather card (weather/WeatherRepository.kt).
 */
class PrivacyBoundaryTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun manifestHasNoPreciseLocationOrSmsPermissions() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        listOf("ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CONTACTS", "GET_ACCOUNTS")
            .forEach { assertTrue("$it must not be requested", !manifest.contains(it)) }
    }

    @Test
    fun intelligenceMemoryAndConnectorsNeverTouchDeviceLocationOrSms() {
        val forbidden = Regex("LocationManager|FusedLocation|getLastKnownLocation|requestLocationUpdates|\\blatitude\\b|\\blongitude\\b|Telephony\\.Sms|SmsManager")
        val offenders = listOf("intelligence", "connector", "ai").flatMap { pkg ->
            File(main, "java/com/marksy/os/$pkg").walkTopDown().filter { it.extension == "kt" }
                .filter { forbidden.containsMatchIn(it.readText()) }.map { it.name }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
