package com.marksy.os.capture

import android.content.Intent
import android.graphics.Bitmap
import androidx.room.Room
import com.marksy.os.data.local.CaptureDao
import com.marksy.os.data.local.MarksyDatabase
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageIntakeTest {
    private lateinit var db: MarksyDatabase
    private lateinit var dao: CaptureDao
    private lateinit var gateway: CaptureGateway
    private val logs = mutableListOf<String>()
    private val decoded = mutableListOf<Bitmap>()
    private var ocr: () -> OcrResult = { OcrResult("BUY ABC @ 500 Target 650 SL 470", 0.95f) }
    private var recognized = 0
    private val recognizer = object : TextRecognizer {
        override suspend fun recognize(bitmap: Bitmap): OcrResult {
            recognized++
            return ocr()
        }
    }
    private val bytes = "png-bytes".toByteArray()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        dao = db.captureDao()
        val registry = CaptureSourceRegistry(capturePackages = { setOf("com.research.app") }, displayName = { "App $it" })
        gateway = CaptureGateway(dao, registry, log = { logs += it })
    }

    @After
    fun tearDown() = db.close()

    private fun intake(decode: (ByteArray) -> Bitmap? = { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { decoded += it } }) =
        ImageIntake(gateway, recognizer, decode, log = { logs += it })

    @Test
    fun sharedImageBecomesACandidateKeyedByItsBytesAndIsReleased() = runBlocking {
        val outcome = intake().submit(bytes, CaptureMethod.USER_SHARED_IMAGE, sourceHint = null) as CaptureOutcome.Candidate
        val candidate = dao.candidate(outcome.id)!!
        assertEquals("sha256:${ImageBytes.sha256(bytes)}", candidate.evidenceRef)
        assertEquals(CaptureMethod.USER_SHARED_IMAGE.name, candidate.method)
        assertFalse(candidate.sourceVerified)
        assertTrue(decoded.single().isRecycled)
    }

    @Test
    fun aSenderThatSharedItsIdentityIsAVerifiedSource() = runBlocking {
        val outcome = intake().submit(bytes, CaptureMethod.USER_SHARED_IMAGE, sourceHint = "com.research.app") as CaptureOutcome.Candidate
        val candidate = dao.candidate(outcome.id)!!
        assertEquals("com.research.app", candidate.sourcePackage)
        assertTrue(candidate.sourceVerified)
    }

    @Test
    fun unreadableOrUndecodableImagesFailWithoutRecognition() = runBlocking {
        assertEquals(CaptureOutcome.Failed(CaptureFailure.IMAGE_UNREADABLE), intake().submit(null, CaptureMethod.USER_SELECTED_IMAGE, null))
        assertEquals(CaptureOutcome.Failed(CaptureFailure.IMAGE_UNREADABLE), intake { null }.submit(bytes, CaptureMethod.USER_SELECTED_IMAGE, null))
        assertEquals(0, recognized)
        assertEquals(CaptureFailure.IMAGE_UNREADABLE, dao.lastFailureCode())
    }

    @Test
    fun recognitionErrorFailsWithAFixedCodeAndLogsNoMessage() = runBlocking {
        ocr = { throw IllegalStateException("BUY SECRET @ 1") }
        assertEquals(CaptureOutcome.Failed(CaptureFailure.CAPTURE_FAILED), intake().submit(bytes, CaptureMethod.USER_SELECTED_IMAGE, null))
        assertTrue(decoded.single().isRecycled)
        assertTrue(logs.none { "SECRET" in it })
    }

    @Test
    fun theSameImageTwiceIsADuplicate() = runBlocking {
        val first = intake().submit(bytes, CaptureMethod.USER_SELECTED_IMAGE, null) as CaptureOutcome.Candidate
        assertEquals(CaptureOutcome.Duplicate(first.id), intake().submit(bytes, CaptureMethod.USER_SHARED_IMAGE, null))
    }

    @Test
    fun readingStopsAtTheSizeCap() {
        assertNull(ImageBytes.read("0123456789".byteInputStream(), maxBytes = 9))
        assertNull(ImageBytes.read(ByteArray(0).inputStream()))
        assertEquals("0123456789", String(ImageBytes.read("0123456789".byteInputStream(), maxBytes = 10)!!))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ImageBytes.sha256("abc".toByteArray()))
    }

    @Test
    fun onlyAnImageShareIsTakenAndOnlyAForeignSenderIsAHint() {
        assertTrue(ShareIntake.isImageShare(Intent.ACTION_SEND, "image/png"))
        assertFalse(ShareIntake.isImageShare(Intent.ACTION_SEND, "text/plain"))
        assertFalse(ShareIntake.isImageShare(Intent.ACTION_VIEW, "image/png"))
        assertEquals("com.research.app", ShareIntake.sourceHint(" com.research.app ", "com.marksy.os"))
        assertNull(ShareIntake.sourceHint("com.marksy.os", "com.marksy.os"))
        assertNull(ShareIntake.sourceHint(null, "com.marksy.os"))
    }

    @Test
    fun outcomesRouteToAReviewOrAFixedNotice() {
        assertEquals(5L, CaptureRouting.reviewCandidate(CaptureOutcome.Candidate(5, CaptureState.EXTRACTED)))
        assertEquals(3L, CaptureRouting.reviewCandidate(CaptureOutcome.Duplicate(3)))
        assertNull(CaptureRouting.reviewCandidate(CaptureOutcome.NotATip))
        assertEquals(CaptureFailure.NOT_A_TIP, CaptureRouting.notice(CaptureOutcome.NotATip))
        assertEquals(CaptureFailure.PROTECTED_SCREEN, CaptureRouting.notice(CaptureOutcome.Failed(CaptureFailure.PROTECTED_SCREEN)))
        assertNull(CaptureRouting.notice(CaptureOutcome.Candidate(5, CaptureState.EXTRACTED)))
    }
}
