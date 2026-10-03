package com.marksy.os.capture.projection

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.marksy.os.capture.CaptureFailure
import com.marksy.os.capture.CaptureMethod
import com.marksy.os.capture.CaptureOutcome
import com.marksy.os.capture.ImageBytes
import com.marksy.os.data.MarksyContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * One user-authorized screen capture (EPIC-038): foreground before the projection, one frame on "Capture now",
 * then everything is released. The frame lives in memory only and is never written anywhere.
 */
class ScreenCaptureService : Service() {
    private val controller = CaptureSessionController.shared
    private val main = Handler(Looper.getMainLooper())
    private var frames: HandlerThread? = null
    private var framesHandler: Handler? = null
    private var projection: MediaProjection? = null
    @Volatile private var display: VirtualDisplay? = null
    private var densityDpi = 0
    private var workflowId = NO_WORKFLOW
    private var capturing = false
    private var ended = false
    @Volatile private var contentVisible = true

    // Frames thread only.
    private var reader: ImageReader? = null
    private var latest: Image? = null

    private val timeout = Runnable { end(CaptureFailure.SESSION_TIMEOUT) }

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { end(CaptureFailure.PROJECTION_REVOKED) }
        }

        override fun onCapturedContentResize(width: Int, height: Int) = resize(width, height)

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            contentVisible = isVisible
        }
    }

    // Keeps only the newest frame, unread, so "Capture now" gets the screen as it is even when nothing redraws.
    private val onImage = ImageReader.OnImageAvailableListener { source ->
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@OnImageAvailableListener
        latest?.close()
        latest = image
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_CAPTURE -> capture()
            ACTION_STOP -> end(intent.getStringExtra(EXTRA_CODE)?.takeIf { it in CaptureFailure.REPORTABLE } ?: CaptureFailure.PROJECTION_REVOKED)
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (workflowId != NO_WORKFLOW && !ended && !capturing) end(CaptureFailure.PROJECTION_REVOKED)
        release()
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_WORKFLOW, NO_WORKFLOW)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        // startForegroundService obliges startForeground, and API 34+ requires it before getMediaProjection.
        if (!enterForeground()) {
            report(id, CaptureFailure.CAPTURE_FAILED)
            stopIfIdle()
            return
        }
        if (workflowId != NO_WORKFLOW) {
            // A session is running: a second consent token is never queued or reused.
            if (id != workflowId) report(id, CaptureFailure.CAPTURE_FAILED)
            return
        }
        if (id == NO_WORKFLOW || data == null) {
            stopIfIdle()
            return
        }
        workflowId = id
        val opened = try {
            open(resultCode, data)
        } catch (e: Exception) {
            log("capture session workflow=$id open-failed (${e.javaClass.simpleName})")
            false
        }
        if (!opened || !controller.activate(id)) {
            end(CaptureFailure.CAPTURE_FAILED)
            return
        }
        main.postDelayed(timeout, SESSION_TIMEOUT_MS)
        log("capture session workflow=$id active")
    }

    private fun open(resultCode: Int, data: Intent): Boolean {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val granted = manager.getMediaProjection(resultCode, data) ?: return false
        projection = granted
        val thread = HandlerThread("MarksyCapture").also { it.start() }
        val handler = Handler(thread.looper)
        frames = thread
        framesHandler = handler
        // API 34+ requires the callback before createVirtualDisplay, which may run only once per projection.
        granted.registerCallback(callback, handler)
        val (width, height) = displaySize()
        densityDpi = resources.displayMetrics.densityDpi
        val first = newReader(width, height, handler)
        reader = first
        display = granted.createVirtualDisplay(
            "MarksyCapture", width, height, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, first.surface, null, handler
        )
        return display != null
    }

    private fun newReader(width: Int, height: Int, handler: Handler): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES).apply { setOnImageAvailableListener(onImage, handler) }

    // Frames thread: a rotation or the chosen app window changed size; the same display is resized, never recreated.
    private fun resize(width: Int, height: Int) {
        val handler = framesHandler ?: return
        val target = display ?: return
        if (width <= 0 || height <= 0) return
        latest?.close()
        latest = null
        val fresh = newReader(width, height, handler)
        runCatching {
            target.resize(width, height, densityDpi)
            target.surface = fresh.surface
        }
        reader?.close()
        reader = fresh
    }

    private fun capture() {
        val id = controller.beginCapture() ?: return stopIfIdle()
        val handler = framesHandler
        if (handler == null || id != workflowId) return end(CaptureFailure.CAPTURE_FAILED)
        capturing = true
        main.removeCallbacks(timeout)
        handler.post {
            val grab = takeFrame()
            main.post { onFrame(grab) }
        }
    }

    // Frames thread.
    private fun takeFrame(): Grab {
        if (!contentVisible) return Grab.Failed(CaptureFailure.CONTENT_HIDDEN)
        val image = latest ?: return Grab.Failed(CaptureFailure.CAPTURE_FAILED)
        latest = null
        return try {
            image.use(::copy)
        } catch (e: Exception) {
            log("capture session workflow=$workflowId frame-failed (${e.javaClass.simpleName})")
            Grab.Failed(CaptureFailure.CAPTURE_FAILED)
        }
    }

    private fun copy(image: Image): Grab {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        if (plane.pixelStride != 4 || width <= 0 || height <= 0) return Grab.Failed(CaptureFailure.CAPTURE_FAILED)
        // Rows are copied without their padding so the hash is stable for an identical screen.
        val rowBytes = width * 4
        val packed = ByteArray(rowBytes * height)
        val source = plane.buffer
        for (y in 0 until height) {
            source.position(y * plane.rowStride)
            source.get(packed, y * rowBytes, rowBytes)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(packed)) }
        return Grab.Frame(bitmap, ImageBytes.sha256(packed), FrameInspector.isProtected(FrameInspector.sampleRgba(packed, width, height)))
    }

    private fun onFrame(grab: Grab) {
        val id = workflowId
        ended = true
        release()
        log("capture session workflow=$id frame=${grab is Grab.Frame}")
        work.launch {
            val outcome = when (grab) {
                is Grab.Failed -> gateway().submitFailure(CaptureMethod.MEDIA_PROJECTION, id, grab.code)
                is Grab.Frame -> recognize(id, grab)
            }
            controller.finish(id, outcome)
            main.post { stopSelf() }
        }
    }

    private suspend fun recognize(id: Long, frame: Grab.Frame): CaptureOutcome {
        if (frame.protected) {
            frame.bitmap.recycle()
            return gateway().submitFailure(CaptureMethod.MEDIA_PROJECTION, id, CaptureFailure.PROTECTED_SCREEN)
        }
        val ocr = try {
            MarksyContainer.textRecognizer.recognize(frame.bitmap)
        } catch (e: Exception) {
            log("capture session workflow=$id ocr-failed (${e.javaClass.simpleName})")
            return gateway().submitFailure(CaptureMethod.MEDIA_PROJECTION, id, CaptureFailure.CAPTURE_FAILED)
        } finally {
            frame.bitmap.recycle()
        }
        // The workflow carries the source's package identity, so no hint is passed.
        return gateway().submitRecognized(CaptureMethod.MEDIA_PROJECTION, null, false, frame.hash, ocr.text, ocr.meanConfidence, id)
    }

    /** Ends a session that took no frame; a frame in progress finishes on its own. */
    private fun end(code: String) {
        if (workflowId == NO_WORKFLOW) return stopIfIdle()
        if (ended || capturing) return
        ended = true
        val id = workflowId
        release()
        log("capture session workflow=$id ended $code")
        work.launch {
            controller.finish(id, gateway().submitFailure(CaptureMethod.MEDIA_PROJECTION, id, code))
            main.post { stopSelf() }
        }
    }

    private fun release() {
        main.removeCallbacks(timeout)
        display?.release()
        display = null
        projection?.let { granted ->
            projection = null
            runCatching { granted.stop() }
        }
        val handler = framesHandler
        val thread = frames
        framesHandler = null
        frames = null
        handler?.post {
            latest?.close()
            latest = null
            reader?.close()
            reader = null
            thread?.quitSafely()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun report(id: Long, code: String) {
        if (id == NO_WORKFLOW) return
        work.launch { controller.finish(id, gateway().submitFailure(CaptureMethod.MEDIA_PROJECTION, id, code)) }
    }

    private fun stopIfIdle() {
        if (workflowId == NO_WORKFLOW) stopSelf()
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        true
    } catch (e: Exception) {
        log("capture session foreground-refused (${e.javaClass.simpleName})")
        false
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val captureNow = PendingIntent.getActivity(
            this, 0, Intent(this, CaptureTriggerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen capture is on")
            .setContentText("Open the tip, then tap Capture now")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Capture now", captureNow)
            .addAction(0, "Stop", stop)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> {
        val windows = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return windows.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        val metrics = DisplayMetrics()
        windows.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun gateway() = MarksyContainer.captureGateway(applicationContext)

    private sealed interface Grab {
        class Frame(val bitmap: Bitmap, val hash: String, val protected: Boolean) : Grab
        class Failed(val code: String) : Grab
    }

    companion object {
        const val CHANNEL_ID = "marksy_screen_capture"
        private const val NOTIFICATION_ID = 7_301
        private const val NO_WORKFLOW = -1L
        private const val MAX_IMAGES = 3
        const val SESSION_TIMEOUT_MS = 3 * 60 * 1000L
        private const val ACTION_START = "com.marksy.os.capture.START"
        private const val ACTION_CAPTURE = "com.marksy.os.capture.CAPTURE"
        private const val ACTION_STOP = "com.marksy.os.capture.STOP"
        private const val EXTRA_WORKFLOW = "workflowId"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_CODE = "code"
        // Outcomes are recorded even if the service is destroyed meanwhile.
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun log(message: String) {
            Log.i("MarksyCapture", message)
        }

        /** The consent result goes to the service exactly once; it is never stored. */
        internal fun startIntent(context: Context, resultCode: Int, data: Intent, workflowId: Long): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_WORKFLOW, workflowId).putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data)

        internal fun captureIntent(context: Context): Intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE)

        /** Ends a running session without a frame; [code] is reported for its workflow. */
        fun stop(context: Context, code: String = CaptureFailure.PROJECTION_REVOKED) {
            val state = CaptureSessionController.shared.state.value
            if (state is CaptureSessionState.Idle || state is CaptureSessionState.Finished) return
            runCatching { context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_CODE, code)) }
        }
    }
}
