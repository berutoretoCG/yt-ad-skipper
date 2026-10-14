package dev.ytskip

import android.content.Context

/** Tiny counter so the main screen can show how many ads were skipped. */
object Prefs {
    private const val FILE = "ytskip"
    private const val KEY_SKIPS = "skips"

    fun skips(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_SKIPS, 0)

    fun incrementSkips(ctx: Context) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_SKIPS, sp.getInt(KEY_SKIPS, 0) + 1).apply()
    }
}
