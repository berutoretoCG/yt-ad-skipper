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
        if (event == null || event.packageName?.toString() != YT_PACKAGE) return

        val now = SystemClock.uptimeMillis()
        if (now - lastSeenWriteAt > 2_000) {
            lastSeenWriteAt = now
            Prefs.markYoutubeSeen(this)
        }
        if (now - lastSkipAt < COOLDOWN_MS) return

        val roots = youtubeRoots(event)
        if (roots.isEmpty()) {
            if (now - lastDumpAt > 10_000) {
                lastDumpAt = now
                Diagnostics.saveNoScreen(this, describeWindows(event))
            }
            return
        }
        for (root in roots) {
            val scan = scanPlayer(root)
            val button = scan.button
            val adShowing = scan.adShowing || button != null

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

    /**
     * YouTube's screen, found three ways. The event's own source is the most
     * reliable: on Xiaomi the floating accessibility menu can be the "active
     * window", hiding YouTube from rootInActiveWindow.
     */
    private fun youtubeRoots(event: AccessibilityEvent): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun addIfYoutube(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val pkg = n.packageName?.toString()
            if ((pkg == null || pkg == YT_PACKAGE) && out.none { it == n }) out.add(n)
        }
        // 1. Climb from the node that fired the event to the top of its window.
        try {
            var n = event.source
            var hops = 0
            while (n != null && hops < 200) {
                val p = n.parent ?: break
                n = p
                hops++
            }
            n?.let { top -> if (top.packageName?.toString() == YT_PACKAGE) out.add(top) }
        } catch (_: Exception) { }
        // 2. Every on-screen window that belongs to YouTube.
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == YT_PACKAGE) addIfYoutube(r)
            }
        } catch (_: Exception) { }
        // 3. The focused window.
        try {
            rootInActiveWindow?.takeIf { it.packageName?.toString() == YT_PACKAGE }?.let { addIfYoutube(it) }
        } catch (_: Exception) { }
        return out
    }

    /** Short report of what the system gave us, for when no YouTube screen was found. */
    private fun describeWindows(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        sb.append("evento: tipo=").append(event.eventType)
            .append(" pkg=").append(event.packageName)
            .append(" clase=").append(event.className)
            .append(" source=").append(if (event.source == null) "null" else event.source?.packageName)
            .append('\n')
        sb.append("rootInActiveWindow: ")
            .append(try { rootInActiveWindow?.packageName ?: "null" } catch (e: Exception) { "error " + e.message })
            .append('\n')
        try {
            val ws = windows
            sb.append("ventanas: ").append(ws.size).append('\n')
            for (w in ws) {
                sb.append("  tipo=").append(w.type)
                    .append(" capa=").append(w.layer)
                    .append(" activa=").append(w.isActive)
                    .append(" foco=").append(w.isFocused)
                    .append(" raíz=").append(try { w.root?.packageName ?: "null" } catch (e: Exception) { "error" })
                if (android.os.Build.VERSION.SDK_INT >= 24) sb.append(" título=").append(w.title)
                sb.append('\n')
            }
        } catch (e: Exception) {
            sb.append("ventanas: error ").append(e.message).append('\n')
        }
        return sb.toString()
    }

    private class Scan(val adShowing: Boolean, val button: AccessibilityNodeInfo?)

    /**
     * YouTube draws most of its UI with virtual views that Android's text search
     * can't see, so we walk the player ourselves. Only the player is walked
     * (~50 nodes), which keeps it cheap enough to do on every event.
     */
    private fun scanPlayer(root: AccessibilityNodeInfo): Scan {
        // Fast path: real skip-button views, if YouTube still uses them.
        for (id in SKIP_VIEW_IDS) {
            root.findAccessibilityNodeInfosByViewId("$YT_PACKAGE:id/$id")
                .firstOrNull { it.isVisibleToUser && !subtreeHasDigits(it) }
                ?.let { return Scan(true, it) }
        }

        val player = PLAYER_IDS.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId("$YT_PACKAGE:id/$id").firstOrNull { it.isVisibleToUser }
        } ?: root

        var ad = false
        val candidates = ArrayList<AccessibilityNodeInfo>()
        var budget = MAX_NODES
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > MAX_DEPTH || budget-- <= 0) return
            val vid = n.viewIdResourceName ?: ""
            if (n.isVisibleToUser && "subtitle" !in vid && "caption" !in vid) {
                val l = label(n)
                if (l != null && AD_MARKERS.any { it in l }) ad = true
                if (looksLikeSkip(n, l)) candidates.add(n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(player, 0)
        // A label match only counts while an ad is on screen; a "skip" view ID always counts.
        val usable = if (ad) candidates else candidates.filter {
            "skip" in (it.viewIdResourceName?.lowercase() ?: "")
        }
        val button = usable.firstOrNull { it.isClickable } ?: usable.firstOrNull()
        return Scan(ad, button)
    }

    private fun looksLikeSkip(n: AccessibilityNodeInfo, l: String?): Boolean {
        val id = n.viewIdResourceName?.lowercase() ?: ""
        if ("skip" in id && "preview" !in id) return !subtreeHasDigits(n)
        if (l == null || l.length > 40 || l.any { it.isDigit() }) return false
        return SKIP_WORDS.any { it in l }
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

        /** The video player; only this part of the screen is scanned. */
        private val PLAYER_IDS = listOf("watch_player", "player_view", "player_fragment_container")

        /** Lower-case fragments of player texts that mean "an ad is playing". */
        private val AD_MARKERS = listOf(
            "patrocinado", "anunciante", "sponsored", "advertiser",
            "sponsorisé", "gesponsert", "sponsorizzato",
        )

        /** Lower-case fragments of the skip button's label/description. */
        private val SKIP_WORDS = listOf(
            "saltar", "omitir", "skip", "ignorer", "überspringen", "salta ", "pular",
        )
    }
}
