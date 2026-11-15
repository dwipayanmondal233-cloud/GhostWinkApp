package com.example.ghostwink

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var faceT: TextView
    private lateinit var statusT: TextView
    private lateinit var outT: TextView
    private lateinit var seqT: TextView
    private lateinit var evtT: TextView
    private lateinit var thL: TextView
    private lateinit var dsL: TextView
    private lateinit var lgL: TextView
    private lateinit var eyeBar: ProgressBar

    private var th = 0.5f
    private var ds = 800
    private var lg = 1200

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCamera()) startGhost() else statusT.text = "Camera permission is needed 😢"
        }

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startGhost() {
        ContextCompat.startForegroundService(this, Intent(this, GhostService::class.java))
    }

    private fun label(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.parseColor("#B8B0D8"))
        v.textSize = 13f
        v.setPadding(0, dp(10), 0, 0)
        return v
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gw", MODE_PRIVATE)
        th = prefs.getFloat("th", 0.5f)
        ds = prefs.getInt("ds", 800)
        lg = prefs.getInt("lg", 1200)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0F0B1E"))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(32), dp(16), dp(24))

        val title = TextView(this)
        title.text = "👻 GhostWink"
        title.textSize = 28f
        title.setTextColor(Color.parseColor("#B69CFF"))
        col.addView(title)

        col.addView(btn("▶️ Start GhostWink") {
            if (hasCamera()) startGhost() else {
                val perms = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                permLauncher.launch(perms.toTypedArray())
            }
        })
        col.addView(btn("⏹ Stop") {
            stopService(Intent(this, GhostService::class.java))
        })
        col.addView(btn("🟢 Allow status dot (Display over other apps)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        faceT = label("Stopped 😴")
        col.addView(faceT)
        statusT = TextView(this)
        statusT.setTextColor(Color.parseColor("#FFD166"))
        col.addView(statusT)

        col.addView(label("Typed text (practice: SOS)"))
        outT = TextView(this)
        outT.textSize = 28f
        outT.setTextColor(Color.WHITE)
        outT.minHeight = dp(44)
        col.addView(outT)
        seqT = TextView(this)
        seqT.textSize = 30f
        seqT.setTextColor(Color.parseColor("#7CFFC4"))
        seqT.minHeight = dp(44)
        col.addView(seqT)
        evtT = TextView(this)
        evtT.textSize = 18f
        evtT.setTextColor(Color.parseColor("#FFD166"))
        evtT.minHeight = dp(30)
        col.addView(evtT)

        col.addView(label("Eyes closed"))
        eyeBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        eyeBar.max = 100
        col.addView(eyeBar)

        thL = label("")
        col.addView(thL)
        val thBar = SeekBar(this)
        thBar.max = 80
        thBar.progress = ((th * 100).toInt() - 10).coerceIn(0, 80)
        thBar.setOnSeekBarChangeListener(listener { p ->
            th = (p + 10) / 100f
            prefs.edit().putFloat("th", th).apply()
            GhostService.instance?.reloadSettings()
            labels()
        })
        col.addView(thBar)

        dsL = label("")
        col.addView(dsL)
        val dsBar = SeekBar(this)
        dsBar.max = 1000
        dsBar.progress = (ds - 400).coerceIn(0, 1000)
        dsBar.setOnSeekBarChangeListener(listener { p ->
            ds = p + 400
            prefs.edit().putInt("ds", ds).apply()
            GhostService.instance?.reloadSettings()
            labels()
        })
        col.addView(dsBar)

        lgL = label("")
        col.addView(lgL)
        val lgBar = SeekBar(this)
        lgBar.max = 1700
        lgBar.progress = (lg - 800).coerceIn(0, 1700)
        lgBar.setOnSeekBarChangeListener(listener { p ->
            lg = p + 800
            prefs.edit().putInt("lg", lg).apply()
            GhostService.instance?.reloadSettings()
            labels()
        })
        col.addView(lgBar)

        col.addView(btn("🎯 Calibrate") { GhostService.instance?.calibrate() })
        col.addView(btn("Reset text") { GhostService.instance?.resetText() })

        labels()
        scroll.addView(col)
        setContentView(scroll)
        handler.post(poll)
    }

    private fun listener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) onChange(p)
        }

        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun labels() {
        thL.text = "Blink sensitivity: ${"%.2f".format(th)} (lower = easier)"
        dsL.text = "Dash starts at: $ds ms"
        lgL.text = "Pause to finish a letter: $lg ms"
    }

    private val poll = object : Runnable {
        override fun run() {
            val s = GhostService.instance
            if (s == null) {
                faceT.text = "Stopped 😴 Tap Start"
                eyeBar.progress = 0
            } else {
                faceT.text = s.faceText
                statusT.text = s.status
                outT.text = s.text + if (s.text.trim() == "SOS") "  ✅" else ""
                seqT.text = s.seq.replace('.', '·').replace('-', '−')
                evtT.text = s.evt
                eyeBar.progress = (s.eye * 100).toInt()
                // keep sliders in sync after calibration
                val newTh = prefs.getFloat("th", th)
                if (newTh != th) {
                    th = newTh
                    labels()
                }
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}