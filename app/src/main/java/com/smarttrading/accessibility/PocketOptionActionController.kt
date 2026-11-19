package com.smarttrading.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.smarttrading.domain.AccountType
import com.smarttrading.domain.Decision
import com.smarttrading.domain.SignalDirection
import com.smarttrading.risk.RiskManager
import com.smarttrading.risk.RiskState
import com.smarttrading.domain.RiskSettings

data class AccessibilityActionResult(
    val acceptedByAndroid: Boolean,
    val verified: Boolean,
    val detail: String
)

class PocketOptionActionController(
    private val verifier: PocketOptionStateVerifier = PocketOptionStateVerifier()
) {
    fun execute(
        root: AccessibilityNodeInfo?,
        snapshot: PocketOptionUiSnapshot,
        decision: Decision,
        expected: ExpectedTradeState,
        autoPermissionGranted: Boolean,
        riskManager: RiskManager,
        riskState: RiskState
    ): AccessibilityActionResult {
        if (!autoPermissionGranted) return blocked("AUTO execution permission has not been granted")
        if (decision.direction == SignalDirection.WAIT) return blocked("WAIT decisions cannot trigger interface actions")
        if (decision.pair != expected.pair) return blocked("Decision currency pair differs from the verified pair")
        if (decision.expirySeconds != expected.expirySeconds) return blocked("Decision expiration differs from the verified expiration")
        if (snapshot.accountType != DetectedAccountType.DEMO || expected.requireDemo.not()) {
            return blocked("Accessibility trade execution is restricted to a positively identified DEMO account")
        }
        val configuredStake = riskManager.validateRequestedStake(expected.amount)
        if (configuredStake.isFailure) {
            return blocked(configuredStake.exceptionOrNull()?.message ?: "Visible stake differs from risk settings")
        }
        val risk = riskManager.validate(decision, AccountType.DEMO, riskState)
        if (risk.isFailure) return blocked(risk.exceptionOrNull()?.message ?: "Risk manager rejected the action")
        val state = verifier.verify(snapshot, expected)
        if (!state.valid) return blocked(state.reasons.joinToString("; "))
        if (root == null) return blocked("Current accessibility window is unavailable")

        val targetType = if (decision.direction == SignalDirection.CALL) PocketElement.CALL_BUTTON else PocketElement.PUT_BUTTON
        val recognized = snapshot.all(targetType).filter { it.clickable && it.enabled }
        if (recognized.size != 1) return blocked("Expected exactly one enabled $targetType node")
        val node = findNode(root, recognized.single())
            ?: return blocked("Recognized control could not be matched to the current accessibility tree")
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return AccessibilityActionResult(
            acceptedByAndroid = accepted,
            verified = false,
            detail = if (accepted) {
                "Android accepted the control action; a subsequent accessibility snapshot must confirm a trade state"
            } else {
                "Android rejected the control action"
            }
        )
    }

    private fun findNode(root: AccessibilityNodeInfo, target: RecognizedUiElement): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        root.getBoundsInScreen(bounds)
        val currentText = listOfNotNull(root.text?.toString(), root.contentDescription?.toString()).joinToString(" ").trim()
        if (root.isClickable && root.isEnabled &&
            root.viewIdResourceName == target.resourceId &&
            currentText == target.text &&
            root.className?.toString() == target.className &&
            bounds.left == target.bounds.left && bounds.top == target.bounds.top &&
            bounds.right == target.bounds.right && bounds.bottom == target.bounds.bottom
        ) return AccessibilityNodeInfo.obtain(root)
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = findNode(child, target)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun blocked(reason: String) = AccessibilityActionResult(false, false, "BLOCKED: $reason")
}
