package com.smarttrading.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TrendDirection { STRONG_UPTREND, UPTREND, SIDEWAYS, DOWNTREND, STRONG_DOWNTREND, UNKNOWN }
enum class MomentumState { BULLISH, BEARISH, NEUTRAL, WEAKENING, UNKNOWN }
enum class RelativeVolatility { VERY_LOW, LOW, NORMAL, HIGH, VERY_HIGH, UNKNOWN }
enum class MarketState { TRENDING, RANGING, BREAKOUT, POSSIBLE_REVERSAL, HIGH_VOLATILITY, LOW_VOLATILITY, UNKNOWN }
enum class PatternDirection { BULLISH, BEARISH, NEUTRAL }

data class DetectedPattern(
    val name: String,
    val direction: PatternDirection,
    val candleCount: Int,
    val quality: Double,
    val trendContext: TrendDirection,
    val detectedAt: java.time.Instant,
    val reason: String
)

data class TrendAnalysis(
    val direction: TrendDirection,
    val confidence: Double,
    val swingHighs: List<Int>,
    val swingLows: List<Int>,
    val structureChanged: Boolean,
    val reasons: List<String>
)

data class PriceZone(
    val lowY: Int,
    val highY: Int,
    val touches: Int,
    val quality: Double,
    val distanceFromCurrentPixels: Int?,
    val brokenOut: Boolean,
    val falseBreakout: Boolean
)

data class SupportResistanceAnalysis(
    val supports: List<PriceZone>,
    val resistances: List<PriceZone>,
    val quality: Double,
    val reasons: List<String>
)

data class MomentumAnalysis(val state: MomentumState, val confidence: Double, val reasons: List<String>)
data class VolatilityAnalysis(
    val relativeState: RelativeVolatility,
    val averageRangeFraction: Double?,
    val abruptChange: Boolean,
    val reasons: List<String>
)
data class MarketConditionAnalysis(
    val state: MarketState,
    val confidence: Double,
    val reasons: List<String>
)

data class EngineAnalysis(
    val name: String,
    val analyzedAt: java.time.Instant,
    val inputs: Map<String, String>,
    val result: String,
    val confidence: Double,
    val recognitionQuality: Double,
    val reasons: List<String>,
    val error: String? = null
)

data class MarketAnalysis(
    val chart: ChartDetectionResult,
    val candles: CandleRecognitionResult,
    val patterns: List<DetectedPattern>,
    val trend: TrendAnalysis,
    val supportResistance: SupportResistanceAnalysis,
    val momentum: MomentumAnalysis,
    val volatility: VolatilityAnalysis,
    val condition: MarketConditionAnalysis,
    val engines: List<EngineAnalysis>,
    val capturedAt: java.time.Instant
)

