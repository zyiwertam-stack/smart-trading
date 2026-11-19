package com.smarttrading.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max

enum class CapturePhase { STOPPED, STARTING, CAPTURING, ERROR }

enum class VideoScanDuration(val label: String, val durationMillis: Long) {
    TEN_SECONDS("10 seconds", 10_000),
    THIRTY_SECONDS("30 seconds", 30_000),
    ONE_MINUTE("1 minute", 60_000),
    TWO_MINUTES("2 minutes", 120_000),
    FIVE_MINUTES("5 minutes", 300_000),
    TEN_MINUTES("10 minutes", 600_000),
    CONTINUOUS("Continuous", 0)
}

enum class CaptureFrequency(val label: String, val intervalMillis: Long) {
    ONE_FPS("1 frame/sec", 1_000),
    TWO_FPS("2 frames/sec", 500),
    FIVE_FPS("5 frames/sec", 200)
}

data class CaptureStatus(
    val phase: CapturePhase = CapturePhase.STOPPED,
    val detail: String = "Screen capture has not started",
    val lastFrameAt: Instant? = null,
    val framesReceived: Long = 0,
    val lastFrameMonotonicNanos: Long? = null,
    val frameWidth: Int? = null,
    val frameHeight: Int? = null
)

data class CapturedFrame(
    val bitmap: Bitmap,
    val capturedAt: Instant,
    val elapsedRealtimeNanos: Long,
    val sourceWidth: Int,
    val sourceHeight: Int
)

object CaptureRuntime {
    private val mutableStatus = MutableStateFlow(CaptureStatus())
    private val mutableFrames = MutableSharedFlow<CapturedFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val status = mutableStatus.asStateFlow()
    val frames = mutableFrames.asSharedFlow()
    internal val hasFrameSubscribers: Boolean
        get() = mutableFrames.subscriptionCount.value > 0

    internal fun starting() {
        mutableStatus.value = CaptureStatus(CapturePhase.STARTING, "Waiting for the first screen frame")
    }

    internal fun capturing() {
        val current = mutableStatus.value
        if (current.lastFrameAt == null) {
            mutableStatus.value = current.copy(
                phase = CapturePhase.CAPTURING,
                detail = "Screen capture is active"
            )
        }
    }

    internal fun frameReceived(
        capturedAt: Instant,
        elapsedRealtimeNanos: Long,
        width: Int,
        height: Int,
        bitmap: Bitmap?
    ) {
        val current = mutableStatus.value
        mutableStatus.value = current.copy(
            phase = CapturePhase.CAPTURING,
            detail = "Receiving screen frames",
            lastFrameAt = capturedAt,
            framesReceived = current.framesReceived + 1,
            lastFrameMonotonicNanos = elapsedRealtimeNanos,
            frameWidth = width,
            frameHeight = height
        )
        if (bitmap != null) {
            mutableFrames.tryEmit(
                CapturedFrame(bitmap, capturedAt, elapsedRealtimeNanos, width, height)
            )
        }
    }

    internal fun stopped(detail: String = "Screen capture stopped") {
        mutableStatus.value = CaptureStatus(CapturePhase.STOPPED, detail)
    }

