package dev.ytskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Listens to the YouTube app's UI tree and taps "Skip" on ads.
 *
 * Strategy:
 *  1. Look up known view IDs of the skip button (fast, language independent).
 *  2. Fall back to text / content-description matches ("Skip", "Saltar", ...).
 *  3. Click the node (or its nearest clickable parent); if that fails,
 *     dispatch a real tap gesture at the centre of the button.
 */
class SkipAdService : AccessibilityService() {

    private var lastSkipAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != YT_PACKAGE) return

        // Avoid hammering the button while YouTube animates it away.
        val now = SystemClock.uptimeMillis()
        if (now - lastSkipAt < COOLDOWN_MS) return

        val root = rootInActiveWindow ?: return
        val button = findSkipButton(root) ?: return

        if (pressNode(button)) {
            lastSkipAt = now
            Prefs.incrementSkips(this)
            Log.d(TAG, "Ad skipped")
        }
    }

    override fun onInterrupt() = Unit

    // ---- finding the button --------------------------------------------------

    private fun findSkipButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in SKIP_VIEW_IDS) {
            root.findAccessibilityNodeInfosByViewId("$YT_PACKAGE:id/$id")
                .firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }
        return findByLabel(root)
    }

    /** Depth-first search for a visible node whose text/description is a skip label. */
    private fun findByLabel(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (node.isVisibleToUser && looksLikeSkip(node)) return node
        for (i in 0 until node.childCount) {
            findByLabel(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun looksLikeSkip(node: AccessibilityNodeInfo): Boolean {
        val label = (node.text ?: node.contentDescription)?.toString()
            ?.trim()?.lowercase() ?: return false
        // Exact / prefix matches only, so we never tap e.g. a video titled "Skip day".
        return SKIP_LABELS.any { label == it || label.startsWith("$it ") }
    }

    // ---- pressing it ----------------------------------------------------------

    private fun pressNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        return tapAt(node)
    }

    private fun tapAt(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "YTAdSkipper"
        private const val YT_PACKAGE = "com.google.android.youtube"
        private const val COOLDOWN_MS = 1_500L
        private const val MAX_DEPTH = 40

        /** View IDs YouTube has used for the skip button. They change between versions. */
        private val SKIP_VIEW_IDS = listOf(
            "skip_ad_button",
            "skip_ad_button_container",
            "modern_skip_ad_text",
            "skip_ad_button_text",
        )

        /** Lower-case labels, several languages. Add yours if needed. */
        private val SKIP_LABELS = listOf(
            "skip", "skip ad", "skip ads",
            "saltar", "omitir", "omitir anuncio", "omitir anuncios", "saltar anuncio",
            "ignorer", "ignorer l'annonce",
            "überspringen", "werbung überspringen",
            "salta", "salta annuncio",
            "pular", "pular anúncio",
        )
    }
}