class CandlePatternRecognition {
    fun recognize(candles: List<Candle>, trend: TrendDirection): List<DetectedPattern> {
        if (candles.size < 1) return emptyList()
        val result = mutableListOf<DetectedPattern>()
        candles.forEachIndexed { index, candle ->
            val body = candle.bodyHeight.toDouble()
            val range = candle.range.toDouble().coerceAtLeast(1.0)
            val upper = candle.upperShadow.toDouble()
            val lower = candle.lowerShadow.toDouble()
            val doji = body / range <= 0.12
            if (doji) result += pattern("Doji", PatternDirection.NEUTRAL, 1, 0.74, trend, candle,
                "Body is at most 12% of the visible candle range")
            if (!doji && lower >= body * 2.0 && upper <= body * 0.65) {
                val hanging = trend == TrendDirection.UPTREND || trend == TrendDirection.STRONG_UPTREND
                result += pattern(
                    if (hanging) "Hanging Man" else "Hammer",
                    if (hanging) PatternDirection.BEARISH else PatternDirection.BULLISH,
                    1, shapeConfidence(lower / body, upper / body), trend, candle,
                    "Lower shadow is at least twice the body and upper shadow is small"
                )
            }
            if (!doji && upper >= body * 2.0 && lower <= body * 0.65) {
                val star = trend == TrendDirection.UPTREND || trend == TrendDirection.STRONG_UPTREND
                result += pattern(
                    if (star) "Shooting Star" else "Inverted Hammer",
                    if (star) PatternDirection.BEARISH else PatternDirection.BULLISH,
                    1, shapeConfidence(upper / body, lower / body), trend, candle,
                    "Upper shadow is at least twice the body and lower shadow is small"
                )
            }
            if ((lower >= body * 2.2 && candle.direction == CandleDirection.BULLISH) ||
                (upper >= body * 2.2 && candle.direction == CandleDirection.BEARISH)
            ) {
                result += pattern(
                    "Pin Bar",
                    if (candle.direction == CandleDirection.BULLISH) PatternDirection.BULLISH else PatternDirection.BEARISH,
                    1, 0.68, trend, candle, "Long rejection wick is aligned with the candle body direction"
                )
            }
            if (index >= 1) {
                val previous = candles[index - 1]
                if (previous.direction != candle.direction &&
                    bodyBounds(candle).first <= bodyBounds(previous).first &&
                    bodyBounds(candle).second >= bodyBounds(previous).second &&
                    body >= previous.bodyHeight * 1.08
                ) {
                    val bullish = candle.direction == CandleDirection.BULLISH
                    result += pattern(
                        if (bullish) "Bullish Engulfing" else "Bearish Engulfing",
                        if (bullish) PatternDirection.BULLISH else PatternDirection.BEARISH,
                        2, 0.82, trend, candle, "Latest real body contains the prior opposite body"
                    )
                }
                if (previous.direction != candle.direction &&
                    bodyBounds(candle).first > bodyBounds(previous).first &&
                    bodyBounds(candle).second < bodyBounds(previous).second &&
                    body <= previous.bodyHeight * 0.72
                ) {
                    val bullish = candle.direction == CandleDirection.BULLISH
                    result += pattern(
                        if (bullish) "Bullish Harami" else "Bearish Harami",
                        if (bullish) PatternDirection.BULLISH else PatternDirection.BEARISH,
                        2, 0.73, trend, candle, "Latest real body is contained within the prior body"
                    )
                }
            }
        }
        for (index in 2 until candles.size) {
            val first = candles[index - 2]
            val middle = candles[index - 1]
            val last = candles[index]
            val avgBody = (first.bodyHeight + last.bodyHeight) / 2.0
            val midpoint = (bodyCenter(first) + bodyCenter(last)) / 2.0
            if (first.direction == CandleDirection.BEARISH && last.direction == CandleDirection.BULLISH &&
                first.bodyHeight >= middle.bodyHeight * 1.5 && last.bodyHeight >= middle.bodyHeight * 1.5 &&
                bodyCenter(last) < midpoint
            ) {
                result += pattern("Morning Star", PatternDirection.BULLISH, 3, 0.75, trend, last,
                    "Large bearish candle, compact pause, and bullish body recovering the first body's midpoint")
            }
            if (first.direction == CandleDirection.BULLISH && last.direction == CandleDirection.BEARISH &&
                first.bodyHeight >= middle.bodyHeight * 1.5 && last.bodyHeight >= middle.bodyHeight * 1.5 &&
                bodyCenter(last) > midpoint
            ) {
                result += pattern("Evening Star", PatternDirection.BEARISH, 3, 0.75, trend, last,
                    "Large bullish candle, compact pause, and bearish body crossing the first body's midpoint")
            }
            val threeUp = listOf(first, middle, last).all { it.direction == CandleDirection.BULLISH } &&
                bodyCenter(middle) < bodyCenter(first) && bodyCenter(last) < bodyCenter(middle) &&
                listOf(first, middle, last).all { it.bodyHeight >= avgBody * 0.45 }
            val threeDown = listOf(first, middle, last).all { it.direction == CandleDirection.BEARISH } &&
                bodyCenter(middle) > bodyCenter(first) && bodyCenter(last) > bodyCenter(middle) &&
                listOf(first, middle, last).all { it.bodyHeight >= avgBody * 0.45 }
            if (threeUp) result += pattern("Three White Soldiers", PatternDirection.BULLISH, 3, 0.72, trend, last,
                "Three consecutive bullish candles form progressively higher close positions")
            if (threeDown) result += pattern("Three Black Crows", PatternDirection.BEARISH, 3, 0.72, trend, last,
                "Three consecutive bearish candles form progressively lower close positions")
        }
        return result.distinctBy { it.name to it.detectedAt }
    }

