package com.smarttrading

import com.smarttrading.accessibility.*
import org.junit.Assert.*
import org.junit.Test

class AccessibilityStateTest {
    private val verifier = PocketOptionStateVerifier()
    private val expected = ExpectedTradeState("EUR/USD", 1.0, 60)

    @Test fun onlyFreshMatchingDemoTradingScreenPassesVerification() {
        val snapshot = PocketOptionUiSnapshot(
            listOf(
                element(PocketElement.PAIR_NAME, "EUR/USD"),
                element(PocketElement.AMOUNT, "1.00"),
                element(PocketElement.EXPIRATION, "1 min"),
                element(PocketElement.CALL_BUTTON, "CALL", clickable = true),
                element(PocketElement.PUT_BUTTON, "PUT", clickable = true)
            ),
            System.currentTimeMillis(),
            PocketOptionScreen.TRADING,
            DetectedAccountType.DEMO,
            "com.pocketoption",
            platformVerified = true
        )

        assertTrue(verifier.verify(snapshot, expected).valid)
        assertEquals(PocketOptionScreen.TRADING, PocketOptionScreenDetector().detect(snapshot))
    }

    @Test fun realUnknownWrongPairAndStaleScreensAreRejected() {
        val base = PocketOptionUiSnapshot(
            listOf(
                element(PocketElement.PAIR_NAME, "GBP/USD"),
                element(PocketElement.AMOUNT, "1"),
                element(PocketElement.EXPIRATION, "60 sec"),
                element(PocketElement.CALL_BUTTON, "CALL", clickable = true)
            ),
            System.currentTimeMillis() - 10_000,
            PocketOptionScreen.TRADING,
            DetectedAccountType.REAL,
            "com.pocketoption",
            platformVerified = true
        )

        val result = verifier.verify(base, expected)

        assertFalse(result.valid)
        assertTrue(result.reasons.any { it.contains("pair") })
        assertTrue(result.reasons.any { it.contains("DEMO") })
        assertTrue(result.reasons.any { it.contains("stale") })
    }

    private fun element(type: PocketElement, text: String, clickable: Boolean = false) =
        RecognizedUiElement(type, text, null, null, clickable, true, IntRect(0, 0, 20, 20))
}
