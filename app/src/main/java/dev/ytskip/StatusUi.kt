package dev.ytskip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.TextUtils
import android.widget.Toast

/** Everything that tells the user whether the skipper is ON or OFF. */
object StatusUi {
    private const val CHANNEL = "status"
    private const val NOTIF_ID = 1

    /** True if the accessibility service is switched on in system settings. */
    fun isEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, SkipAdService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            .any { it.equals(me, ignoreCase = true) }
    }

    /** Called by the service when it starts or stops. */
    fun onStateChanged(ctx: Context, on: Boolean) {
        Toast.makeText(
            ctx,
            if (on) "YT Ad Skipper: ACTIVADO ✅" else "YT Ad Skipper: DESACTIVADO ⛔",
            Toast.LENGTH_SHORT
        ).show()
        if (on) showNotification(ctx) else nm(ctx).cancel(NOTIF_ID)
        refreshTile(ctx)
    }

    /** Persistent notification while the skipper is running (also refreshes the counter). */
    fun showNotification(ctx: Context) {
        val nm = nm(ctx)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Estado del saltador", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = builder(ctx)
            .setSmallIcon(R.drawable.ic_skip)
            .setContentTitle("Saltador de anuncios ACTIVADO")
            .setContentText("Anuncios saltados: ${Prefs.skips(ctx)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    fun refreshTile(ctx: Context) {
        try {
            TileService.requestListeningState(ctx, ComponentName(ctx, SkipTileService::class.java))
        } catch (_: Exception) {
            // Tile not added to Quick Settings: nothing to refresh.
        }
    }

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Suppress("DEPRECATION")
    private fun builder(ctx: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
}
