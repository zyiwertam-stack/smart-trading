package com.smarttrading.accessibility

data class ExpectedTradeState(
    val pair: String,
    val amount: Double,
    val expirySeconds: Int,
    val requireDemo: Boolean = true
)

data class StateVerification(val valid: Boolean, val reasons: List<String>)

class PocketOptionStateVerifier {
    fun verify(snapshot: PocketOptionUiSnapshot, expected: ExpectedTradeState): StateVerification {
        val reasons = mutableListOf<String>()
        if (snapshot.screen != PocketOptionScreen.TRADING) reasons += "Trading screen was not positively identified"
        if (snapshot.recognizedAtMillis <= 0 ||
            System.currentTimeMillis() - snapshot.recognizedAtMillis !in 0..MAX_SNAPSHOT_AGE_MS
        ) reasons += "Accessibility snapshot is stale"
        if (snapshot.sourcePackage.isNullOrBlank()) reasons += "Source application is unknown"
        if (!snapshot.platformVerified) reasons += "Pocket Option application or branding was not verified"
        val pair = snapshot.first(PocketElement.PAIR_NAME)?.text.orEmpty()
        if (pair.isBlank() || !pair.contains(expected.pair, ignoreCase = true)) {
            reasons += "Selected currency pair does not match the requested pair"
        }
        val actualAmount = snapshot.all(PocketElement.AMOUNT)
            .mapNotNull { it.text.filter { char -> char.isDigit() || char == '.' || char == ',' }
                .replace(',', '.').toDoubleOrNull() }
            .firstOrNull()
        if (actualAmount == null || actualAmount != expected.amount) reasons += "Visible amount does not match the requested amount"
        val expiryValue = snapshot.all(PocketElement.EXPIRATION).mapNotNull { parseExpirySeconds(it.text) }.firstOrNull()
        if (expiryValue == null || expiryValue != expected.expirySeconds) reasons += "Visible expiration does not match the requested duration"
        if (expected.requireDemo && snapshot.accountType != DetectedAccountType.DEMO) {
            reasons += "DEMO account was not positively identified"
        }
        if (snapshot.all(PocketElement.CALL_BUTTON).count { it.enabled && it.clickable } +
            snapshot.all(PocketElement.PUT_BUTTON).count { it.enabled && it.clickable } == 0
        ) reasons += "No enabled actionable CALL or PUT control was recognized"
        return StateVerification(reasons.isEmpty(), reasons)
    }

    private fun parseExpirySeconds(value: String): Int? {
        val text = value.lowercase()
        val number = Regex("\\d+").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        if (number.isEmpty()) return null
        if ("min" in text || "minute" in text) return number.first() * 60
        if ("sec" in text || "second" in text) return number.first()
        if (':' in text && number.size >= 2) return number[0] * 60 + number[1]
        return number.first()
    }

    companion object {
        const val MAX_SNAPSHOT_AGE_MS = 1_500L
    }
}
