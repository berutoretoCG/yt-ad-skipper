package dev.ytskip

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One screen: big ON/OFF status, counter, and buttons to set things up. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var toggle: Button
    private lateinit var notifButton: Button
    private lateinit var diagButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        status = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }
        counter = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        }
        toggle = Button(this).apply {
            setOnClickListener {
                val running = SkipAdService.instance
                if (running != null) {
                    running.disableSelf()
                    status.postDelayed({ refresh() }, 400)
                } else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        notifButton = Button(this).apply {
            text = "Permitir notificación de estado"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
            }
        }
        val tileButton = Button(this).apply {
            text = "Añadir botón a Ajustes rápidos"
            if (Build.VERSION.SDK_INT >= 33) {
                setOnClickListener { requestTile() }
            } else {
                visibility = View.GONE
            }
        }
        diagButton = Button(this).apply {
            text = "Compartir diagnóstico del último anuncio"
            setOnClickListener { shareDiagnostic() }
        }
        val help = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = """
                Cómo saber si está activado:
                • Esta pantalla (verde = activado).
                • La notificación fija "Saltador de anuncios ACTIVADO".
                • El botón "Saltar anuncios" en Ajustes rápidos: iluminado = activado. Tócalo para apagarlo o encenderlo.
                • Al encenderlo o apagarlo sale un aviso en pantalla.

                Para activarlo: Ajustes de Accesibilidad → Apps instaladas / descargadas → YT Ad Skipper.
                Si el interruptor sale gris: Ajustes → Aplicaciones → YT Ad Skipper → ⋮ → Permitir ajustes restringidos.

                Si se apaga solo (Xiaomi, Samsung, Huawei…):
                • Ajustes → Aplicaciones → YT Ad Skipper → Ahorro de batería → Sin restricciones.
                • En Xiaomi, activa también "Inicio automático".
            """.trimIndent()
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(status)
            addView(counter)
            addView(toggle)
            addView(notifButton)
            addView(tileButton)
            addView(diagButton)
            addView(help)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (SkipAdService.instance != null) StatusUi.showNotification(this)
        refresh()
    }

    private fun refresh() {
        val on = StatusUi.isEnabled(this)
        status.text = if (on) "✅ ACTIVADO" else "⛔ DESACTIVADO"
        status.setBackgroundColor(if (on) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40))
        toggle.text = if (on) "Desactivar" else "Activar (abre Accesibilidad)"

        val last = Prefs.lastSkip(this)
        val lastText = if (last == 0L) "nunca" else
            DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        counter.text = "Anuncios saltados: ${Prefs.skips(this)}\nÚltimo: $lastText"

        notifButton.visibility =
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) View.VISIBLE else View.GONE

        diagButton.visibility = if (Diagnostics.file(this).exists()) View.VISIBLE else View.GONE
    }

    /** Sends the saved screen structure as plain text (WhatsApp, Telegram, email, Claude…). */
    private fun shareDiagnostic() {
        val f = Diagnostics.file(this)
        if (!f.exists()) return
        val text = f.readText().take(90_000)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico YT Ad Skipper")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Compartir diagnóstico"))
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val sbm = getSystemService(StatusBarManager::class.java) ?: return
        sbm.requestAddTileService(
            ComponentName(this, SkipTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_skip),
            mainExecutor
        ) { _ -> }
    }
}
