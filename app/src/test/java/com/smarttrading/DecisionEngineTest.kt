package com.smarttrading

import com.smarttrading.ai.DecisionEngine
import com.smarttrading.domain.*
import com.smarttrading.risk.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DecisionEngineTest {
    private val now = Instant.now()

    @Test fun staleFramesAlwaysWait() {
        val snapshot = AnalysisSnapshot("EUR/USD", "1m", now, 3_000, .99,
            listOf(EngineResult("TREND", "CALL", .9, "")))
        assertEquals(SignalDirection.WAIT, DecisionEngine().decide(snapshot, .5).direction)
    }
    @Test fun conflictingEnginesWait() {
        val snapshot = AnalysisSnapshot("EUR/USD", "1m", now, 0, .9,
            listOf(EngineResult("A", "CALL", .8, ""), EngineResult("B", "PUT", .8, ""), EngineResult("C", "WAIT", .8, "")))
        assertEquals(SignalDirection.WAIT, DecisionEngine().decide(snapshot, .5).direction)
    }

    @Test fun subsecondButOverLimitFramesWait() {
        val capturedAt = Instant.parse("2026-01-01T00:00:00Z")
        val clock = Clock.fixed(capturedAt.plusMillis(2_500), ZoneOffset.UTC)
        val snapshot = AnalysisSnapshot(
            "EUR/USD",
            "1m",
            capturedAt,
            1_900,
            .9,
            listOf(
                EngineResult("A", "CALL", .9, "", capturedAt),
                EngineResult("B", "CALL", .8, "", capturedAt),
                EngineResult("C", "CALL", .7, "", capturedAt)
            )
        )

        assertEquals(SignalDirection.WAIT, DecisionEngine(clock).decide(snapshot, .5).direction)
    }

    @Test fun waitOnlyEnginesDoNotCountAsDirectionalEvidence() {
        val snapshot = AnalysisSnapshot(
            "EUR/USD",
            "1m",
            now,
            0,
            .9,
            listOf(
                EngineResult("A", "CALL", .9, ""),
                EngineResult("B", "WAIT", .9, ""),
                EngineResult("C", "WAIT", .9, "")
            )
        )

        assertEquals(SignalDirection.WAIT, DecisionEngine().decide(snapshot, .5).direction)
    }

    @Test fun invalidConfidenceNeverProducesDirection() {
        val snapshot = AnalysisSnapshot(
            "EUR/USD",
            "1m",
            now,
            0,
            .9,
            listOf(
                EngineResult("A", "CALL", .9, ""),
                EngineResult("B", "CALL", Double.NaN, ""),
                EngineResult("C", "CALL", .8, "")
            )
        )

        assertEquals(SignalDirection.WAIT, DecisionEngine().decide(snapshot, .5).direction)
    }

    @Test fun alignedFreshDirectionalEnginesCanProduceCall() {
        val capturedAt = Instant.parse("2026-01-01T00:00:00Z")
        val clock = Clock.fixed(capturedAt.plusMillis(100), ZoneOffset.UTC)
        val snapshot = AnalysisSnapshot(
            "EUR/USD",
            "1m",
            capturedAt,
            100,
            .9,
            listOf(
                EngineResult("A", "CALL", .9, "", capturedAt),
                EngineResult("B", "CALL", .8, "", capturedAt),
                EngineResult("C", "CALL", .7, "", capturedAt)
            )
        )

        assertEquals(SignalDirection.CALL, DecisionEngine(clock).decide(snapshot, .65).direction)
    }

    @Test fun realExecutionIsBlockedByRiskBoundary() {
        val decision = Decision(SignalDirection.CALL, "EUR/USD", 60, .9, .1, "", emptyList(), now)
        val result = RiskManager(RiskSettings()).validate(decision, AccountType.REAL, RiskState())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test fun invalidRiskNumbersAreRejected() {
        val decision = Decision(SignalDirection.CALL, "EUR/USD", 60, .9, .1, "", emptyList(), now)

        assertTrue(
            RiskManager(RiskSettings(stake = Double.NaN))
                .validate(decision, AccountType.DEMO, RiskState())
                .isFailure
        )
    }
}
