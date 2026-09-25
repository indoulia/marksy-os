package com.marksy.os.intelligence

import androidx.room.Room
import com.marksy.os.data.MemoryRepository
import com.marksy.os.data.MemorySettings
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.LocationMemory.Role
import com.marksy.os.intelligence.PersonalMemory.Kind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationMemoryTest {
    private class MemSettings : MemorySettings {
        override var enabled = true
        override var watermark = 0L
        val kinds = Kind.entries.associateWith { true }.toMutableMap()
        override fun isKindEnabled(kind: Kind) = kinds.getValue(kind)
        override fun setKindEnabled(kind: Kind, enabled: Boolean) { kinds[kind] = enabled }
    }

    private val day = 24 * 3_600_000L
    private val t0 = 1_790_000_000_000L
    private var now = t0
    private lateinit var db: MarksyDatabase
    private lateinit var settings: MemSettings
    private lateinit var repo: MemoryRepository
    private lateinit var pipeline: EventIntelligencePipeline
    private var seq = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        settings = MemSettings()
        repo = MemoryRepository(db.memoryDao(), db.notificationEventDao(), db.learningDao(), settings, { now }, { ZoneOffset.UTC })
        pipeline = EventIntelligencePipeline(db.notificationEventDao(), ZoneOffset.UTC)
    }

    @After
    fun tearDown() = db.close()

    private fun e(category: String, title: String, body: String, pkg: String = "in.swiggy.android") = NotificationEventEntity(
        sourcePackage = pkg, sourceName = pkg.substringAfterLast('.'), sourceKey = "k${++seq}", eventFingerprint = "f$seq",
        title = title, body = body, postedAt = now, category = category, priority = 50, confidence = .9f, isTrading = false
    )

    private fun capture(category: String, title: String, body: String, at: Long, pkg: String = "in.swiggy.android") = runBlocking {
        val id = db.notificationEventDao().insert(e(category, title, body, pkg).copy(postedAt = at))
        pipeline.process(id)
        id
    }

    private fun place(key: String) = runBlocking { db.memoryDao().find(Kind.LOCATION.name, key) }

    @Test
    fun extractionFromDeliveriesRidesAndCalendarText() {
        assertEquals(LocationMemory.Place("home", "Home", Role.HOME), LocationMemory.extract(e("DELIVERY", "Order delivered", "Your order was delivered to your Home.")))
        assertEquals(Role.WORK, LocationMemory.extract(e("OTHER", "Uber", "Your ride to Office is arriving", "com.ubercab"))?.role)
        assertEquals("Baner, Pune", LocationMemory.extract(e("DELIVERY", "Delivered", "Delivered to Flat 402, Rose Apts, Baner, Pune 411045"))?.label)
        assertEquals("WeWork Baner", LocationMemory.extract(e("REMINDERS", "Design review", "10:00–11:00\nLocation: WeWork Baner", "com.google.android.calendar"))?.label)
        assertEquals("Phoenix Mall", LocationMemory.extract(e("EMAIL", "Invite", "📍 Phoenix Mall"))?.label)
    }

    @Test
    fun privacyBoundariesNothingPreciseOrGuessed() {
        // A bare street address cannot be coarsened, so it is dropped rather than stored.
        assertNull(LocationMemory.extract(e("DELIVERY", "Delivered", "Delivered to 12 MG Road")))
        // A recipient name is not a place.
        assertNull(LocationMemory.extract(e("DELIVERY", "Delivered", "Your parcel was delivered to Priya")))
        // A calendar location that is just a person's name is not a place.
        assertNull(LocationMemory.extract(e("REMINDERS", "1:1", "Location: Priya Sharma")))
        // A two-part address keeps only the city, never the street.
        assertEquals("Pune", LocationMemory.normalize("12 MG Road, Pune")?.label)
        // Chats carry other people's whereabouts.
        assertNull(LocationMemory.extract(e("MESSAGES", "Rahul", "📍 Koregaon Park", "com.whatsapp")))
        // Online meetings and time-like values are not places.
        assertNull(LocationMemory.extract(e("REMINDERS", "Standup", "Location: Zoom Meeting")))
        assertNull(LocationMemory.extract(e("REMINDERS", "Standup", "Where: Google Meet")))
        assertNull(LocationMemory.extract(e("DELIVERY", "Arriving", "Arriving at your doorstep today")))
        assertNull(LocationMemory.extract(e("PAYMENTS", "Paid", "You paid Rs 500 to Swiggy")))
        // A shop that starts with "Home" is not home.
        assertEquals(Role.PLACE, LocationMemory.normalize("Home Centre, Phoenix Mall")?.role)
        assertNull(LocationMemory.normalize("  ").also { assertNull(LocationMemory.normalize("tbd")) })
    }

    @Test
    fun repeatedObservationsAccumulateConfidenceWithProvenanceAndNoDuplicates() = runBlocking {
        val ids = (0..2).map { d -> capture("DELIVERY", "Delivered", "Your order was delivered to Home", t0 + d * day) }
        capture("OTHER", "Uber", "Your ride to home is arriving", t0 + 3 * day, "com.ubercab")
        capture("DELIVERY", "Delivered", "Your order was delivered to your home.", t0 + 3 * day + 1000)
        now = t0 + 4 * day
        repo.ingest()
        val home = place("home")!!
        assertEquals("Home", home.label)
        assertEquals(5, home.observations)
        assertEquals(t0, home.firstObservedAt)
        assertEquals(t0 + 3 * day + 1000, home.lastObservedAt)
        assertTrue(home.confidence > .5f)
        assertTrue(PersonalMemory.eventIds(home).containsAll(ids))
        assertEquals(listOf("android", "ubercab"), PersonalMemory.sources(home))
        assertEquals(1, db.memoryDao().active(now).count { it.kind == Kind.LOCATION.name })
        assertEquals(t0 + 3 * day + 1000 + 90 * day, home.expiresAt)
    }

    @Test
    fun correctionForgetExpiryAndDisableAreHonoured() = runBlocking {
        capture("DELIVERY", "Delivered", "Delivered to Flat 9, Lake View, Aundh, Pune", t0)
        capture("DELIVERY", "Delivered", "Delivered to your office", t0)
        now = t0 + day
        repo.ingest()
        val aundh = place("aundh pune")!!
        val work = place("work")!!

        // "This isn't my home" style correction keeps the user's label and never expires.
        assertTrue(repo.correct(aundh.id, "Parents' place"))
        // Forget suppresses relearning the same place.
        assertTrue(repo.forget(work.id))
        capture("DELIVERY", "Delivered", "Delivered to Flat 9, Lake View, Aundh, Pune", t0 + 2 * day)
        capture("DELIVERY", "Delivered", "Delivered to your office", t0 + 2 * day)
        now = t0 + 3 * day
        repo.ingest()
        assertEquals("Parents' place", place("aundh pune")!!.label)
        assertNull(place("aundh pune")!!.expiresAt)
        assertEquals(PersonalMemory.STATE_FORGOTTEN, place("work")!!.state)

        // Disabled kind learns nothing new; learned places expire after their TTL.
        settings.kinds[Kind.LOCATION] = false
        capture("DELIVERY", "Delivered", "Delivered to Hinjewadi Phase 2, Pune", t0 + 4 * day)
        now = t0 + 5 * day
        repo.ingest()
        assertNull(place("hinjewadi phase pune"))
        settings.kinds[Kind.LOCATION] = true
        capture("OTHER", "Ola", "Your ride to Viman Nagar, Pune is confirmed", t0 + 6 * day, "com.olacabs.customer")
        now = t0 + 7 * day
        repo.ingest()
        assertTrue(place("viman nagar pune") != null)
        now = t0 + 6 * day + 91 * day
        repo.ingest()
        assertNull(place("viman nagar pune"))
    }
}