    private fun bodyBounds(candle: Candle) = min(candle.bodyTop, candle.bodyBottom) to max(candle.bodyTop, candle.bodyBottom)
    private fun bodyCenter(candle: Candle) = (candle.bodyTop + candle.bodyBottom) / 2
    private fun shapeConfidence(longRatio: Double, shortRatio: Double) = (0.65 + min(longRatio, 5.0) * 0.04 - shortRatio * 0.04).coerceIn(0.0, 0.9)
    private fun pattern(
        name: String, direction: PatternDirection, count: Int, quality: Double,
        trend: TrendDirection, candle: Candle, reason: String
    ) = DetectedPattern(name, direction, count, quality, trend, candle.detectedAt, reason)
}

class TrendAnalysisEngine {
    fun analyze(candles: List<Candle>): TrendAnalysis {
        if (candles.size < 5) return TrendAnalysis(TrendDirection.UNKNOWN, 0.0, emptyList(), emptyList(), false,
            listOf("At least five geometrically validated candles are required"))
        val highs = (1 until candles.lastIndex).filter {
            candles[it].top < candles[it - 1].top && candles[it].top <= candles[it + 1].top
        }
        val lows = (1 until candles.lastIndex).filter {
            candles[it].bottom > candles[it - 1].bottom && candles[it].bottom >= candles[it + 1].bottom
        }
        val risingHighs = highs.zipWithNext().count { (a, b) -> candles[b].top < candles[a].top }
        val risingLows = lows.zipWithNext().count { (a, b) -> candles[b].bottom < candles[a].bottom }
        val fallingHighs = highs.zipWithNext().count { (a, b) -> candles[b].top > candles[a].top }
        val fallingLows = lows.zipWithNext().count { (a, b) -> candles[b].bottom > candles[a].bottom }
        val up = risingHighs + risingLows
        val down = fallingHighs + fallingLows
        val structureChanged = candles.size >= 7 &&
            ((up > down && candles.last().bottom > candles[candles.lastIndex - 1].bottom) ||
                (down > up && candles.last().top < candles[candles.lastIndex - 1].top))
        val ranges = candles.takeLast(8)
        val normalizedRange = (ranges.maxOf { it.bottom } - ranges.minOf { it.top }).toDouble() /
            ranges.sumOf { it.range }.coerceAtLeast(1)
        val direction = when {
            up >= 4 && up >= down * 2 -> TrendDirection.STRONG_UPTREND
            up > down && up >= 2 -> TrendDirection.UPTREND
            down >= 4 && down >= up * 2 -> TrendDirection.STRONG_DOWNTREND
            down > up && down >= 2 -> TrendDirection.DOWNTREND
            normalizedRange < 0.42 -> TrendDirection.SIDEWAYS
            else -> TrendDirection.UNKNOWN
        }
        val confidence = when (direction) {
            TrendDirection.UNKNOWN -> 0.0
            TrendDirection.SIDEWAYS -> (1.0 - normalizedRange).coerceIn(0.0, 0.75)
            else -> max(up, down).toDouble() / (up + down).coerceAtLeast(1)
        }
        return TrendAnalysis(direction, confidence, highs, lows, structureChanged, listOf(
            "Compared ${highs.size} local swing highs and ${lows.size} local swing lows",
            "Structure score: rising=$up, falling=$down; compactness=${"%.2f".format(normalizedRange)}"
        ))
    }
}

