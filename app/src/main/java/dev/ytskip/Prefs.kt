package dev.ytskip

import android.content.Context

/** Counter + time of the last skip, shown in the app and the notification. */
object Prefs {
    private const val FILE = "ytskip"
    private const val KEY_SKIPS = "skips"
    private const val KEY_LAST = "last_skip"
    private const val KEY_YT_SEEN = "yt_seen"
    private const val KEY_AD_SEEN = "ad_seen"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun skips(ctx: Context): Int = sp(ctx).getInt(KEY_SKIPS, 0)

    /** Wall-clock millis of the last skip, or 0 if none yet. */
    fun lastSkip(ctx: Context): Long = sp(ctx).getLong(KEY_LAST, 0L)

    fun recordSkip(ctx: Context) {
        val p = sp(ctx)
        p.edit()
            .putInt(KEY_SKIPS, p.getInt(KEY_SKIPS, 0) + 1)
            .putLong(KEY_LAST, System.currentTimeMillis())
            .apply()
    }

    fun markYoutubeSeen(ctx: Context) = sp(ctx).edit().putLong(KEY_YT_SEEN, System.currentTimeMillis()).apply()
    fun youtubeSeen(ctx: Context): Long = sp(ctx).getLong(KEY_YT_SEEN, 0L)

    private var lastAdWrite = 0L
    fun markAdSeen(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAdWrite < 2_000) return
        lastAdWrite = now
        sp(ctx).edit().putLong(KEY_AD_SEEN, now).apply()
    }
    fun adSeen(ctx: Context): Long = sp(ctx).getLong(KEY_AD_SEEN, 0L)
}
