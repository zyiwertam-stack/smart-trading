package com.smarttrading.risk

import com.smarttrading.domain.*

data class RiskState(val sessionTrades: Int = 0, val dailyTrades: Int = 0, val dailyPnl: Double = 0.0, val consecutiveLosses: Int = 0)

class RiskManager(private val settings: RiskSettings) {
    fun validateRequestedStake(requestedStake: Double): Result<Unit> {
        if (!requestedStake.isFinite() || requestedStake <= 0 || requestedStake != settings.stake) {
            return Result.failure(IllegalStateException("Visible stake does not match the risk-configured stake"))
        }
        return Result.success(Unit)
    }

    fun validate(decision: Decision, account: AccountType, state: RiskState): Result<Unit> {
        if (decision.direction == SignalDirection.WAIT) return Result.failure(IllegalStateException("WAIT signal cannot be traded"))
        if (!settings.stake.isFinite() || !settings.maxStake.isFinite() ||
            settings.stake <= 0 || settings.maxStake <= 0 || settings.stake > settings.maxStake
        ) return Result.failure(IllegalStateException("Stake exceeds configured limit"))
        if (!decision.confidence.isFinite() || decision.confidence !in 0.0..1.0 ||
            !settings.minConfidence.isFinite() || settings.minConfidence !in 0.0..1.0 ||
            decision.confidence < settings.minConfidence
        ) return Result.failure(IllegalStateException("Signal confidence is below the configured minimum"))
        if (settings.maxSessionTrades < 0 || settings.maxDailyTrades < 0 ||
            settings.maxConsecutiveLosses < 0 || !settings.maxDailyLoss.isFinite() ||
            settings.maxDailyLoss <= 0
        ) return Result.failure(IllegalStateException("Risk limits are invalid"))
        if (state.sessionTrades < 0 || state.dailyTrades < 0 || state.consecutiveLosses < 0 ||
            !state.dailyPnl.isFinite()
        ) return Result.failure(IllegalStateException("Current risk state is invalid"))
        if (state.sessionTrades >= settings.maxSessionTrades) return Result.failure(IllegalStateException("Session trade limit reached"))
        if (state.dailyTrades >= settings.maxDailyTrades) return Result.failure(IllegalStateException("Daily trade limit reached"))
        if (state.dailyPnl <= -settings.maxDailyLoss) return Result.failure(IllegalStateException("Maximum daily loss reached"))
        if (state.consecutiveLosses >= settings.maxConsecutiveLosses) return Result.failure(IllegalStateException("Loss streak limit reached"))
        if (account == AccountType.REAL) return Result.failure(UnsupportedOperationException("REAL execution is blocked: no confirmed Pocket Option API"))
        return Result.success(Unit)
    }
}
