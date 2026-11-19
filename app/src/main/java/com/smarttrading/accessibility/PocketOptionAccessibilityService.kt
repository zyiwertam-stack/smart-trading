package com.smarttrading.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PocketOptionAccessibilityService : AccessibilityService() {
    private val recognizer = PocketOptionUiRecognizer()

    override fun onServiceConnected() {
        super.onServiceConnected()
        mutableStatus.value = AccessibilityStatus(enabled = true, snapshot = null, detail = "Service connected; waiting for a visible app window")
        refreshSnapshot(rootInActiveWindow)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        refreshSnapshot(rootInActiveWindow)
    }

    override fun onInterrupt() {
        mutableStatus.value = mutableStatus.value.copy(detail = "Accessibility service was interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        mutableStatus.value = AccessibilityStatus(false, null, "Accessibility service is disabled")
        return super.onUnbind(intent)
    }

    private fun refreshSnapshot(root: AccessibilityNodeInfo?) {
        val snapshot = try {
            recognizer.recognize(root)
        } finally {
            root?.recycle()
        }
        mutableStatus.value = AccessibilityStatus(
            enabled = true,
            snapshot = snapshot,
            detail = when (snapshot.screen) {
                PocketOptionScreen.TRADING -> "Trading interface detected from accessible labels"
                PocketOptionScreen.OTHER -> "Accessible window detected; Pocket Option trading controls not confirmed"
                PocketOptionScreen.UNKNOWN -> "No readable active window"
            }
        )
    }

    companion object {
        private val mutableStatus = MutableStateFlow(AccessibilityStatus())
        val status = mutableStatus.asStateFlow()
    }
}

data class AccessibilityStatus(
    val enabled: Boolean = false,
    val snapshot: PocketOptionUiSnapshot? = null,
    val detail: String = "Enable the service in Android Accessibility settings"
)
