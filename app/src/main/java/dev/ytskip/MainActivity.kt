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
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            text = "Compartir diagnóstico"
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

                El botón ⏭ del menú flotante de accesibilidad sirve para SALTAR AHORA el anuncio (ya no apaga la app). Si un anuncio no se salta solo, púlsalo: además guarda un diagnóstico de ese momento.

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

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000)
        }
    }
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, settingsObserver
        )
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(settingsObserver)
        handler.removeCallbacks(ticker)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (SkipAdService.instance != null) StatusUi.showNotification(this)
        refresh()
    }

    private fun refresh() {
        val on = SkipAdService.instance != null || StatusUi.isEnabled(this)
        status.text = if (on) "✅ ACTIVADO" else "⛔ DESACTIVADO"
        status.setBackgroundColor(if (on) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40))
        toggle.text = if (on) "Desactivar" else "Activar (abre Accesibilidad)"

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        counter.text = "Versión $version\n" +
            "Anuncios saltados: ${Prefs.skips(this)}\n" +
            "Último anuncio saltado: ${ago(Prefs.lastSkip(this))}\n" +
            "Última vez que vio YouTube: ${ago(Prefs.youtubeSeen(this))}\n" +
            "Último anuncio detectado: ${ago(Prefs.adSeen(this))}"

        notifButton.visibility =
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) View.VISIBLE else View.GONE

    }

    private fun ago(t: Long): CharSequence = if (t == 0L) "nunca" else
        DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)

    /** Sends the saved screen structure as plain text (WhatsApp, Telegram, email, Claude…). */
    private fun shareDiagnostic() {
        val f = Diagnostics.file(this)
        if (!f.exists()) {
            android.widget.Toast.makeText(
                this,
                "Aún no hay datos: activa el saltador y abre un vídeo en YouTube",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
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
