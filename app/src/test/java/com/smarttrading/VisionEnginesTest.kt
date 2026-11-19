package com.smarttrading

import com.smarttrading.ai.DecisionEngine
import com.smarttrading.domain.AnalysisSnapshot
import com.smarttrading.domain.SignalDirection
import com.smarttrading.vision.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VisionEnginesTest {
    private val now = Instant.now()

    @Test fun chartDetectorFindsPlotAndRejectsUniformScreen() {
        val plotted = chartFrame()
        val detected = ChartDetectionEngine().detect(plotted)
        assertEquals(ChartStatus.DETECTED, detected.status)
        assertNotNull(detected.bounds)
        assertTrue(detected.quality > 0.35)

        val blank = PixelFrame(320, 240, IntArray(320 * 240) { 0x181818 }, now)
        val missing = ChartDetectionEngine().detect(ProcessedFrame(blank, 0.9, emptyList(), now))
        assertEquals(ChartStatus.NO_CHART, missing.status)
        assertTrue(missing.reasons.isNotEmpty())
    }

    @Test fun chartAndCandlesRemainDetectableAtHigherResolutionAndUnclearFramesWait() {
        val scaled = chartFrame(width = 640, height = 480)
        val chart = ChartDetectionEngine().detect(scaled)
        val candles = chart.bounds?.let { CandleRecognitionEngine().recognize(scaled.frame, it) }

        assertEquals(ChartStatus.DETECTED, chart.status)
        assertEquals(9, candles?.candles?.size)
        assertEquals(ChartStatus.LOW_QUALITY, ChartDetectionEngine().detect(scaled.copy(imageQuality = .1)).status)
    }

    @Test fun chartDetectorReportsOverlayIntersection() {
        val processed = chartFrame()
        val chart = processed.detectedRegions.single()
        val overlay = PixelRect(chart.left + chart.width / 4, chart.top + chart.height / 4,
            chart.left + chart.width * 3 / 4, chart.top + chart.height * 3 / 4)

        val result = ChartDetectionEngine().detect(processed, listOf(overlay))

        assertTrue(result.obscured)
        assertEquals(ChartStatus.PARTIALLY_OBSCURED, result.status)
    }

    @Test fun candleRecognizerExtractsGreenAndRedBodiesAndShadows() {
        val processed = chartFrame()
        val chart = processed.detectedRegions.single()

        val result = CandleRecognitionEngine().recognize(processed.frame, chart)

        assertEquals(9, result.candles.size)
        assertTrue(result.candles.any { it.direction == CandleDirection.BULLISH && it.color == CandleColor.GREEN })
        assertTrue(result.candles.any { it.direction == CandleDirection.BEARISH && it.color == CandleColor.RED })
        assertTrue(result.candles.all { chart.contains(it.x, it.y) && it.upperShadow > 0 && it.lowerShadow > 0 })
    }

    @Test fun candleRecognizerSupportsConfiguredPaletteAndRejectsUnresolvedLines() {
        val frame = chartFrame(colorCycle = listOf(0x3980e8, 0xe7d448))
        val chart = frame.detectedRegions.single()
        val calibrated = CandleCalibration(bullishRgb = 0x3980e8, bearishRgb = 0xe7d448)

        val result = CandleRecognitionEngine().recognize(frame.frame, chart, calibrated)
        val linesOnly = CandleRecognitionEngine().recognize(
            PixelFrame(100, 100, IntArray(10_000) { 0x101010 }.apply {
                for (y in 20..80) this[y * 100 + 50] = 0x20dd30
            }, now),
            PixelRect(0, 0, 100, 100)
        )

        assertEquals(9, result.candles.size)
        assertTrue(result.candles.all { it.direction != CandleDirection.UNKNOWN })
        assertTrue(linesOnly.candles.isEmpty())
    }

    @Test fun candlePatternsRequireGeometryAndIncludeReversalExamples() {
        val doji = candle(0, top = 40, bottom = 60, bodyTop = 49, bodyBottom = 50, upper = 9, lower = 10)
        val hammer = candle(1, top = 30, bottom = 65, bodyTop = 30, bodyBottom = 39, upper = 0, lower = 26)
        val previous = candle(2, top = 45, bottom = 70, bodyTop = 48, bodyBottom = 65, direction = CandleDirection.BEARISH)
        val engulfing = candle(3, top = 40, bottom = 75, bodyTop = 42, bodyBottom = 72, direction = CandleDirection.BULLISH)
        val results = CandlePatternRecognition().recognize(
            listOf(doji, hammer, previous, engulfing),
            TrendDirection.DOWNTREND
        )

        assertTrue(results.any { it.name == "Doji" })
        assertTrue(results.any { it.name == "Hammer" })
        assertTrue(results.any { it.name == "Bullish Engulfing" })
        assertTrue(results.all { it.quality in 0.0..1.0 && it.candleCount in 1..3 })
    }

    @Test fun candlePatternsDistinguishTrendContextAndMultiCandleStructures() {
        val longUpperWick = candle(0, top = 40, bottom = 52, bodyTop = 50, bodyBottom = 51, upper = 10, lower = 1)
        val longLowerWick = candle(1, top = 30, bottom = 65, bodyTop = 30, bodyBottom = 39, upper = 0, lower = 26)
        val bullishPrior = candle(2, top = 42, bottom = 72, bodyTop = 42, bodyBottom = 72)
        val bearishEngulfing = candle(3, top = 40, bottom = 75, bodyTop = 40, bodyBottom = 75, direction = CandleDirection.BEARISH)
        val bearishPrior = candle(4, top = 42, bottom = 72, bodyTop = 42, bodyBottom = 72, direction = CandleDirection.BEARISH)
        val bullishHarami = candle(5, top = 45, bottom = 68, bodyTop = 50, bodyBottom = 60)
        val morningStar = listOf(
            candle(6, 30, 80, 35, 75, direction = CandleDirection.BEARISH),
            candle(7, 48, 62, 53, 55),
            candle(8, 25, 70, 35, 40)
        )
        val eveningStar = listOf(
            candle(9, 30, 80, 35, 45),
            candle(10, 48, 62, 53, 55),
            candle(11, 25, 80, 65, 75, direction = CandleDirection.BEARISH)
        )
        val soldiers = listOf(candle(12, 30, 60, 32, 42), candle(13, 25, 55, 27, 37), candle(14, 20, 50, 22, 32))
        val crows = listOf(
            candle(15, 30, 60, 40, 50, direction = CandleDirection.BEARISH),
            candle(16, 35, 65, 45, 55, direction = CandleDirection.BEARISH),
            candle(17, 40, 70, 50, 60, direction = CandleDirection.BEARISH)
        )
        val patterns = CandlePatternRecognition()
        val down = patterns.recognize(
            listOf(longUpperWick, bullishPrior, bearishEngulfing, bearishPrior, bullishHarami) + morningStar + soldiers,
            TrendDirection.DOWNTREND
        ).map { it.name }
        val up = patterns.recognize(listOf(longUpperWick, longLowerWick) + eveningStar + crows, TrendDirection.UPTREND)
            .map { it.name }

        assertTrue("Inverted Hammer" in down)
        assertTrue("Shooting Star" in up)
        assertTrue("Hanging Man" in up)
        assertTrue("Bearish Engulfing" in down)
        assertTrue("Bullish Harami" in down)
        assertTrue("Morning Star" in down)
        assertTrue("Evening Star" in up)
        assertTrue("Three White Soldiers" in down)
        assertTrue("Three Black Crows" in up)
        assertTrue("Pin Bar" in up)
    }

    @Test fun trendEngineUsesSwingStructureRatherThanLastCandleColor() {
        val highs = listOf(100, 80, 95, 70, 85, 60, 75, 50, 65)
        val lows = listOf(110, 130, 120, 125, 110, 115, 100, 105, 90)
        val candles = highs.indices.map { index ->
            candle(index, highs[index], lows[index], direction = if (index == 8) CandleDirection.BEARISH else CandleDirection.BULLISH)
        }

        val result = TrendAnalysisEngine().analyze(candles)

        assertEquals(TrendDirection.STRONG_UPTREND, result.direction)
        assertTrue(result.swingHighs.isNotEmpty())
        assertTrue(result.swingLows.isNotEmpty())
    }

    @Test fun supportResistanceMomentumVolatilityAndConditionsUseCandleHistory() {
        val bullish = (0 until 12).map { index ->
            val top = if (index % 2 == 0) 80 - index else 90 - index
            val bottom = if (index % 2 == 0) 120 - index else 125 - index
            candle(index, top, bottom, bodyTop = top + 2, bodyBottom = bottom - 2)
        }
        val momentum = MomentumEngine().analyze(bullish)
        val volatilityCandles = bullish + (12 until 17).map { index ->
            candle(index, 20, 100, bodyTop = 25, bodyBottom = 95)
        }
        val volatility = VolatilityEngine().analyze(volatilityCandles, 100)
        val trend = TrendAnalysis(TrendDirection.UPTREND, .8, listOf(1, 3), listOf(2, 4), false, emptyList())
        val levels = SupportResistanceEngine().analyze(bullish, 160)
        val condition = MarketConditionEngine().analyze(trend, levels, momentum, volatility, emptyList())

        assertEquals(MomentumState.BULLISH, momentum.state)
        assertTrue(volatility.abruptChange)
        assertTrue(volatility.relativeState in setOf(RelativeVolatility.HIGH, RelativeVolatility.VERY_HIGH))
        assertTrue(levels.quality in 0.0..1.0)
        assertTrue(condition.state in setOf(MarketState.HIGH_VOLATILITY, MarketState.TRENDING, MarketState.RANGING))
    }

    @Test fun supportResistanceClustersRetestsAndLabelsBreakouts() {
        val levels = listOf(90, 100, 90, 101, 95, 98).mapIndexed { index, bottom ->
            candle(index, top = 50, bottom = bottom)
        }
        val confirmedBreak = candle(6, top = 120, bottom = 140, bodyTop = 125, bodyBottom = 137,
            direction = CandleDirection.BEARISH)
        val falseBreak = candle(6, top = 90, bottom = 140, bodyTop = 92, bodyBottom = 100,
            direction = CandleDirection.BULLISH)
        val engine = SupportResistanceEngine()
        val broken = engine.analyze(levels + confirmedBreak, chartHeight = 200).supports
        val failed = engine.analyze(levels + falseBreak, chartHeight = 200).supports

        assertTrue(broken.any { it.touches >= 2 && it.brokenOut && !it.falseBreakout })
        assertTrue(failed.any { it.touches >= 2 && it.brokenOut && it.falseBreakout })
    }

    @Test fun noChartAndStaleVisualEvidenceAlwaysYieldWait() {
        val engine = DecisionEngine()
        val fresh = AnalysisSnapshot("EUR/USD", "visual", now, 0, .9, emptyList(), chartDetected = false)
        val noCandles = AnalysisSnapshot("EUR/USD", "visual", now, 0, .9, emptyList(), recognizedCandleCount = 0)
        val stale = AnalysisSnapshot("EUR/USD", "visual", now.minusSeconds(5), 5_000, .9, emptyList())

        assertEquals(SignalDirection.WAIT, engine.decide(fresh, .65).direction)
        assertEquals(SignalDirection.WAIT, engine.decide(noCandles, .65).direction)
        assertEquals(SignalDirection.WAIT, engine.decide(stale, .65).direction)
    }

    @Test fun integratedMarketAnalysisUsesDetectedFrameAndReturnsEngineEvidence() {
        val result = MarketAnalysisEngine().analyze(chartFrame())
        val blank = PixelFrame(320, 240, IntArray(320 * 240) { 0x181818 }, now)
        val missing = MarketAnalysisEngine().analyze(ProcessedFrame(blank, .9, emptyList(), now))

        assertEquals(ChartStatus.DETECTED, result.chart.status)
        assertEquals(9, result.candles.candles.size)
        assertEquals(9, result.engines.size)
        assertEquals(now, result.capturedAt)
        assertEquals(ChartStatus.NO_CHART, missing.chart.status)
        assertTrue(missing.candles.candles.isEmpty())
    }

    private fun chartFrame(
        colorCycle: List<Int> = listOf(0x25d075, 0xe54d5d),
        width: Int = 320,
        height: Int = 240
    ): ProcessedFrame {
        val scale = width / 320
        val pixels = IntArray(width * height) { 0x121820 }
        val bounds = PixelRect(10 * scale, 10 * scale, 310 * scale, 230 * scale)
        repeat(9) { index ->
            val x = (30 + index * 27) * scale
            val candleTop = (65 + index % 3 * 3) * scale
            val bodyTop = candleTop + 10 * scale
            val bodyBottom = bodyTop + 12 * scale
            val candleBottom = bodyBottom + 10 * scale
            val color = colorCycle[index % colorCycle.size]
            for (y in candleTop until bodyTop) pixels[y * width + x + scale] = color
            for (y in bodyTop..bodyBottom) for (dx in 0 until 4 * scale) pixels[y * width + x + dx] = color
            for (y in bodyBottom + 1..candleBottom) pixels[y * width + x + scale] = color
        }
        val frame = PixelFrame(width, height, pixels, now)
        return ProcessedFrame(frame, .95, listOf(bounds), now)
    }

    private fun candle(
        x: Int,
        top: Int = 40,
        bottom: Int = 80,
        bodyTop: Int = top + 5,
        bodyBottom: Int = bottom - 5,
        upper: Int = bodyTop - top,
        lower: Int = bottom - bodyBottom,
        direction: CandleDirection = CandleDirection.BULLISH
    ) = Candle(
        x = x,
        y = (top + bottom) / 2,
        top = top,
        bottom = bottom,
        bodyTop = bodyTop,
        bodyBottom = bodyBottom,
        upperShadow = upper,
        lowerShadow = lower,
        color = if (direction == CandleDirection.BULLISH) CandleColor.GREEN else CandleColor.RED,
        direction = direction,
        relativeHeight = (bottom - top).toDouble() / 200,
        relativeWidth = .02,
        detectedAt = now
    )
}
