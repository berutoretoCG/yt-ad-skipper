package dev.ytskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Listens to the YouTube app's UI and taps "Skip"/"Saltar"/"Omitir" on ads.
 *
 *  1. Checks every YouTube window (not only the focused one), so it also works
 *     with the mini-player, full screen and overlays.
 *  2. Looks for known skip-button view IDs, then for the button's text using
 *     the system's own text search (fast; no walking the whole screen).
 *  3. Ignores the countdown ("Saltar en 5") and anything that isn't an exact
 *     skip label, so it never taps a video title.
 *  4. Clicks the button itself or a small clickable parent. If that's not
 *     possible, it taps the button's exact position — never the whole player,
 *     which would pause the video.
 */
class SkipAdService : AccessibilityService() {

    private var lastSkipAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        StatusUi.onStateChanged(this, true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopped()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopped()
        super.onDestroy()
    }

    private fun stopped() {
        if (instance === this) {
            instance = null
            StatusUi.onStateChanged(this, false)
        }
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != YT_PACKAGE) return

        val now = SystemClock.uptimeMillis()
        if (now - lastSkipAt < COOLDOWN_MS) return

        for (root in youtubeRoots()) {
            val button = findSkipButton(root) ?: continue
            if (press(button)) {
                lastSkipAt = now
                Prefs.recordSkip(this)
                StatusUi.showNotification(this)
                Log.d(TAG, "Ad skipped")
                return
            }
        }
    }

    // ---- finding the button --------------------------------------------------

    private fun youtubeRoots(): List<AccessibilityNodeInfo> {
        val roots = try {
            windows.mapNotNull { it.root }.filter { it.packageName?.toString() == YT_PACKAGE }
        } catch (_: Exception) {
            emptyList()
        }
        if (roots.isNotEmpty()) return roots
        return listOfNotNull(rootInActiveWindow?.takeIf { it.packageName?.toString() == YT_PACKAGE })
    }

    private fun findSkipButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in SKIP_VIEW_IDS) {
            root.findAccessibilityNodeInfosByViewId("$YT_PACKAGE:id/$id")
                .firstOrNull { it.isVisibleToUser && !hasDigits(it) }
                ?.let { return it }
        }
        for (word in SEARCH_WORDS) {
            root.findAccessibilityNodeInfosByText(word)
                .firstOrNull { it.isVisibleToUser && isSkipLabel(it) }
                ?.let { return it }
        }
        return null
    }

    private fun label(node: AccessibilityNodeInfo): String? =
        (node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription)
            ?.toString()?.trim()?.lowercase()

    /** Countdown labels like "Saltar en 5" contain digits — the button isn't ready yet. */
    private fun hasDigits(node: AccessibilityNodeInfo): Boolean =
        label(node)?.any { it.isDigit() } == true

    private fun isSkipLabel(node: AccessibilityNodeInfo): Boolean {
        val l = label(node) ?: return false
        return l in SKIP_LABELS
    }

    // ---- pressing it ----------------------------------------------------------

    private fun press(node: AccessibilityNodeInfo): Boolean {
        val nodeBounds = bounds(node)
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && hops <= 3) {
            if (target.isClickable) {
                // Only click parents roughly the size of the button, not the whole player.
                val small = nodeBounds.isEmpty || area(bounds(target)) <= area(nodeBounds) * 6
                if (small && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                break
            }
            target = target.parent
            hops++
        }
        return tapAt(nodeBounds)
    }

    private fun tapAt(r: Rect): Boolean {
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
    private fun area(r: Rect) = r.width().toLong() * r.height().toLong()

    companion object {
        /** The running service, or null when it's off. Used by the Quick Settings button. */
        @Volatile
        var instance: SkipAdService? = null
            private set

        private const val TAG = "YTAdSkipper"
        private const val YT_PACKAGE = "com.google.android.youtube"
        private const val COOLDOWN_MS = 1_500L

        /** View IDs YouTube has used for the skip button. They change between versions. */
        private val SKIP_VIEW_IDS = listOf(
            "skip_ad_button",
            "modern_skip_ad_text",
            "skip_ad_button_text",
            "skip_ad_button_container",
        )

        /** Words passed to the system text search (substring, case-insensitive). */
        private val SEARCH_WORDS = listOf(
            "Saltar", "Omitir", "Skip", "Ignorer", "Überspringen", "Salta", "Pular",
        )

        /** Exact labels (lower-case) accepted as the skip button. */
        private val SKIP_LABELS = setOf(
            "saltar", "saltar anuncio", "saltar anuncios",
            "omitir", "omitir anuncio", "omitir anuncios",
            "skip", "skip ad", "skip ads",
            "ignorer", "ignorer l'annonce", "ignorer les annonces",
            "überspringen", "werbung überspringen",
            "salta", "salta annuncio", "salta annunci",
            "pular", "pular anúncio", "pular anúncios",
        )
    }
}
