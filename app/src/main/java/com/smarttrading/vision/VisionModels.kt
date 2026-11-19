package com.smarttrading.vision

import java.time.Instant
import kotlin.math.max

data class PixelFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val capturedAt: Instant
) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height)
    }

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun crop(rect: PixelRect): PixelFrame {
        val left = rect.left.coerceIn(0, width - 1)
        val top = rect.top.coerceIn(0, height - 1)
        val right = rect.right.coerceIn(left + 1, width)
        val bottom = rect.bottom.coerceIn(top + 1, height)
        val cropped = IntArray((right - left) * (bottom - top))
        for (y in top until bottom) {
            pixels.copyInto(cropped, (y - top) * (right - left), y * width + left, y * width + right)
        }
        return PixelFrame(right - left, bottom - top, cropped, capturedAt)
    }
}

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = max(0, width) * max(0, height)
    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
}

data class ProcessedFrame(
    val frame: PixelFrame,
    val imageQuality: Double,
    val detectedRegions: List<PixelRect>,
    val processingTimestamp: Instant
)

enum class ChartStatus { DETECTED, NO_CHART, LOW_QUALITY, PARTIALLY_OBSCURED }

data class ChartDetectionResult(
    val status: ChartStatus,
    val bounds: PixelRect?,
    val width: Int,
    val height: Int,
    val capturedAt: Instant,
    val quality: Double,
    val fullyVisible: Boolean,
    val obscured: Boolean,
    val reasons: List<String>
) {
    companion object {
        fun noChart(frame: PixelFrame, reason: String) = ChartDetectionResult(
            ChartStatus.NO_CHART, null, 0, 0, frame.capturedAt, 0.0, false, false, listOf(reason)
        )
    }
}

enum class CandleDirection { BULLISH, BEARISH, UNKNOWN }
enum class CandleColor { GREEN, RED, BLUE, WHITE, OTHER, UNKNOWN }

data class Candle(
    val x: Int,
    val y: Int,
    val top: Int,
    val bottom: Int,
    val bodyTop: Int,
    val bodyBottom: Int,
    val upperShadow: Int,
    val lowerShadow: Int,
    val color: CandleColor,
    val direction: CandleDirection,
    val relativeHeight: Double,
    val relativeWidth: Double,
    val detectedAt: Instant
) {
    val bodyHeight: Int get() = bodyBottom - bodyTop + 1
    val range: Int get() = bottom - top + 1
}

data class CandleCalibration(
    val bullishRgb: Int? = null,
    val bearishRgb: Int? = null,
    val colorTolerance: Int = 100,
    val minimumRelativeBody: Double = 0.004,
    val maximumRelativeBody: Double = 0.5
) {
    init {
        require(colorTolerance in 1..255)
        require(minimumRelativeBody in 0.0..maximumRelativeBody && maximumRelativeBody <= 1.0)
    }
}

data class CandleRecognitionResult(
    val candles: List<Candle>,
    val quality: Double,
    val reasons: List<String>
)
