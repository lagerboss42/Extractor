package com.majk.extractor.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.majk.extractor.data.parser.OpterListTextParser
import com.majk.extractor.vpn.CaptureRepository

/**
 * Reads on-screen text from Opter Driver and feeds [OpterListTextParser].
 * Enable in system Accessibility settings. Works alongside VPN.
 */
class OpterAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300
            packageNames = arrayOf(OPTER_PACKAGE)
        }
        isRunning = true
        Log.i(TAG, "Opter accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != OPTER_PACKAGE) return
        val root = rootInActiveWindow ?: return
        try {
            val sb = StringBuilder()
            collectText(root, sb, 0)
            val text = sb.toString()
            if (text.length < 40) return
            val now = System.currentTimeMillis()
            if (now - lastParseMs < 1500) return
            if (text == lastText) return
            lastParseMs = now
            lastText = text

            val found = OpterListTextParser.parse(text)
            if (found.isNotEmpty()) {
                CaptureRepository.mergeEnrich(found)
                Log.i(TAG, "A11y parsed ${found.size} stops (total ${CaptureRepository.lastParseCount})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "A11y parse: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "interrupted")
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > 40) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty()) out.appendLine(t)
        else if (!d.isNullOrEmpty()) out.appendLine(d)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out, depth + 1)
            child.recycle()
        }
    }

    companion object {
        const val OPTER_PACKAGE = "com.opter.driver"
        private const val TAG = "OpterA11y"
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile private var lastParseMs: Long = 0
        @Volatile private var lastText: String = ""

        fun openSettings(context: android.content.Context) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
