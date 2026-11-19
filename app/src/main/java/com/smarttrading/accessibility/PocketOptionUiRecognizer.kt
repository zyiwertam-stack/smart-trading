package com.smarttrading.accessibility

import android.view.accessibility.AccessibilityNodeInfo

enum class PocketElement {
    TRADING_SCREEN, CURRENCY_PAIR, PAIR_NAME, AMOUNT, EXPIRATION, CALL_BUTTON, PUT_BUTTON,
    OPEN_TRADES, CLOSED_TRADES, BALANCE, DEMO_ACCOUNT, REAL_ACCOUNT, UNKNOWN
}

data class RecognizedUiElement(
    val type: PocketElement,
    val text: String,
    val resourceId: String?,
    val className: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: IntRect
)

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class PocketOptionUiSnapshot(
    val elements: List<RecognizedUiElement>,
    val recognizedAtMillis: Long,
    val screen: PocketOptionScreen,
    val accountType: DetectedAccountType,
    val sourcePackage: String?,
    val platformVerified: Boolean = false
) {
    fun first(type: PocketElement): RecognizedUiElement? = elements.firstOrNull { it.type == type }
    fun all(type: PocketElement): List<RecognizedUiElement> = elements.filter { it.type == type }
}

enum class PocketOptionScreen { TRADING, OTHER, UNKNOWN }
enum class DetectedAccountType { DEMO, REAL, UNKNOWN }

class PocketOptionUiRecognizer {
    fun recognize(root: AccessibilityNodeInfo?, nowMillis: Long = System.currentTimeMillis()): PocketOptionUiSnapshot {
        if (root == null) {
            return PocketOptionUiSnapshot(emptyList(), nowMillis, PocketOptionScreen.UNKNOWN, DetectedAccountType.UNKNOWN, null)
        }
        val elements = mutableListOf<RecognizedUiElement>()
        walk(root, elements)
        val sourcePackage = root.packageName?.toString()
        val platformVerified = sourcePackage?.contains("pocketoption", ignoreCase = true) == true ||
            elements.any {
                it.text.contains("pocket option", ignoreCase = true) ||
                    it.text.contains("pocketoption", ignoreCase = true)
            }
        if (!platformVerified) {
            return PocketOptionUiSnapshot(
                emptyList(), nowMillis, PocketOptionScreen.OTHER, DetectedAccountType.UNKNOWN,
                sourcePackage, platformVerified = false
            )
        }
        val types = elements.map { it.type }.toSet()
        val screen = when {
            PocketElement.CALL_BUTTON in types && PocketElement.PUT_BUTTON in types &&
                PocketElement.AMOUNT in types -> PocketOptionScreen.TRADING
            elements.isEmpty() -> PocketOptionScreen.UNKNOWN
            else -> PocketOptionScreen.OTHER
        }
        val account = when {
            PocketElement.REAL_ACCOUNT in types -> DetectedAccountType.REAL
            PocketElement.DEMO_ACCOUNT in types -> DetectedAccountType.DEMO
            else -> DetectedAccountType.UNKNOWN
        }
        return PocketOptionUiSnapshot(elements, nowMillis, screen, account, sourcePackage, platformVerified)
    }

    private fun walk(node: AccessibilityNodeInfo, output: MutableList<RecognizedUiElement>) {
        if (node.isPassword) return
        val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ").trim()
        val resourceId = node.viewIdResourceName
        val type = classify(text, resourceId, node.className?.toString())
        if (type != PocketElement.UNKNOWN || text.isNotBlank()) {
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            output += RecognizedUiElement(
                type, text, resourceId, node.className?.toString(), node.isClickable, node.isEnabled,
                IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
            )
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                walk(child, output)
            } finally {
                child.recycle()
            }
        }
    }

    private fun classify(text: String, resourceId: String?, className: String?): PocketElement {
        val value = "$text ${resourceId.orEmpty()}".lowercase()
        return when {
            Regex("\\b[a-z]{3}\\s*/\\s*[a-z]{3}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> PocketElement.PAIR_NAME
            Regex("\\breal\\b").containsMatchIn(value) || containsAny(value, "real account", "real balance", "real_account") -> PocketElement.REAL_ACCOUNT
            Regex("\\bdemo\\b").containsMatchIn(value) || containsAny(value, "practice", "demo balance", "demo_account") -> PocketElement.DEMO_ACCOUNT
            containsAny(value, "call", "buy", "up-button", "btn_up") -> PocketElement.CALL_BUTTON
            containsAny(value, "put", "sell", "down-button", "btn_down") -> PocketElement.PUT_BUTTON
            containsAny(value, "currency pair", "asset", "instrument", "pair-selector") -> PocketElement.CURRENCY_PAIR
            containsAny(value, "amount", "investment", "stake", "sum") -> PocketElement.AMOUNT
            containsAny(value, "expiration", "expiry", "duration", "timeframe") -> PocketElement.EXPIRATION
            containsAny(value, "open trades", "active trades", "open_orders") -> PocketElement.OPEN_TRADES
            containsAny(value, "closed trades", "history", "closed_orders") -> PocketElement.CLOSED_TRADES
            containsAny(value, "balance", "account_balance") -> PocketElement.BALANCE
            className?.contains("EditText", ignoreCase = true) == true && text.toDoubleOrNull() != null -> PocketElement.AMOUNT
            else -> PocketElement.UNKNOWN
        }
    }

    private fun containsAny(value: String, vararg tokens: String) = tokens.any(value::contains)
}
