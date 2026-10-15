package dev.ytskip

import android.content.Context

/** Counter + time of the last skip, shown in the app and the notification. */
object Prefs {
    private const val FILE = "ytskip"
    private const val KEY_SKIPS = "skips"
    private const val KEY_LAST = "last_skip"

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
}