class SupportResistanceEngine {
    fun analyze(candles: List<Candle>, chartHeight: Int): SupportResistanceAnalysis {
        if (candles.size < 4 || chartHeight <= 0) {
            return SupportResistanceAnalysis(emptyList(), emptyList(), 0.0, listOf("Insufficient candles or chart dimensions"))
        }
        val tolerance = max(2, (chartHeight * 0.018).toInt())
        val supportRaw = candles.mapIndexedNotNull { index, candle ->
            if (index in 1 until candles.lastIndex &&
                candle.bottom >= candles[index - 1].bottom && candle.bottom >= candles[index + 1].bottom
            ) candle.bottom else null
        }
        val resistanceRaw = candles.mapIndexedNotNull { index, candle ->
            if (index in 1 until candles.lastIndex &&
                candle.top <= candles[index - 1].top && candle.top <= candles[index + 1].top
            ) candle.top else null
        }
        val current = closeY(candles.last())
        val supports = buildZones(supportRaw, candles, tolerance, current, isSupport = true)
        val resistances = buildZones(resistanceRaw, candles, tolerance, current, isSupport = false)
        return SupportResistanceAnalysis(
            supports.filter { it.touches >= 2 }.takeLast(6),
            resistances.filter { it.touches >= 2 }.takeLast(6),
            ((supports.size + resistances.size) / 8.0).coerceAtMost(1.0),
            listOf("Zones cluster repeated local extrema with a tolerance of $tolerance screen pixels")
        )
    }

    private fun buildZones(
        levels: List<Int>, candles: List<Candle>, tolerance: Int, current: Int, isSupport: Boolean
    ): List<PriceZone> {
        val clusters = mutableListOf<MutableList<Int>>()
        levels.sorted().forEach { level ->
            val cluster = clusters.firstOrNull { abs(level - it.average()).toInt() <= tolerance }
            if (cluster == null) clusters += mutableListOf(level) else cluster += level
        }
        return clusters.map { points ->
            val low = points.min()
            val high = points.max()
            val lastExcursion = candles.lastOrNull { candle ->
                if (isSupport) candle.bottom > high + tolerance else candle.top < low - tolerance
            }
            val broken = lastExcursion != null && candles.lastIndex - candles.indexOf(lastExcursion) <= 1
            val falseBreak = broken && (if (isSupport) current < low else current > high)
            PriceZone(
                low, high, points.size, (points.size / 4.0).coerceAtMost(1.0),
                abs(current - (low + high) / 2), broken, falseBreak
            )
        }
    }

    private fun closeY(candle: Candle) =
        if (candle.direction == CandleDirection.BULLISH) candle.bodyTop else candle.bodyBottom
}

class MomentumEngine {
    fun analyze(candles: List<Candle>): MomentumAnalysis {
        if (candles.size < 4) return MomentumAnalysis(MomentumState.UNKNOWN, 0.0, listOf("At least four candles are required"))
        val recent = candles.takeLast(6)
        val signed = recent.map { candle ->
            (if (candle.direction == CandleDirection.BULLISH) 1 else -1) * candle.bodyHeight.toDouble()
        }
        val directional = signed.sum()
        val aligned = recent.zipWithNext().count { it.first.direction == it.second.direction }
        val earlierAverage = candles.dropLast(recent.size).takeLast(5).map { it.bodyHeight }.average()
        val recentAverage = recent.map { it.bodyHeight }.average()
        val acceleration = if (earlierAverage.isFinite() && earlierAverage > 0) recentAverage / earlierAverage else 1.0
        val state = when {
            recentAverage < 1 -> MomentumState.UNKNOWN
            aligned >= 4 && directional > 0 && acceleration >= 0.85 -> MomentumState.BULLISH
            aligned >= 4 && directional < 0 && acceleration >= 0.85 -> MomentumState.BEARISH
            acceleration < 0.65 -> MomentumState.WEAKENING
            else -> MomentumState.NEUTRAL
        }
        return MomentumAnalysis(state, (aligned / 5.0).coerceIn(0.0, 1.0), listOf(
            "Recent directional-body sum=${directional.toInt()} pixels across ${recent.size} candles",
            "Average body acceleration ratio=${"%.2f".format(acceleration)}"
        ))
    }

}

