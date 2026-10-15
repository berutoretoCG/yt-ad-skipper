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
 * Listens to the YouTube app's UI and taps the skip button on ads.
 *
 * YouTube now shows an icon-only skip button (⏭) with no visible text, so:
 *  1. Fast path: known skip-button view IDs, then exact labels via system text search.
 *  2. If an ad is on screen ("Patrocinado", "Ir al sitio del anunciante"…), a deep
 *     search looks for ANY node whose view ID contains "skip" or whose
 *     description starts with a skip word — this catches the icon button.
 *  3. Countdowns ("Saltar en 5") are ignored.
 *  4. If an ad is on screen and no button is found, the screen structure is saved
 *     as a diagnostic the user can share, so the next fix is exact.
 */
class SkipAdService : AccessibilityService() {

    private var lastSkipAt = 0L
    private var lastDumpAt = 0L
    private var lastAdDumpAt = 0L
    private var lastSeenWriteAt = 0L

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
        if (now - lastSeenWriteAt > 2_000) {
            lastSeenWriteAt = now
            Prefs.markYoutubeSeen(this)
        }
        if (now - lastSkipAt < COOLDOWN_MS) return

        for (root in youtubeRoots()) {
            var button = findSkipButtonFast(root)
            val adShowing = button != null || isAdShowing(root)
            if (button == null && adShowing) button = findSkipButtonDeep(root)

            if (adShowing) Prefs.markAdSeen(this)
            if (adShowing && now - lastDumpAt > DUMP_INTERVAL_MS) {
                lastDumpAt = now
                lastAdDumpAt = now
                Diagnostics.save(this, root, button, adShowing = true)
            } else if (!adShowing && now - lastDumpAt > 15_000 && now - lastAdDumpAt > 120_000) {
                // No ad recognised: still keep a recent snapshot, in case ads look different.
                lastDumpAt = now
                Diagnostics.save(this, root, null, adShowing = false)
            }
            if (button != null && press(button)) {
                lastSkipAt = now
                Prefs.recordSkip(this)
                StatusUi.showNotification(this)
                Log.d(TAG, "Ad skipped")
                return
            }
        }
    }

    // ---- where to look -------------------------------------------------------

    private fun youtubeRoots(): List<AccessibilityNodeInfo> {
        val roots = try {
            windows.mapNotNull { it.root }.filter { it.packageName?.toString() == YT_PACKAGE }
        } catch (_: Exception) {
            emptyList()
        }
        if (roots.isNotEmpty()) return roots
        return listOfNotNull(rootInActiveWindow?.takeIf { it.packageName?.toString() == YT_PACKAGE })
    }

    private fun isAdShowing(root: AccessibilityNodeInfo): Boolean =
        AD_MARKERS.any { marker ->
            root.findAccessibilityNodeInfosByText(marker).any { it.isVisibleToUser }
        }

    // ---- finding the button --------------------------------------------------

    private fun findSkipButtonFast(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in SKIP_VIEW_IDS) {
            root.findAccessibilityNodeInfosByViewId("$YT_PACKAGE:id/$id")
                .firstOrNull { it.isVisibleToUser && !subtreeHasDigits(it) }
                ?.let { return it }
        }
        for (word in SEARCH_WORDS) {
            root.findAccessibilityNodeInfosByText(word)
                .firstOrNull { it.isVisibleToUser && label(it) in SKIP_LABELS }
                ?.let { return it }
        }
        return null
    }

    /** Only runs while an ad is on screen. Prefers clickable matches. */
    private fun findSkipButtonDeep(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val matches = ArrayList<AccessibilityNodeInfo>()
        var budget = MAX_NODES
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > MAX_DEPTH || budget-- <= 0) return
            if (n.isVisibleToUser && looksLikeSkip(n)) matches.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return matches.firstOrNull { it.isClickable } ?: matches.firstOrNull()
    }

    private fun looksLikeSkip(n: AccessibilityNodeInfo): Boolean {
        if (subtreeHasDigits(n)) return false
        val id = n.viewIdResourceName?.lowercase() ?: ""
        if ("skip" in id && "preview" !in id) return true
        val l = label(n) ?: return false
        if (l.length > 40) return false
        return l in SKIP_LABELS || SKIP_PREFIXES.any { l.startsWith(it) }
    }

    private fun label(node: AccessibilityNodeInfo): String? =
        (node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription)
            ?.toString()?.trim()?.lowercase()

    /** Countdowns like "Saltar en 5" mean the button isn't ready yet. */
    private fun subtreeHasDigits(n: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (n == null || depth > 3) return false
        if (label(n)?.any { it.isDigit() } == true) return true
        for (i in 0 until n.childCount) if (subtreeHasDigits(n.getChild(i), depth + 1)) return true
        return false
    }

    // ---- pressing it ----------------------------------------------------------

    private fun press(node: AccessibilityNodeInfo): Boolean {
        val nodeBounds = bounds(node)
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && hops <= 3) {
            if (target.isClickable) {
                // Only click parents roughly the size of the button, never the whole player.
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
        const val YT_PACKAGE = "com.google.android.youtube"
        private const val COOLDOWN_MS = 1_500L
        private const val DUMP_INTERVAL_MS = 4_000L
        private const val MAX_DEPTH = 60
        private const val MAX_NODES = 4_000

        private val SKIP_VIEW_IDS = listOf(
            "skip_ad_button",
            "modern_skip_ad_text",
            "skip_ad_button_text",
            "skip_ad_button_container",
        )

        /** Visible texts that mean "an ad is playing". */
        private val AD_MARKERS = listOf(
            "Patrocinado", "Sponsored", "anunciante", "advertiser",
            "Sponsorisé", "Gesponsert", "Sponsorizzato",
        )

        private val SEARCH_WORDS = listOf(
            "Saltar", "Omitir", "Skip", "Ignorer", "Überspringen", "Salta", "Pular",
        )

        private val SKIP_LABELS = setOf(
            "saltar", "saltar anuncio", "saltar anuncios",
            "omitir", "omitir anuncio", "omitir anuncios",
            "skip", "skip ad", "skip ads",
            "ignorer", "ignorer l'annonce", "ignorer les annonces",
            "überspringen", "werbung überspringen",
            "salta", "salta annuncio", "salta annunci",
            "pular", "pular anúncio", "pular anúncios",
        )

        /** Looser match, only used while an ad is on screen. */
        private val SKIP_PREFIXES = listOf(
            "saltar", "omitir", "skip", "ignorer", "überspringen", "salta ", "pular",
        )
    }
}
