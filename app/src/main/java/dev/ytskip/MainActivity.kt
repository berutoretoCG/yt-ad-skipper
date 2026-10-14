package dev.ytskip

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Single screen: shows whether the service is on and opens Accessibility settings. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var counter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER }
        counter = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad) }
        val button = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val help = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "In Accessibility → Installed apps (or Downloaded apps), choose " +
                "\"YT Ad Skipper\" and turn it on. Then just use YouTube normally."
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(status); addView(counter); addView(button); addView(help)
        })
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "✅ Ad skipper is ON" else "⛔ Ad skipper is OFF"
        counter.text = "Ads skipped: ${Prefs.skips(this)}"
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, SkipAdService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            .any { it.equals(me, ignoreCase = true) }
    }
}
