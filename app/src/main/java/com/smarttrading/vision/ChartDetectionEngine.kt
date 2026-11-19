package com.smarttrading.vision

import kotlin.math.abs

class ChartDetectionEngine {
    fun detect(
        processed: ProcessedFrame,
        excludedRegions: List<PixelRect> = emptyList(),
        minimumQuality: Double = 0.28
    ): ChartDetectionResult {
        val frame = processed.frame
        if (processed.imageQuality < minimumQuality) {
            return ChartDetectionResult(
                ChartStatus.LOW_QUALITY, null, 0, 0, frame.capturedAt,
                processed.imageQuality, false, false, listOf("Captured image quality is below threshold")
            )
        }

        val candidates = buildSet {
            processed.detectedRegions.forEach { rect ->
                if (rect.width >= frame.width * 0.34 && rect.height >= frame.height * 0.22) add(rect)
            }
            add(PixelRect(
                (frame.width * 0.04).toInt(),
                (frame.height * 0.10).toInt(),
                (frame.width * 0.96).toInt(),
                (frame.height * 0.88).toInt()
            ))
            add(PixelRect(
                (frame.width * 0.06).toInt(),
                (frame.height * 0.16).toInt(),
                (frame.width * 0.84).toInt(),
                (frame.height * 0.88).toInt()
            ))
        }.filter { rect ->
            rect.left >= 0 && rect.top >= 0 && rect.right <= frame.width && rect.bottom <= frame.height &&
                rect.width > 0 && rect.height > 0 &&
                rect.width.toDouble() / rect.height in 0.85..5.5
        }

        val ranked = candidates.map { rect ->
            val evidence = visualEvidence(frame, rect)
            rect to evidence
        }.sortedByDescending { it.second }
        val (bounds, score) = ranked.firstOrNull()
            ?: return ChartDetectionResult.noChart(frame, "No chart-shaped image region was detected")

        val obscuredRatio = excludedRegions.maxOfOrNull { intersectionRatio(bounds, it) } ?: 0.0
        val visible = obscuredRatio < 0.08 && bounds.left > 0 && bounds.top > 0 &&
            bounds.right < frame.width && bounds.bottom < frame.height
        if (score < 0.13) {
            return ChartDetectionResult.noChart(frame, "No region contains sufficient price-plot visual evidence")
        }
        val quality = (score * 1.7 * processed.imageQuality).coerceIn(0.0, 1.0)
        val reasons = buildList {
            add("Candidate region scored on color transitions and repeated vertical plot structure")
            if (obscuredRatio >= 0.08) add("A known overlay overlaps the candidate chart")
            if (!visible) add("Chart bounds touch a screen edge or are partially outside the visible frame")
        }
        val status = when {
            obscuredRatio >= 0.08 -> ChartStatus.PARTIALLY_OBSCURED
            quality < 0.35 -> ChartStatus.LOW_QUALITY
            else -> ChartStatus.DETECTED
        }
        return ChartDetectionResult(
            status, bounds, bounds.width, bounds.height, frame.capturedAt,
            quality, visible, obscuredRatio >= 0.08, reasons
        )
    }

    private fun visualEvidence(frame: PixelFrame, rect: PixelRect): Double {
        val stride = maxOf(1, minOf(rect.width, rect.height) / 80)
        val coloredColumns = BooleanArray((rect.width + stride - 1) / stride)
        val activeRows = BooleanArray((rect.height + stride - 1) / stride)
        var saturated = 0
        var sampled = 0
        for (y in rect.top until rect.bottom step stride) {
            for (x in rect.left until rect.right step stride) {
                val color = frame.pixel(x, y)
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                val max = maxOf(red, green, blue)
                val min = minOf(red, green, blue)
                val chromatic = max - min > 48 && max > 95
                sampled++
                if (chromatic) {
                    saturated++
                    coloredColumns[(x - rect.left) / stride] = true
                    activeRows[(y - rect.top) / stride] = true
                }
            }
        }
        val columnCoverage = coloredColumns.count { it }.toDouble() / coloredColumns.size.coerceAtLeast(1)
        val rowCoverage = activeRows.count { it }.toDouble() / activeRows.size.coerceAtLeast(1)
        val chromaRatio = saturated.toDouble() / sampled.coerceAtLeast(1)
        if (chromaRatio < 0.002 || columnCoverage < 0.03 || rowCoverage < 0.05) return 0.0
        val spread = (columnCoverage * 0.45 + rowCoverage * 0.25).coerceIn(0.0, 1.0)
        val plausibleChroma = (1.0 - abs(chromaRatio - 0.045) / 0.15).coerceIn(0.0, 1.0)
        return spread * 0.7 + plausibleChroma * 0.3
    }

    private fun intersectionRatio(a: PixelRect, b: PixelRect): Double {
        val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return width.toDouble() * height / a.area.coerceAtLeast(1)
    }
}
