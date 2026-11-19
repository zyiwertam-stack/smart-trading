package com.smarttrading.domain

import java.time.Instant

enum class AccountType { DEMO, REAL }
enum class TradingMode { MANUAL, AUTO }
enum class SignalDirection { CALL, PUT, WAIT }
enum class MarketCondition {
    TRENDING, RANGING, BREAKOUT, POSSIBLE_REVERSAL, HIGH_VOLATILITY, LOW_VOLATILITY, UNKNOWN, UNCERTAIN
}

data class EngineResult(
    val engine: String,
    val value: String,
    val confidence: Double,
    val explanation: String,
    val timestamp: Instant = Instant.now(),
    val sourceData: Map<String, String> = emptyMap(),
    val recognitionQuality: Double = 1.0,
    val error: String? = null
)

data class AnalysisSnapshot(
    val pair: String,
    val timeframe: String,
    val capturedAt: Instant,
    val frameAgeMs: Long,
    val imageQuality: Double,
    val engines: List<EngineResult>,
    val chartDetected: Boolean = true,
    val recognizedCandleCount: Int = 5
)

data class Decision(
    val direction: SignalDirection,
    val pair: String,
    val expirySeconds: Int?,
    val confidence: Double,
    val uncertainty: Double,
    val explanation: String,
    val engines: List<EngineResult>,
    val analyzedAt: Instant
)

data class RiskSettings(
    val stake: Double = 1.0,
    val maxStake: Double = 10.0,
    val maxSessionTrades: Int = 20,
    val maxDailyTrades: Int = 50,
    val maxDailyLoss: Double = 25.0,
    val maxConsecutiveLosses: Int = 3,
    val minConfidence: Double = 0.65
)
