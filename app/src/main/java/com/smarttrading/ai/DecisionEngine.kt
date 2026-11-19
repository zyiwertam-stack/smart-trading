package com.smarttrading.ai

import com.smarttrading.domain.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

class DecisionEngine(private val clock: Clock = Clock.systemUTC()) {
    fun decide(snapshot: AnalysisSnapshot, minimumConfidence: Double): Decision {
        val now = Instant.now(clock)
        val frameAge = Duration.between(snapshot.capturedAt, now).toMillis()
        val stale = snapshot.frameAgeMs !in 0..MAX_FRAME_AGE_MS ||
            frameAge !in 0..MAX_FRAME_AGE_MS
        if (stale) {
            return wait(snapshot, "Frame is stale or has an invalid timestamp", now)
        }

        if (!snapshot.chartDetected) {
            return wait(snapshot, "NO_CHART: a reliable chart region was not detected", now)
        }
        if (snapshot.recognizedCandleCount < MIN_RECOGNIZED_CANDLES) {
            return wait(snapshot, "Insufficient visually verified candles", now)
        }
        if (!snapshot.imageQuality.isFinite() || snapshot.imageQuality !in 0.55..1.0 ||
            !minimumConfidence.isFinite() || minimumConfidence !in 0.0..1.0
        ) {
            return wait(snapshot, "Image quality or confidence threshold is invalid or insufficient", now)
        }

        val validEngines = snapshot.engines.filter { engine ->
            engine.confidence.isFinite() &&
                engine.confidence in 0.0..1.0 &&
                engine.recognitionQuality.isFinite() &&
                engine.recognitionQuality in 0.0..1.0 &&
                engine.error == null &&
                engine.value.uppercase() in VALID_DIRECTIONS &&
                Duration.between(engine.timestamp, now).toMillis() in 0..MAX_FRAME_AGE_MS &&
                Duration.between(snapshot.capturedAt, engine.timestamp).toMillis() in -500..MAX_FRAME_AGE_MS
        }
        if (validEngines.size != snapshot.engines.size) {
            return wait(snapshot, "An analytical result is invalid or stale", now)
        }

        val directionalEngines = validEngines.filter {
            it.value.equals(SignalDirection.CALL.name, true) ||
                it.value.equals(SignalDirection.PUT.name, true)
        }
        if (directionalEngines.size < MIN_DIRECTIONAL_ENGINES) {
            return wait(snapshot, "Insufficient directional engine evidence", now)
        }

        val calls = directionalEngines.filter { it.value.equals("CALL", true) }.sumOf { it.confidence }
        val puts = directionalEngines.filter { it.value.equals("PUT", true) }.sumOf { it.confidence }
        val total = calls + puts
        if (total == 0.0) return wait(snapshot, "Analytical engines did not produce a direction", now)
        val direction = if (calls > puts) SignalDirection.CALL else SignalDirection.PUT
        val confidence = maxOf(calls, puts) / total
        if (confidence < minimumConfidence || abs(calls - puts) / total < 0.15) {
            return wait(snapshot, "Engine disagreement is above the safe threshold", now, confidence, validEngines)
        }
        return Decision(direction, snapshot.pair, 60, confidence, 1 - confidence,
            "Consensus of ${directionalEngines.count { it.value.equals(direction.name, true) }} analytical engines",
            validEngines, now)
    }

    private fun wait(
        snapshot: AnalysisSnapshot,
        reason: String,
        now: Instant,
        confidence: Double = 0.0,
        engines: List<EngineResult> = snapshot.engines
    ) = Decision(
        SignalDirection.WAIT,
        snapshot.pair,
        null,
        confidence,
        1 - confidence,
        reason,
        engines,
        now
    )

    private companion object {
        const val MAX_FRAME_AGE_MS = 2_000L
        const val MIN_RECOGNIZED_CANDLES = 5
        const val MIN_DIRECTIONAL_ENGINES = 3
        val VALID_DIRECTIONS = setOf(
            SignalDirection.CALL.name,
            SignalDirection.PUT.name,
            SignalDirection.WAIT.name
        )
    }
}
