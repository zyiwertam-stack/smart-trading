package com.smarttrading

import com.smarttrading.accessibility.*
import com.smarttrading.ai.*
import com.smarttrading.domain.*
import com.smarttrading.risk.RiskManager
import com.smarttrading.risk.RiskState
import com.smarttrading.trading.IntegrationStatus
import com.smarttrading.vision.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AutoControllerTest {
    @Test fun autoCannotBecomeReadyWithoutVerifiedExecutionAdapter() {
        val now = Instant.now()
        val chart = ChartDetectionResult(
            ChartStatus.DETECTED, PixelRect(5, 5, 200, 150), 195, 145, now,
            .9, true, false, emptyList()
        )
        val candles = (0 until 5).map { index ->
            Candle(index * 10, 70, 50, 90, 55, 85, 5, 5, CandleColor.GREEN,
                CandleDirection.BULLISH, .2, .02, now)
        }
        val candleResult = CandleRecognitionResult(candles, .9, emptyList())
        val trend = TrendAnalysis(TrendDirection.UPTREND, .9, listOf(1), listOf(2), false, emptyList())
        val levels = SupportResistanceAnalysis(emptyList(), emptyList(), .8, emptyList())
        val momentum = MomentumAnalysis(MomentumState.BULLISH, .9, emptyList())
        val volatility = VolatilityAnalysis(RelativeVolatility.NORMAL, .1, false, emptyList())
        val condition = MarketConditionAnalysis(MarketState.TRENDING, .9, emptyList())
        val analysis = MarketAnalysis(chart, candleResult, emptyList(), trend, levels, momentum, volatility,
            condition, emptyList(), now)
        val decision = Decision(SignalDirection.CALL, "EUR/USD", 60, .9, .1, "verified", emptyList(), now)
        val snapshot = PocketOptionUiSnapshot(
            emptyList(), System.currentTimeMillis(), PocketOptionScreen.TRADING,
            DetectedAccountType.DEMO, "com.pocketoption", platformVerified = true
        )
        val readiness = AutoController().checkReadiness(
            AutoControllerInput(
                TradingMode.AUTO, true, AccountType.DEMO, analysis, decision, true, snapshot,
                IntegrationStatus.BLOCKED, RiskManager(RiskSettings()), RiskState()
            )
        )

        assertEquals(AutoControllerState.BLOCKED, readiness.state)
        assertTrue(readiness.reason.contains("adapter"))
    }

    @Test fun autoPermissionAndModeMustBeExplicit() {
        val result = AutoController().checkReadiness(
            AutoControllerInput(
                TradingMode.MANUAL, false, AccountType.DEMO, null, null, false, null,
                IntegrationStatus.BLOCKED, RiskManager(RiskSettings()), RiskState()
            )
        )

        assertEquals(AutoControllerState.DISABLED, result.state)
    }
}
