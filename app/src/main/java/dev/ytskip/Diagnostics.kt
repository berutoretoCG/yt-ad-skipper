package dev.ytskip

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the structure of YouTube's screen while an ad is showing (IDs, texts,
 * descriptions, positions — no video content), so the user can share it and the
 * skip button can be identified exactly when YouTube changes its design.
 */
object Diagnostics {
    private const val FILE = "diagnostico.txt"
    private const val MAX_NODES = 2_500

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun save(ctx: Context, root: AccessibilityNodeInfo, found: AccessibilityNodeInfo?, adShowing: Boolean) {
        try {
            val sb = StringBuilder()
            sb.append("YT Ad Skipper ").append(appVersion(ctx))
                .append(" | YouTube ").append(youtubeVersion(ctx))
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append('\n')
            sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .append(" | anuncio detectado: ").append(if (adShowing) "SÍ" else "NO")
                .append(" | botón encontrado: ").append(if (found != null) describe(found) else "NO")
                .append("\n\n")
            var budget = MAX_NODES
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || budget-- <= 0) return
                if (n.isVisibleToUser) {
                    sb.append("  ".repeat(depth.coerceAtMost(30))).append(describe(n)).append('\n')
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            walk(root, 0)
            file(ctx).writeText(sb.toString())
        } catch (_: Exception) {
            // Diagnostics must never break skipping.
        }
    }

    /** When the service hears YouTube but can't read its screen. */
    fun saveNoScreen(ctx: Context, report: String) {
        try {
            val head = "YT Ad Skipper " + appVersion(ctx) + " | YouTube " + youtubeVersion(ctx) +
                " | Android " + Build.VERSION.RELEASE + " | " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                " | NO SE PUDO LEER LA PANTALLA DE YOUTUBE\n\n"
            file(ctx).writeText(head + report)
        } catch (_: Exception) { }
    }

    private fun describe(n: AccessibilityNodeInfo): String {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val parts = mutableListOf(n.className?.toString()?.substringAfterLast('.') ?: "?")
        n.viewIdResourceName?.let { parts.add("id=" + it.substringAfter(":id/")) }
        n.text?.takeIf { it.isNotBlank() }?.let { parts.add("text=\"" + it.toString().take(60) + "\"") }
        n.contentDescription?.takeIf { it.isNotBlank() }?.let { parts.add("desc=\"" + it.toString().take(60) + "\"") }
        if (n.isClickable) parts.add("CLICK")
        parts.add("[${r.left},${r.top}-${r.right},${r.bottom}]")
        return parts.joinToString(" ")
    }

    private fun appVersion(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun youtubeVersion(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(SkipAdService.YT_PACKAGE, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
