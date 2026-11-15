package com.example.ghostwink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GhostAccess : AccessibilityService() {

    companion object {
        var instance: GhostAccess? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- Global actions ----------
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---------- Gestures ----------
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
            .build()
        dispatchGesture(g, null, null)
    }

    fun scroll(down: Boolean) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        if (down) swipe(w / 2, h * 0.7f, w / 2, h * 0.3f, 350)
        else swipe(w / 2, h * 0.3f, w / 2, h * 0.7f, 350)
    }

    fun tapAt(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, null, null)
    }

    fun tapCenter() {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val p = Path()
        p.moveTo(w / 2, h / 2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, null, null)
    }

    // ---------- Typing ----------
    private fun focusedField(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (node.isPassword) return null
        return node
    }

    private fun setNodeText(node: AccessibilityNodeInfo, newText: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun typeText(s: String): Boolean {
        val node = focusedField() ?: return false
        val current = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) {
            start = current.length
            end = current.length
        }
        val low = minOf(start, end)
        val high = maxOf(start, end)
        val newText = current.substring(0, low) + s + current.substring(high)

        var ok = setNodeText(node, newText)
        if (!ok) {
            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("gw", s))
            ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        val sel = Bundle()
        val pos = low + s.length
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return ok
    }

    fun deleteLast(): Boolean {
        val node = focusedField() ?: return false
        if (node.isShowingHintText) return false
        val current = node.text?.toString() ?: return false
        if (current.isEmpty()) return false
        val ok = setNodeText(node, current.dropLast(1))
        val sel = Bundle()
        val pos = current.length - 1
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return ok
    }
}