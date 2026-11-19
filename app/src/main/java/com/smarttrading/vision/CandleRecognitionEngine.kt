package com.smarttrading.vision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class CandleRecognitionEngine {
    fun recognize(
        frame: PixelFrame,
        chart: PixelRect,
        calibration: CandleCalibration = CandleCalibration()
    ): CandleRecognitionResult {
        val left = chart.left.coerceIn(0, frame.width)
        val right = chart.right.coerceIn(left, frame.width)
        val top = chart.top.coerceIn(0, frame.height)
        val bottom = chart.bottom.coerceIn(top, frame.height)
        if (right - left < 16 || bottom - top < 24) {
            return CandleRecognitionResult(emptyList(), 0.0, listOf("Chart region is too small to resolve candle geometry"))
        }

        val coloredColumns = BooleanArray(right - left)
        val directionalPixels = Array(right - left) { ArrayList<Pair<Int, CandleDirection>>() }
        for (x in left until right) {
            for (y in top until bottom) {
                val rgb = frame.pixel(x, y)
                val direction = directionOf(rgb, calibration) ?: continue
                coloredColumns[x - left] = true
                directionalPixels[x - left].add(y to direction)
            }
        }

        val groups = mutableListOf<IntRange>()
        var groupStart = -1
        for (column in coloredColumns.indices) {
            if (coloredColumns[column] && groupStart < 0) groupStart = column
            if ((!coloredColumns[column] || column == coloredColumns.lastIndex) && groupStart >= 0) {
                val end = if (coloredColumns[column.coerceAtMost(coloredColumns.lastIndex)]) column else column - 1
                if (end - groupStart + 1 in 2..max(12, (chart.width * 0.035).toInt())) {
                    groups += groupStart..end
                }
                groupStart = -1
            }
        }

        val candles = groups.mapNotNull { group ->
            val directionCounts = mutableMapOf(CandleDirection.BULLISH to 0, CandleDirection.BEARISH to 0)
            val rowCounts = IntArray(bottom - top)
            var minY = bottom
            var maxY = top - 1
            var colorCounts = mutableMapOf<CandleColor, Int>()
            for (column in group) {
                for ((y, direction) in directionalPixels[column]) {
                    directionCounts[direction] = directionCounts.getValue(direction) + 1
                    rowCounts[y - top]++
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                    val color = classifyColor(frame.pixel(left + column, y), calibration)
                    colorCounts[color] = colorCounts.getOrDefault(color, 0) + 1
                }
            }
            val groupWidth = group.last - group.first + 1
            if (minY >= maxY || groupWidth < 2) return@mapNotNull null
            val bodyThreshold = max(2, ceil(groupWidth * 0.55).toInt())
            var bodyTop = -1
            var bodyBottom = -1
            var runStart = -1
            var bestRun = 0
            for (row in rowCounts.indices) {
                if (rowCounts[row] >= bodyThreshold && runStart < 0) runStart = row
                if ((rowCounts[row] < bodyThreshold || row == rowCounts.lastIndex) && runStart >= 0) {
                    val runEnd = if (rowCounts[row.coerceAtMost(rowCounts.lastIndex)] >= bodyThreshold) row else row - 1
                    val runLength = runEnd - runStart + 1
                    if (runLength > bestRun) {
                        bodyTop = runStart
                        bodyBottom = runEnd
                        bestRun = runLength
                    }
                    runStart = -1
                }
            }
            val bodyHeight = bodyBottom - bodyTop + 1
            val relativeBody = bodyHeight.toDouble() / (bottom - top)
            if (bodyHeight < 2 || relativeBody !in calibration.minimumRelativeBody..calibration.maximumRelativeBody) {
                return@mapNotNull null
            }
            val upCount = directionCounts.getValue(CandleDirection.BULLISH)
            val downCount = directionCounts.getValue(CandleDirection.BEARISH)
            if (max(upCount, downCount) < min(upCount, downCount) * 1.15) return@mapNotNull null
            val direction = if (upCount > downCount) CandleDirection.BULLISH else CandleDirection.BEARISH
            val midX = left + (group.first + group.last) / 2
            if (!chart.contains(midX, (minY + maxY) / 2)) return@mapNotNull null
            val candleColor = colorCounts.maxByOrNull { it.value }?.key ?: CandleColor.UNKNOWN
            Candle(
                x = midX,
                y = (minY + maxY) / 2,
                top = minY,
                bottom = maxY,
                bodyTop = top + bodyTop,
                bodyBottom = top + bodyBottom,
                upperShadow = (top + bodyTop - minY).coerceAtLeast(0),
                lowerShadow = (maxY - top - bodyBottom).coerceAtLeast(0),
                color = candleColor,
                direction = direction,
                relativeHeight = (maxY - minY + 1).toDouble() / (bottom - top),
                relativeWidth = groupWidth.toDouble() / (right - left),
                detectedAt = frame.capturedAt
            )
        }.sortedBy { it.x }

        val quality = when {
            candles.size < 3 -> 0.0
            else -> (candles.size / 16.0).coerceAtMost(1.0) *
                (candles.count { it.color != CandleColor.OTHER && it.color != CandleColor.UNKNOWN }
                    .toDouble() / candles.size)
        }
        val reasons = if (candles.size < 3) {
            listOf("Fewer than three candle bodies passed color, width, and geometry checks")
        } else {
            listOf("Candles were extracted from in-chart color columns with separate body and wick geometry")
        }
        return CandleRecognitionResult(candles, quality, reasons)
    }

    private fun directionOf(rgb: Int, calibration: CandleCalibration): CandleDirection? {
        val customBull = calibration.bullishRgb?.let { colorDistance(rgb, it) <= calibration.colorTolerance } ?: false
        val customBear = calibration.bearishRgb?.let { colorDistance(rgb, it) <= calibration.colorTolerance } ?: false
        if (customBull.xor(customBear)) return if (customBull) CandleDirection.BULLISH else CandleDirection.BEARISH
        val red = rgb shr 16 and 0xff
        val green = rgb shr 8 and 0xff
        val blue = rgb and 0xff
        return when {
            red > green * 1.35 && red > blue * 1.25 && red > 95 -> CandleDirection.BEARISH
            green > red * 1.25 && green > blue * 1.10 && green > 80 -> CandleDirection.BULLISH
            blue > red * 1.3 && blue > green * 1.15 && blue > 100 -> null
            else -> null
        }
    }

    private fun classifyColor(rgb: Int, calibration: CandleCalibration): CandleColor {
        if (calibration.bullishRgb?.let { colorDistance(rgb, it) <= calibration.colorTolerance } == true) return CandleColor.GREEN
        if (calibration.bearishRgb?.let { colorDistance(rgb, it) <= calibration.colorTolerance } == true) return CandleColor.RED
        val red = rgb shr 16 and 0xff
        val green = rgb shr 8 and 0xff
        val blue = rgb and 0xff
        return when {
            red > green * 1.35 && red > blue * 1.25 -> CandleColor.RED
            green > red * 1.25 && green > blue * 1.10 -> CandleColor.GREEN
            blue > red * 1.3 && blue > green * 1.15 -> CandleColor.BLUE
            red > 200 && green > 200 && blue > 200 -> CandleColor.WHITE
            else -> CandleColor.OTHER
        }
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs((a shr 16 and 0xff) - (b shr 16 and 0xff)) +
            abs((a shr 8 and 0xff) - (b shr 8 and 0xff)) +
            abs((a and 0xff) - (b and 0xff))
}
