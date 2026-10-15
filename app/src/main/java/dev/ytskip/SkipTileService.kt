package dev.ytskip

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings button "Saltar anuncios".
 * Lit = ON, grey = OFF. Tap when ON turns it off; tap when OFF opens
 * Accessibility settings (Android doesn't let apps switch it on by themselves).
 */
class SkipTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render(StatusUi.isEnabled(this))
    }

    override fun onClick() {
        super.onClick()
        val running = SkipAdService.instance
        if (running != null) {
            running.disableSelf()
            render(false)
        } else {
            openAccessibilitySettings()
        }
    }

    private fun render(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "Activado" else "Desactivado"
        tile.updateTile()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startLegacy(intent)
        }
    }

    @Suppress("DEPRECATION")
    private fun startLegacy(intent: Intent) = startActivityAndCollapse(intent)
}
