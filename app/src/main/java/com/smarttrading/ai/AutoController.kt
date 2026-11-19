package com.smarttrading.ai

import com.smarttrading.accessibility.DetectedAccountType
import com.smarttrading.accessibility.PocketOptionScreen
import com.smarttrading.accessibility.PocketOptionUiSnapshot
import com.smarttrading.domain.AccountType
import com.smarttrading.domain.Decision
import com.smarttrading.domain.RiskSettings
import com.smarttrading.domain.SignalDirection
import com.smarttrading.domain.TradingMode
import com.smarttrading.risk.RiskManager
import com.smarttrading.risk.RiskState
import com.smarttrading.trading.IntegrationStatus
import com.smarttrading.vision.ChartStatus
import com.smarttrading.vision.MarketAnalysis
import java.time.Duration
import java.time.Instant

enum class AutoControllerState {
    DISABLED, INITIALIZING, CHECKING, READY, ANALYZING, WAITING, EXECUTING,
    VERIFYING, MONITORING, STOPPED, ERROR, BLOCKED
}

data class AutoReadiness(
    val state: AutoControllerState,
    val reason: String,
    val checkedAt: Instant = Instant.now()
)

data class AutoControllerInput(
    val mode: TradingMode,
    val permissionGranted: Boolean,
    val account: AccountType,
    val analysis: MarketAnalysis?,
    val decision: Decision?,
    val accessibilityEnabled: Boolean,
    val uiSnapshot: PocketOptionUiSnapshot?,
    val adapterStatus: IntegrationStatus,
    val riskManager: RiskManager,
    val riskState: RiskState
)

class AutoController {
    fun checkReadiness(input: AutoControllerInput): AutoReadiness {
        if (input.mode != TradingMode.AUTO || !input.permissionGranted) {
            return AutoReadiness(AutoControllerState.DISABLED, "AUTO mode or explicit AUTO permission is disabled")
        }
        val analysis = input.analysis
            ?: return AutoReadiness(AutoControllerState.INITIALIZING, "Waiting for a completed screen analysis")
        if (Duration.between(analysis.capturedAt, Instant.now()).toMillis() !in 0..MAX_ANALYSIS_AGE_MS) {
            return AutoReadiness(AutoControllerState.BLOCKED, "Latest visual analysis is stale")
        }
        if (analysis.chart.status != ChartStatus.DETECTED || analysis.chart.obscured ||
            analysis.candles.candles.size < MIN_CANDLES
        ) {
            return AutoReadiness(AutoControllerState.WAITING, "A visible chart and at least five recognized candles are required")
        }
        if (!input.accessibilityEnabled || input.uiSnapshot?.screen != PocketOptionScreen.TRADING ||
            input.uiSnapshot.platformVerified.not()
        ) {
            return AutoReadiness(AutoControllerState.BLOCKED, "Pocket Option trading screen is not verified by Accessibility")
        }
        if (input.account != AccountType.DEMO || input.uiSnapshot.accountType != DetectedAccountType.DEMO) {
            return AutoReadiness(AutoControllerState.BLOCKED, "Only a positively verified DEMO account is permitted")
        }
        val decision = input.decision
            ?: return AutoReadiness(AutoControllerState.INITIALIZING, "Waiting for the AI decision")
        if (decision.direction == SignalDirection.WAIT) {
            return AutoReadiness(AutoControllerState.WAITING, decision.explanation)
        }
        val risk = input.riskManager.validate(decision, input.account, input.riskState)
        if (risk.isFailure) {
            return AutoReadiness(AutoControllerState.BLOCKED, risk.exceptionOrNull()?.message ?: "Risk manager rejected this decision")
        }
        if (input.adapterStatus != IntegrationStatus.CONNECTED) {
            return AutoReadiness(AutoControllerState.BLOCKED, "No verified, connected trade-execution adapter is available")
        }
        return AutoReadiness(AutoControllerState.READY, "Analysis, Accessibility, DEMO account, risk, and adapter checks passed")
    }

    private companion object {
        const val MAX_ANALYSIS_AGE_MS = 2_000L
        const val MIN_CANDLES = 5
    }
}