class VolatilityEngine {
    fun analyze(candles: List<Candle>, chartHeight: Int): VolatilityAnalysis {
        if (candles.size < 4 || chartHeight <= 0) {
            return VolatilityAnalysis(RelativeVolatility.UNKNOWN, null, false, listOf("Insufficient candle ranges for relative volatility"))
        }
        val ranges = candles.map { it.range.toDouble() / chartHeight }
        val average = ranges.takeLast(10).average()
        val currentWindow = ranges.takeLast(5).average()
        val previous = ranges.dropLast(min(5, ranges.size)).takeLast(5).average()
        val abrupt = previous.isFinite() && previous > 0 && currentWindow / previous >= 1.8
        val state = when {
            average < 0.025 -> RelativeVolatility.VERY_LOW
            average < 0.05 -> RelativeVolatility.LOW
            average < 0.12 -> RelativeVolatility.NORMAL
            average < 0.22 -> RelativeVolatility.HIGH
            else -> RelativeVolatility.VERY_HIGH
        }
        return VolatilityAnalysis(state, average, abrupt, listOf(
            "Relative visual range averages ${(average * 100).toInt()}% of chart height; not an absolute market volatility value",
            if (abrupt) "Recent visual range expanded abruptly" else "No abrupt recent range expansion detected"
        ))
    }
}

class MarketConditionEngine {
    fun analyze(
        trend: TrendAnalysis,
        supportResistance: SupportResistanceAnalysis,
        momentum: MomentumAnalysis,
        volatility: VolatilityAnalysis,
        patterns: List<DetectedPattern>
    ): MarketConditionAnalysis {
        val reversal = patterns.any { it.quality >= 0.72 && it.direction != PatternDirection.NEUTRAL } &&
            (momentum.state == MomentumState.WEAKENING ||
                trend.structureChanged && momentum.state != MomentumState.UNKNOWN)
        val state = when {
            volatility.relativeState in setOf(RelativeVolatility.HIGH, RelativeVolatility.VERY_HIGH) &&
                volatility.abruptChange -> MarketState.HIGH_VOLATILITY
            reversal -> MarketState.POSSIBLE_REVERSAL
            supportResistance.supports.any { it.brokenOut && !it.falseBreakout } ||
                supportResistance.resistances.any { it.brokenOut && !it.falseBreakout } -> MarketState.BREAKOUT
            trend.direction in setOf(TrendDirection.UPTREND, TrendDirection.STRONG_UPTREND,
                TrendDirection.DOWNTREND, TrendDirection.STRONG_DOWNTREND) &&
                momentum.state in setOf(MomentumState.BULLISH, MomentumState.BEARISH) -> MarketState.TRENDING
            trend.direction == TrendDirection.SIDEWAYS &&
                (supportResistance.supports.isNotEmpty() || supportResistance.resistances.isNotEmpty()) -> MarketState.RANGING
            volatility.relativeState == RelativeVolatility.VERY_LOW -> MarketState.LOW_VOLATILITY
            else -> MarketState.UNKNOWN
        }
        val evidence = listOf(trend.confidence, supportResistance.quality, momentum.confidence)
            .filter { it.isFinite() }
        return MarketConditionAnalysis(state, if (state == MarketState.UNKNOWN) 0.0 else evidence.average().coerceIn(0.0, 1.0),
            listOf("Combined trend, structure, momentum, volatility, and ${patterns.size} detected patterns"))
    }
}