    internal fun failed(detail: String) {
        mutableStatus.value = CaptureStatus(CapturePhase.ERROR, detail)
    }
}

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var scanStopTask: Runnable? = null
    private var frameIntervalNanos = CaptureFrequency.TWO_FPS.intervalMillis * NANOS_PER_MILLISECOND
    private var lastPublishedFrameNanos = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        CaptureRuntime.starting()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("SMART TRADING Screen Vision")
                .setContentText("Screen capture is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = readProjectionIntent(intent)
        if (resultCode != Activity.RESULT_OK || data == null) {
            failCapture("Screen capture permission data is missing or invalid")
            return START_NOT_STICKY
        }

        try {
            val scanDuration = intent?.getLongExtra(EXTRA_SCAN_DURATION_MS, 0) ?: 0
            val frameInterval = intent?.getLongExtra(
                EXTRA_FRAME_INTERVAL_MS,
                CaptureFrequency.TWO_FPS.intervalMillis
            ) ?: CaptureFrequency.TWO_FPS.intervalMillis
            if (scanDuration !in VideoScanDuration.values().map { it.durationMillis } ||
                frameInterval !in CaptureFrequency.values().map { it.intervalMillis }
            ) {
                failCapture("Video scan duration or frame frequency is invalid")
                return START_NOT_STICKY
            }
            startCapture(resultCode, data, scanDuration, frameInterval)
        } catch (error: RuntimeException) {
            failCapture("Could not start screen capture: ${error.message ?: error.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent,
        scanDurationMillis: Long,
        frameIntervalMillis: Long
    ) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )
        val thread = HandlerThread("smart-trading-screen-capture").apply { start() }
        val handler = Handler(thread.looper)
        imageReader = reader
        captureThread = thread
        captureHandler = handler
        frameIntervalNanos = frameIntervalMillis * NANOS_PER_MILLISECOND
        lastPublishedFrameNanos = 0
        reader.setOnImageAvailableListener(::onImageAvailable, handler)

        val mediaProjection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, data)
        projection = mediaProjection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCapture(stopProjection = false)
                CaptureRuntime.stopped("Screen capture permission was revoked")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        projectionCallback = callback
        mediaProjection.registerCallback(callback, handler)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "SMART TRADING Screen Vision",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        ) ?: throw IllegalStateException("Android did not create a virtual display")
        CaptureRuntime.capturing()
        if (scanDurationMillis > 0) {
            val stopTask = Runnable { stopCapture("Video scan completed") }
            scanStopTask = stopTask
            handler.postDelayed(stopTask, scanDurationMillis)
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (lastPublishedFrameNanos != 0L &&
                image.timestamp - lastPublishedFrameNanos < frameIntervalNanos
            ) return
            lastPublishedFrameNanos = image.timestamp
            val capturedAt = Instant.now()
            val bitmap = if (CaptureRuntime.hasFrameSubscribers) {
                image.toBitmap()
            } else {
                null
            }
            CaptureRuntime.frameReceived(
                capturedAt = capturedAt,
                elapsedRealtimeNanos = image.timestamp,
                width = image.width,
                height = image.height,
                bitmap = bitmap
            )
        } catch (error: RuntimeException) {
            failCapture("Could not read a screen frame: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.firstOrNull() ?: throw IllegalStateException("Screen frame has no pixel plane")
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride < 4 || rowStride < width * pixelStride) {
            throw IllegalStateException("Unsupported screen frame pixel layout")
        }

        val scale = max(1, ceil(max(width, height) / MAX_FRAME_DIMENSION.toDouble()).toInt())
        val outputWidth = ceil(width / scale.toDouble()).toInt()
        val outputHeight = ceil(height / scale.toDouble()).toInt()
        val pixels = IntArray(outputWidth * outputHeight)
        val buffer = plane.buffer
        var target = 0
        for (y in 0 until height step scale) {
            val rowStart = y * rowStride
            for (x in 0 until width step scale) {
                val offset = rowStart + x * pixelStride
                val red = buffer.get(offset).toInt() and 0xff
                val green = buffer.get(offset + 1).toInt() and 0xff
                val blue = buffer.get(offset + 2).toInt() and 0xff
                val alpha = buffer.get(offset + 3).toInt() and 0xff
                pixels[target++] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    }

    private fun failCapture(detail: String) {
        releaseCapture()
        CaptureRuntime.failed(detail)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCapture(detail: String = "Screen capture stopped") {
        releaseCapture()
        CaptureRuntime.stopped(detail)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCapture(stopProjection: Boolean = true) {
        scanStopTask?.let { captureHandler?.removeCallbacks(it) }
        scanStopTask = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        val activeProjection = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        if (activeProjection != null && callback != null) {
            activeProjection.unregisterCallback(callback)
        }
        if (stopProjection) activeProjection?.stop()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen Vision", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    private fun readProjectionIntent(intent: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }

    override fun onDestroy() {
        releaseCapture()
        if (CaptureRuntime.status.value.phase == CapturePhase.CAPTURING ||
            CaptureRuntime.status.value.phase == CapturePhase.STARTING
        ) {
            CaptureRuntime.stopped("Screen capture service stopped")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.smarttrading.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_SCAN_DURATION_MS = "scan_duration_ms"
        const val EXTRA_FRAME_INTERVAL_MS = "frame_interval_ms"
        private const val CHANNEL = "screen_vision"
        private const val NOTIFICATION_ID = 10
        private const val MAX_FRAME_DIMENSION = 1280
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
