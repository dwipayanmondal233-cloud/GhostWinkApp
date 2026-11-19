package com.example.ghostwink

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class PracticeActivity : ComponentActivity() {

    private class Step(val title: String, val how: String, val kind: Int, val target: String = "")

    // kind: 0 letter, 1 word, 2 control mode, 3 command, 4 cursor mode, 5 click target, 6 back to type
    private val steps = listOf(
        Step("Type E", "One quick blink, then wait.", 0, "E"),
        Step("Type T", "One long blink. Keep your eyes shut about one second, then wait.", 0, "T"),
        Step("Type S", "Three quick blinks, then wait.", 0, "S"),
        Step("Type O", "Three long blinks, then wait.", 0, "O"),
        Step("Type SOS", "S, then O, then S. Pause between letters.", 1, "SOS"),
        Step("Enter control mode", "Close your eyes for three seconds.", 2),
        Step("Scroll down", "One quick blink.", 3, "SCROLL DOWN"),
        Step("Go back", "Stick your tongue out for half a second.", 3, "BACK"),
        Step("Start cursor mode", "Raise your eyebrows. The monkey appears.", 4),
        Step("Click the target", "Move your nose until the monkey stands on the target. Then one deliberate blink (about half a second).", 5),
        Step("Back to type mode", "Raise your eyebrows, then close your eyes for three seconds.", 6)
    )

    private val bg = Color.parseColor("#05070D")
    private val panel = Color.parseColor("#0B1220")
    private val border = Color.parseColor("#12304A")
    private val cyan = Color.parseColor("#00E5FF")
    private val green = Color.parseColor("#39FF88")
    private val red = Color.parseColor("#FF2E63")
    private val amber = Color.parseColor("#FFC400")
    private val dim = Color.parseColor("#6F8AA3")

    private val handler = Handler(Looper.getMainLooper())
    private var idx = 0
    private var started = false
    private var done = false
    private var baseLetter = 0
    private var baseCmd = 0

    private lateinit var stepT: TextView
    private lateinit var titleT: TextView
    private lateinit var howT: TextView
    private lateinit var hintT: TextView
    private lateinit var modeT: TextView
    private lateinit var outT: TextView
    private lateinit var seqT: TextView
    private lateinit var evtT: TextView
    private lateinit var eyeBar: ProgressBar
    private lateinit var prog: ProgressBar
    private lateinit var target: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mono(t: TextView, size: Float, color: Int, bold: Boolean = false) {
        t.typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        t.textSize = size
        t.setTextColor(color)
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(10), dp(14), dp(14))
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

    private fun neonBtn(t: String, color: Int, h: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        b.textSize = 14f
        b.setTextColor(color)
        b.stateListAnimator = null
        val d = GradientDrawable()
        d.cornerRadius = dp(8).toFloat()
        d.setColor(Color.parseColor("#08111C"))
        d.setStroke(dp(2), color)
        b.background = d
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
        lp.topMargin = dp(10)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun small(t: String): TextView {
        val v = TextView(this)
        v.text = t
        mono(v, 11f, dim)
        v.letterSpacing = 0.15f
        v.setPadding(0, dp(8), 0, dp(2))
        return v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(40), dp(18), dp(28))

        val head = TextView(this)
        head.text = "PRACTICE MODE"
        mono(head, 24f, cyan, true)
        head.letterSpacing = 0.2f
        col.addView(head)
        val sub = TextView(this)
        sub.text = "// nothing real is triggered here. safe to experiment."
        mono(sub, 12f, dim)
        col.addView(sub)

        val lesson = card()
        stepT = small("")
        lesson.addView(stepT)
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        prog.max = steps.size
        prog.progressTintList = ColorStateList.valueOf(green)
        prog.progressBackgroundTintList = ColorStateList.valueOf(border)
        lesson.addView(prog)
        titleT = TextView(this)
        mono(titleT, 22f, Color.WHITE, true)
        titleT.setPadding(0, dp(12), 0, dp(4))
        lesson.addView(titleT)
        howT = TextView(this)
        mono(howT, 14f, Color.parseColor("#C9D6E2"))
        lesson.addView(howT)
        hintT = TextView(this)
        mono(hintT, 13f, amber)
        hintT.setPadding(0, dp(10), 0, 0)
        lesson.addView(hintT)
        col.addView(lesson)

        target = neonBtn("TARGET", red, dp(96)) {}
        target.textSize = 20f
        target.visibility = View.GONE
        col.addView(target)

        val live = card()
        live.addView(small("live"))
        modeT = TextView(this)
        mono(modeT, 13f, green, true)
        live.addView(modeT)
        live.addView(small("output"))
        outT = TextView(this)
        mono(outT, 24f, Color.WHITE, true)
        outT.minHeight = dp(36)
        live.addView(outT)
        live.addView(small("signal"))
        seqT = TextView(this)
        mono(seqT, 26f, green, true)
        seqT.letterSpacing = 0.2f
        seqT.minHeight = dp(36)
        live.addView(seqT)
        evtT = TextView(this)
        mono(evtT, 14f, amber)
        evtT.minHeight = dp(24)
        live.addView(evtT)
        live.addView(small("eye closure"))
        eyeBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        eyeBar.max = 100
        eyeBar.progressTintList = ColorStateList.valueOf(cyan)
        eyeBar.progressBackgroundTintList = ColorStateList.valueOf(border)
        live.addView(eyeBar)
        col.addView(live)

        col.addView(neonBtn("SKIP STEP", amber, dp(48)) { if (!done) next() })
        col.addView(neonBtn("EXIT PRACTICE", dim, dp(48)) { finish() })

        scroll.addView(col)
        setContentView(scroll)
        handler.post(poll)
    }

    private fun startStep() {
        val s = GhostService.instance ?: return
        s.practice = true
        s.practiceClick = { x, y -> onMonkeyClick(x, y) }
        val st = steps[idx]
        if (st.kind <= 1 && (s.controlMode || s.cursorMode)) s.resetModes()
        if (st.kind == 1) s.resetText()
        baseLetter = s.letterSerial
        baseCmd = s.cmdSerial
        stepT.text = "STEP ${idx + 1} / ${steps.size}"
        prog.progress = idx
        titleT.text = st.title.uppercase()
        howT.text = st.how
        hintT.text = ""
        target.visibility = if (st.kind == 5) View.VISIBLE else View.GONE
        s.say(st.title + ". " + st.how)
    }

    private fun next() {
        idx++
        if (idx >= steps.size) complete() else startStep()
    }

    private fun complete() {
        done = true
        prog.progress = steps.size
        stepT.text = "COMPLETE"
        titleT.text = "ALL SYSTEMS GO"
        howT.text = "You finished practice. You can run it again any time."
        hintT.text = ""
        target.visibility = View.GONE
        GhostService.instance?.let {
            it.say("Practice complete")
            it.practice = false
            it.practiceClick = null
        }
    }

    private fun onMonkeyClick(x: Float, y: Float) {
        if (done || idx >= steps.size || steps[idx].kind != 5) return
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val hit = x >= loc[0] && x <= loc[0] + target.width &&
            y >= loc[1] && y <= loc[1] + target.height
        if (hit) next() else hintT.text = "MISSED. Aim the monkey's feet at the target."
    }

    private fun check(s: GhostService) {
        val st = steps[idx]
        when (st.kind) {
            0 -> if (s.letterSerial != baseLetter) {
                baseLetter = s.letterSerial
                if (s.lastLetter == st.target) next()
                else hintT.text = "GOT ${s.lastLetter}. NEED ${st.target}. TRY AGAIN."
            }
            1 -> {
                val t = s.text.trim()
                if (t == st.target) next()
                else if (t.length >= st.target.length) {
                    hintT.text = "GOT $t. RESETTING, TRY AGAIN."
                    s.resetText()
                }
            }
            2 -> if (s.controlMode && !s.cursorMode) next()
            3 -> if (s.cmdSerial != baseCmd) {
                baseCmd = s.cmdSerial
                if (s.lastCmd == st.target) next()
                else hintT.text = "GOT ${s.lastCmd}. NEED ${st.target}. TRY AGAIN."
            }
            4 -> if (s.cursorMode) next()
            6 -> if (!s.controlMode && !s.cursorMode) next()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val s = GhostService.instance
            if (s == null) {
                started = false
                if (!done) hintT.text = "> START GHOSTWINK ON THE MAIN SCREEN FIRST"
            } else {
                if (!started && !done) {
                    started = true
                    startStep()
                }
                modeT.text = (s.faceText + "  |  " +
                    (if (s.cursorMode) "CURSOR" else if (s.controlMode) "CONTROL" else "TYPE")).uppercase()
                outT.text = s.text
                seqT.text = s.seq.replace('.', '\u00B7').replace('-', '\u2212')
                evtT.text = s.evt
                eyeBar.progress = (s.eye * 100).toInt()
                if (started && !done) check(s)
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        GhostService.instance?.let {
            it.practice = false
            it.practiceClick = null
            it.resetModes()
        }
        super.onDestroy()
    }
}