class MarketAnalysisEngine(
    private val chartDetector: ChartDetectionEngine = ChartDetectionEngine(),
    private val candleRecognizer: CandleRecognitionEngine = CandleRecognitionEngine(),
    private val patternsEngine: CandlePatternRecognition = CandlePatternRecognition(),
    private val trendEngine: TrendAnalysisEngine = TrendAnalysisEngine(),
    private val levelsEngine: SupportResistanceEngine = SupportResistanceEngine(),
    private val momentumEngine: MomentumEngine = MomentumEngine(),
    private val volatilityEngine: VolatilityEngine = VolatilityEngine(),
    private val conditionEngine: MarketConditionEngine = MarketConditionEngine()
) {
    fun analyze(processed: ProcessedFrame, excludedRegions: List<PixelRect> = emptyList()): MarketAnalysis {
        val chart = chartDetector.detect(processed, excludedRegions)
        val candles = if (chart.status == ChartStatus.DETECTED && chart.bounds != null) {
            candleRecognizer.recognize(processed.frame, chart.bounds)
        } else {
            CandleRecognitionResult(emptyList(), 0.0, listOf("Chart detection did not provide a reliable visible chart region"))
        }
        val trend = trendEngine.analyze(candles.candles)
        val patterns = patternsEngine.recognize(candles.candles, trend.direction)
        val levels = levelsEngine.analyze(candles.candles, chart.height)
        val momentum = momentumEngine.analyze(candles.candles)
        val volatility = volatilityEngine.analyze(candles.candles, chart.height)
        val condition = conditionEngine.analyze(trend, levels, momentum, volatility, patterns)
        val now = processed.processingTimestamp
        val engines = listOf(
            EngineAnalysis("OpenCV / frame", now, mapOf("size" to "${processed.frame.width}x${processed.frame.height}"),
                "PROCESSED", processed.imageQuality, processed.imageQuality, listOf("Resize, denoise, CLAHE, color segmentation, and contour extraction completed")),
            EngineAnalysis("Chart detection", now, mapOf("candidateRegions" to processed.detectedRegions.size.toString()),
                chart.status.name, chart.quality, chart.quality, chart.reasons),
            EngineAnalysis("Candle recognition", now, mapOf("chartStatus" to chart.status.name),
                "${candles.candles.size} candles", candles.quality, candles.quality, candles.reasons),
            EngineAnalysis("Trend analysis", now, mapOf("candles" to candles.candles.size.toString()),
                trend.direction.name, trend.confidence, candles.quality, trend.reasons),
            EngineAnalysis("Candle patterns", now, mapOf("candles" to candles.candles.size.toString()),
                patterns.joinToString { it.name }.ifEmpty { "NONE" }, patterns.maxOfOrNull { it.quality } ?: 0.0,
                candles.quality, patterns.map { it.reason }.ifEmpty { listOf("No pattern met geometric confirmation criteria") }),
            EngineAnalysis("Support / resistance", now, mapOf("candles" to candles.candles.size.toString()),
                "${levels.supports.size} support zones, ${levels.resistances.size} resistance zones", levels.quality, candles.quality, levels.reasons),
            EngineAnalysis("Momentum", now, emptyMap(), momentum.state.name, momentum.confidence, candles.quality, momentum.reasons),
            EngineAnalysis("Relative volatility", now, emptyMap(), volatility.relativeState.name, candles.quality, candles.quality, volatility.reasons),
            EngineAnalysis("Market condition", now, emptyMap(), condition.state.name, condition.confidence, candles.quality, condition.reasons)
        )
        return MarketAnalysis(chart, candles, patterns, trend, levels, momentum, volatility, condition, engines, processed.frame.capturedAt)
    }
}
