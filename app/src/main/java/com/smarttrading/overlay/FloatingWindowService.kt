package com.smarttrading.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.TextView
import android.util.DisplayMetrics
import com.smarttrading.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.smarttrading.vision.PixelRect

object FloatingAnalysisRuntime {
    private val mutableText = MutableStateFlow("SMART TRADING\nWAIT • MANUAL")
    val text = mutableText.asStateFlow()
    private var overlayFraction: List<Double>? = null

    fun update(value: String) {
        mutableText.value = value
    }

    @Synchronized
    fun updateOverlayBounds(screenWidth: Int, screenHeight: Int, bounds: PixelRect) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        overlayFraction = listOf(
            bounds.left.toDouble() / screenWidth,
            bounds.top.toDouble() / screenHeight,
            bounds.right.toDouble() / screenWidth,
            bounds.bottom.toDouble() / screenHeight
        )
    }

    @Synchronized
    fun excludedRegions(frameWidth: Int, frameHeight: Int): List<PixelRect> {
        val fractions = overlayFraction ?: return emptyList()
        return listOf(
            PixelRect(
                (fractions[0] * frameWidth).toInt().coerceIn(0, frameWidth),
                (fractions[1] * frameHeight).toInt().coerceIn(0, frameHeight),
                (fractions[2] * frameWidth).toInt().coerceIn(0, frameWidth),
                (fractions[3] * frameHeight).toInt().coerceIn(0, frameHeight)
            )
        ).filter { it.area > 0 }
    }

    @Synchronized
    fun clearOverlayBounds() {
        overlayFraction = null
    }
}

class FloatingWindowService : Service() {
    private var window: View? = null
    private var scope: CoroutineScope? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (window != null) return START_STICKY
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(320, 300, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 120
        }
        val label = TextView(this).apply {
            text = FloatingAnalysisRuntime.text.value
            textSize = 12f
            setTextColor(0xffe6edf3.toInt()); setBackgroundColor(0xee16232f.toInt()); setPadding(12, 10, 12, 10)
            setOnClickListener {
                startActivity(Intent(this@FloatingWindowService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            setOnTouchListener(DragListener(params))
        }
        window = label
        getSystemService(WindowManager::class.java).addView(label, params)
        updateOverlayBounds(params)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { serviceScope ->
            serviceScope.launch {
                FloatingAnalysisRuntime.text.collect { label.text = it }
            }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        scope?.cancel()
        scope = null
        window?.let { getSystemService(WindowManager::class.java).removeView(it) }
        window = null
        FloatingAnalysisRuntime.clearOverlayBounds()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private inner class DragListener(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var moved = false
        override fun onTouch(v: View, event: MotionEvent): Boolean = when (event.action) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; true }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 8) moved = true
                params.x = startX - dx.toInt()
                params.y = startY + dy.toInt()
                getSystemService(WindowManager::class.java).updateViewLayout(v, params)
                updateOverlayBounds(params)
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) v.performClick()
                true
            }
            else -> true
        }
    }

    private fun updateOverlayBounds(params: WindowManager.LayoutParams) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        val left = (metrics.widthPixels - params.width - params.x).coerceIn(0, metrics.widthPixels)
        val top = params.y.coerceIn(0, metrics.heightPixels)
        FloatingAnalysisRuntime.updateOverlayBounds(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelRect(left, top, (left + params.width).coerceAtMost(metrics.widthPixels),
                (top + params.height).coerceAtMost(metrics.heightPixels))
        )
    }
}
