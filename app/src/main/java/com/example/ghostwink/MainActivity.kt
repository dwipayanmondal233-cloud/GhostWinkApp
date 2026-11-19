package com.example.ghostwink

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
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

    private val bg = Color.parseColor("#05070D")
    private val panel = Color.parseColor("#0B1220")
    private val border = Color.parseColor("#12304A")
    private val cyan = Color.parseColor("#00E5FF")
    private val green = Color.parseColor("#39FF88")
    private val red = Color.parseColor("#FF2E63")
    private val amber = Color.parseColor("#FFC400")
    private val dim = Color.parseColor("#6F8AA3")
    private val textC = Color.parseColor("#C9D6E2")

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
            if (hasCamera()) startGhost() else statusT.text = "> CAMERA PERMISSION REQUIRED"
        }

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startGhost() {
        ContextCompat.startForegroundService(this, Intent(this, GhostService::class.java))
    }

    private fun mono(t: TextView, size: Float, color: Int, bold: Boolean = false) {
        t.typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        t.textSize = size
        t.setTextColor(color)
    }

    private fun label(t: String): TextView {
        val v = TextView(this)
        v.text = t.uppercase()
        mono(v, 11f, dim)
        v.letterSpacing = 0.15f
        v.setPadding(0, dp(14), 0, dp(4))
        return v
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(6), dp(14), dp(14))
        val b = GradientDrawable()
        b.cornerRadius = dp(10).toFloat()
        b.setColor(panel)
        b.setStroke(dp(1), border)
        c.background = b
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(14)
        c.layoutParams = lp
        return c
    }

    private fun neonBtn(t: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = true
        b.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        b.textSize = 14f
        b.letterSpacing = 0.1f
        b.setTextColor(color)
        b.stateListAnimator = null
        val d = GradientDrawable()
        d.cornerRadius = dp(8).toFloat()
        d.setColor(Color.parseColor("#08111C"))
        d.setStroke(dp(2), color)
        b.background = d
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        )
        lp.topMargin = dp(10)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun tintSeek(s: SeekBar, color: Int) {
        s.progressTintList = ColorStateList.valueOf(color)
        s.thumbTintList = ColorStateList.valueOf(color)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gw", MODE_PRIVATE)
        th = prefs.getFloat("th", 0.5f)
        ds = prefs.getInt("ds", 800)
        lg = prefs.getInt("lg", 1200)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(40), dp(18), dp(28))

        val title = TextView(this)
        title.text = "GHOSTWINK"
        mono(title, 30f, cyan, true)
        title.letterSpacing = 0.25f
        col.addView(title)

        val sub = TextView(this)
        sub.text = "// hands-free control. blink to execute."
        mono(sub, 12f, dim)
        col.addView(sub)

        col.addView(neonBtn("\u25B6  START", green) {
            if (hasCamera()) startGhost() else {
                val perms = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                permLauncher.launch(perms.toTypedArray())
            }
        })
        col.addView(neonBtn("\u25A0  STOP", red) {
            stopService(Intent(this, GhostService::class.java))
        })
        col.addView(neonBtn("\u25C9  ALLOW STATUS DOT", cyan) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        col.addView(neonBtn("\u2699  ENABLE ACTIONS (ACCESSIBILITY)", amber) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

            col.addView(neonBtn("\u25B6  PRACTICE MODE", Color.WHITE) {
            startActivity(Intent(this, PracticeActivity::class.java))
        })

        // ---- Live terminal card ----
        val term = card()
        faceT = label("")
        faceT.text = "STANDBY"
        term.addView(faceT)
        statusT = TextView(this)
        mono(statusT, 13f, amber)
        term.addView(statusT)

        term.addView(label("output"))
        outT = TextView(this)
        mono(outT, 26f, Color.WHITE, true)
        outT.minHeight = dp(40)
        term.addView(outT)

        term.addView(label("signal"))
        seqT = TextView(this)
        mono(seqT, 28f, green, true)
        seqT.letterSpacing = 0.2f
        seqT.minHeight = dp(40)
        term.addView(seqT)

        evtT = TextView(this)
        mono(evtT, 15f, amber)
        evtT.minHeight = dp(26)
        term.addView(evtT)

        term.addView(label("eye closure"))
        eyeBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        eyeBar.max = 100
        eyeBar.progressTintList = ColorStateList.valueOf(cyan)
        eyeBar.progressBackgroundTintList = ColorStateList.valueOf(border)
        term.addView(eyeBar)
        col.addView(term)

        // ---- Voice card ----
        val voice = card()
        voice.addView(label("// voice assistant"))
        lateinit var voiceBtn: Button
        lateinit var lettersBtn: Button
        fun paintVoice() {
            voiceBtn.text = if (prefs.getBoolean("voice", true)) "VOICE: ON" else "VOICE: OFF"
            lettersBtn.text = if (prefs.getBoolean("sayletters", false)) "SPEAK LETTERS: ON" else "SPEAK LETTERS: OFF"
        }
        voiceBtn = neonBtn("", green) {
            prefs.edit().putBoolean("voice", !prefs.getBoolean("voice", true)).apply()
            paintVoice()
            GhostService.instance?.say("Voice on")
        }
        lettersBtn = neonBtn("", cyan) {
            prefs.edit().putBoolean("sayletters", !prefs.getBoolean("sayletters", false)).apply()
            paintVoice()
        }
        voice.addView(voiceBtn)
        voice.addView(lettersBtn)
        val rateL = label("")
        rateL.text = "VOICE SPEED: ${prefs.getInt("vrate", 100)}%"
        voice.addView(rateL)
        val rateBar = SeekBar(this)
        rateBar.max = 100
        rateBar.progress = (prefs.getInt("vrate", 100) - 50).coerceIn(0, 100)
        tintSeek(rateBar, amber)
        rateBar.setOnSeekBarChangeListener(listener { p ->
            prefs.edit().putInt("vrate", p + 50).apply()
            rateL.text = "VOICE SPEED: ${p + 50}%"
        })
        voice.addView(rateBar)
        voice.addView(neonBtn("TEST VOICE", amber) {
            GhostService.instance?.say("Hello. I am GhostWink.")
                ?: run { statusT.text = "> START GHOSTWINK FIRST" }
        })
        paintVoice()
        col.addView(voice)

        // ---- Cursor card ----
        val cur = card()
        cur.addView(label("// monkey cursor"))
        val gainL = label("")
        fun gainText() {
            gainL.text = "CURSOR SPEED: ${prefs.getInt("cgain", 6)}"
        }
        gainText()
        cur.addView(gainL)
        val gainBar = SeekBar(this)
        gainBar.max = 14
        gainBar.progress = (prefs.getInt("cgain", 6) - 2).coerceIn(0, 14)
        tintSeek(gainBar, green)
        gainBar.setOnSeekBarChangeListener(listener { p ->
            prefs.edit().putInt("cgain", p + 2).apply()
            gainText()
        })
        cur.addView(gainBar)
        lateinit var invBtn: Button
        fun paintInv() {
            invBtn.text = if (prefs.getBoolean("cinvert", false)) "FLIP LEFT/RIGHT: ON" else "FLIP LEFT/RIGHT: OFF"
        }
        invBtn = neonBtn("", amber) {
            prefs.edit().putBoolean("cinvert", !prefs.getBoolean("cinvert", false)).apply()
            paintInv()
        }
        cur.addView(invBtn)
        paintInv()
        val cskins = listOf(
            "🐵" to "MONKEY", "🐱" to "CAT", "👻" to "GHOST", "🤖" to "ROBOT",
            "🦊" to "FOX", "🐸" to "FROG", "dot" to "NEON DOT"
        )
        lateinit var skinBtn: Button
        fun paintSkin() {
            val now = prefs.getString("cskin", "🐵")
            val name = cskins.firstOrNull { it.first == now }?.second ?: "MONKEY"
            skinBtn.text = "CURSOR: $name  (TAP TO CHANGE)"
        }
        skinBtn = neonBtn("", cyan) {
            val now = prefs.getString("cskin", "🐵")
            val i = cskins.indexOfFirst { it.first == now }
            prefs.edit().putString("cskin", cskins[(i + 1) % cskins.size].first).apply()
            GhostService.instance?.applySkin()
            paintSkin()
        }
        cur.addView(skinBtn)
        paintSkin()

        lateinit var smileBtn: Button
        fun paintSmile() {
            smileBtn.text = if (prefs.getBoolean("smile", true)) "SMILE = LIKE: ON" else "SMILE = LIKE: OFF"
        }
        smileBtn = neonBtn("", green) {
            prefs.edit().putBoolean("smile", !prefs.getBoolean("smile", true)).apply()
            paintSmile()
        }
        cur.addView(smileBtn)
        paintSmile()

        col.addView(cur)

        // ---- Tuning card ----
        val tune = card()
        tune.addView(label("// tuning"))

        thL = label("")
        tune.addView(thL)
        val thBar = SeekBar(this)
        thBar.max = 80
        thBar.progress = ((th * 100).toInt() - 10).coerceIn(0, 80)
        tintSeek(thBar, cyan)
        thBar.setOnSeekBarChangeListener(listener { p ->
            th = (p + 10) / 100f
            prefs.edit().putFloat("th", th).apply()
            GhostService.instance?.reloadSettings()
            labels()
        })
        tune.addView(thBar)

        dsL = label("")
        tune.addView(dsL)
        val dsBar = SeekBar(this)
        dsBar.max = 1000
        dsBar.progress = (ds - 400).coerceIn(0, 1000)
        tintSeek(dsBar, green)
        dsBar.setOnSeekBarChangeListener(listener { p ->
            ds = p + 400
            prefs.edit().putInt("ds", ds).apply()
            GhostService.instance?.reloadSettings()
            labels()
        })
        tune.addView(dsBar)

        lgL = label("")
        tune.addView(lgL)
        val lgBar = SeekBar(this)
        lgBar.max = 1700
        lgBar.progress = (lg - 800).coerceIn(0, 1700)
        tintSeek(lgBar, amber)
        lgBar.setOnSeekBarChangeListener(listener { p ->
            lg = p + 800
            prefs.edit().putInt("lg", lg).apply()
            GhostService.instance?.reloadSettings()
            labels()
        })
        tune.addView(lgBar)

        tune.addView(neonBtn("\u2316  CALIBRATE", cyan) { GhostService.instance?.calibrate() })
        tune.addView(neonBtn("\u27F2  RESET TEXT", dim) { GhostService.instance?.resetText() })
        col.addView(tune)

        val foot = TextView(this)
        foot.text = "practice: SOS  \u00B7 \u00B7 \u00B7  \u2212 \u2212 \u2212  \u00B7 \u00B7 \u00B7"
        mono(foot, 11f, dim)
        foot.gravity = Gravity.CENTER
        foot.setPadding(0, dp(18), 0, 0)
        col.addView(foot)

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
        thL.text = "BLINK THRESHOLD: ${"%.2f".format(th)}  (LOWER = EASIER)"
        dsL.text = "DASH STARTS AT: $ds MS"
        lgL.text = "LETTER GAP: $lg MS"
    }

    private val poll = object : Runnable {
        override fun run() {
            val s = GhostService.instance
            if (s == null) {
                faceT.text = "STANDBY \u2014 TAP START"
                faceT.setTextColor(dim)
                eyeBar.progress = 0
            } else {
                faceT.text = s.faceText.uppercase() + "  |  " + (if (s.cursorMode) "CURSOR" else if (s.controlMode) "CONTROL" else "TYPE")
                faceT.setTextColor(if (s.faceText.startsWith("FACE")) green else amber)
                statusT.text = s.status
                outT.text = s.text + if (s.text.trim() == "SOS") "  \u2714" else ""
                seqT.text = s.seq.replace('.', '\u00B7').replace('-', '\u2212')
                evtT.text = s.evt
                eyeBar.progress = (s.eye * 100).toInt()
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