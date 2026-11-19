package com.smarttrading

import com.smarttrading.domain.*
import com.smarttrading.risk.RiskManager
import com.smarttrading.risk.RiskState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RiskManagerTest {
    private val decision = Decision(SignalDirection.CALL, "EUR/USD", 60, .9, .1, "", emptyList(), Instant.now())

    @Test fun waitAndRiskLimitBreachesAreNeverAccepted() {
        val wait = decision.copy(direction = SignalDirection.WAIT)
        val dailyLimit = RiskState(dailyTrades = RiskSettings().maxDailyTrades)
        val losses = RiskState(consecutiveLosses = RiskSettings().maxConsecutiveLosses)

        assertTrue(RiskManager(RiskSettings()).validate(wait, AccountType.DEMO, RiskState()).isFailure)
        assertTrue(RiskManager(RiskSettings()).validate(decision, AccountType.DEMO, dailyLimit).isFailure)
        assertTrue(RiskManager(RiskSettings()).validate(decision, AccountType.DEMO, losses).isFailure)
    }

    @Test fun visibleStakeMustMatchConfiguredRiskStake() {
        val manager = RiskManager(RiskSettings(stake = 2.0))

        assertTrue(manager.validateRequestedStake(2.0).isSuccess)
        assertTrue(manager.validateRequestedStake(3.0).isFailure)
        assertTrue(manager.validateRequestedStake(Double.NaN).isFailure)
    }
}
