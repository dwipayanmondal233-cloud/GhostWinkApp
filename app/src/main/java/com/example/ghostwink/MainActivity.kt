package com.example.ghostwink

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private val morse = mapOf(
        ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D", "." to "E",
        "..-." to "F", "--." to "G", "...." to "H", ".." to "I", ".---" to "J",
        "-.-" to "K", ".-.." to "L", "--" to "M", "-." to "N", "---" to "O",
        ".--." to "P", "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T",
        "..-" to "U", "...-" to "V", ".--" to "W", "-..-" to "X", "-.--" to "Y",
        "--.." to "Z", ".----" to "1", "..---" to "2", "...--" to "3",
        "....-" to "4", ".....-" to "5", "-...." to "6", "--..." to "7",
        "---.." to "8", "----." to "9", "-----" to "0"
    )

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: FaceLandmarker? = null
    private var tone: ToneGenerator? = null

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var faceText: TextView
    private lateinit var eyeBar: ProgressBar
    private lateinit var outText: TextView
    private lateinit var seqText: TextView
    private lateinit var evtText: TextView
    private lateinit var thLabel: TextView
    private lateinit var dsLabel: TextView
    private lateinit var lgLabel: TextView
    private lateinit var thBar: SeekBar
    private lateinit var calBtn: Button

    private var th = 0.5f
    private var ds = 800
    private var lg = 1200

    private var text = ""
    private var seq = ""
    private var lastSym = 0L
    private var spaceAdded = false
    private var closed = false
    private var closedAt = 0L
    private var browUp = false
    private var browLock = 0L

    private var calibrating = false
    private val samples = mutableListOf<Float>()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else status.text = "Camera permission is needed 😢"
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("gw", MODE_PRIVATE)
        th = prefs.getFloat("th", 0.5f)
        ds = prefs.getInt("ds", 800)
        lg = prefs.getInt("lg", 1200)
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        } catch (e: Exception) {
        }
        buildUi()
        handler.post(gapRunnable)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun label(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.parseColor("#B8B0D8"))
        v.textSize = 13f
        v.setPadding(0, dp(10), 0, 0)
        return v
    }

    private fun buildUi() {
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

        previewView = PreviewView(this)
        col.addView(previewView, LinearLayout.LayoutParams(dp(240), dp(240)))

        faceText = label("Starting...")
        col.addView(faceText)
        status = TextView(this)
        status.setTextColor(Color.parseColor("#FFD166"))
        col.addView(status)

        col.addView(label("Typed text (practice: SOS)"))
        outText = TextView(this)
        outText.textSize = 28f
        outText.setTextColor(Color.WHITE)
        outText.minHeight = dp(44)
        col.addView(outText)

        seqText = TextView(this)
        seqText.textSize = 30f
        seqText.setTextColor(Color.parseColor("#7CFFC4"))
        seqText.minHeight = dp(44)
        col.addView(seqText)

        evtText = TextView(this)
        evtText.textSize = 18f
        evtText.setTextColor(Color.parseColor("#FFD166"))
        evtText.minHeight = dp(30)
        col.addView(evtText)

        col.addView(label("Eyes closed"))
        eyeBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        eyeBar.max = 100
        col.addView(eyeBar)

        thLabel = label("")
        col.addView(thLabel)
        thBar = SeekBar(this)
        thBar.max = 80
        thBar.progress = ((th * 100).toInt() - 10).coerceIn(0, 80)
        thBar.setOnSeekBarChangeListener(listener { p ->
            th = (p + 10) / 100f
            prefs.edit().putFloat("th", th).apply()
            refreshLabels()
        })
        col.addView(thBar)

        dsLabel = label("")
        col.addView(dsLabel)
        val dsBar = SeekBar(this)
        dsBar.max = 1000
        dsBar.progress = (ds - 400).coerceIn(0, 1000)
        dsBar.setOnSeekBarChangeListener(listener { p ->
            ds = p + 400
            prefs.edit().putInt("ds", ds).apply()
            refreshLabels()
        })
        col.addView(dsBar)

        lgLabel = label("")
        col.addView(lgLabel)
        val lgBar = SeekBar(this)
        lgBar.max = 1700
        lgBar.progress = (lg - 800).coerceIn(0, 1700)
        lgBar.setOnSeekBarChangeListener(listener { p ->
            lg = p + 800
            prefs.edit().putInt("lg", lg).apply()
            refreshLabels()
        })
        col.addView(lgBar)

        calBtn = Button(this)
        calBtn.text = "🎯 Calibrate"
        calBtn.setOnClickListener { calibrate() }
        col.addView(calBtn)

        val resetBtn = Button(this)
        resetBtn.text = "Reset text"
        resetBtn.setOnClickListener {
            text = ""
            seq = ""
            render()
        }
        col.addView(resetBtn)

        refreshLabels()
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun listener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) onChange(p)
        }

        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun refreshLabels() {
        thLabel.text = "Blink sensitivity: ${"%.2f".format(th)} (lower = easier)"
        dsLabel.text = "Dash starts at: $ds ms"
        lgLabel.text = "Pause to finish a letter: $lg ms"
    }

    private fun render() {
        outText.text = text + if (text.trim() == "SOS") "  ✅" else ""
        seqText.text = seq.replace('.', '·').replace('-', '−')
    }

    private fun show(msg: String) {
        evtText.text = msg
        handler.removeCallbacks(clearEvt)
        handler.postDelayed(clearEvt, 1200)
    }

    private val clearEvt = Runnable { evtText.text = "" }

    private fun beep(type: Int, ms: Int) {
        try {
            tone?.startTone(type, ms)
        } catch (e: Exception) {
        }
    }

    private val gapRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (seq.isNotEmpty() && now - lastSym > lg) {
                val ch = morse[seq]
                if (ch != null) {
                    text += ch
                    show("Letter $ch")
                    beep(ToneGenerator.TONE_PROP_ACK, 120)
                } else {
                    show("Unknown code")
                    beep(ToneGenerator.TONE_PROP_NACK, 200)
                }
                seq = ""
                lastSym = now
                render()
            } else if (seq.isEmpty() && text.isNotEmpty() && !spaceAdded &&
                lastSym != 0L && now - lastSym > 2500 && !text.endsWith(" ")
            ) {
                text += " "
                spaceAdded = true
                render()
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun addSymbol(c: Char) {
        seq += c
        lastSym = SystemClock.uptimeMillis()
        spaceAdded = false
        show(if (c == '.') "· dot" else "− dash")
        beep(ToneGenerator.TONE_PROP_BEEP, if (c == '.') 80 else 200)
        render()
    }

    // ---------- Blink logic ----------

    private fun process(l: Float, r: Float, brow: Float) {
        val now = SystemClock.uptimeMillis()
        val m = min(l, r)
        eyeBar.progress = (m * 100).toInt()
        faceText.text = "Face found ✅"
        if (calibrating) {
            samples.add(m)
            return
        }

        if (!closed && m > th) {
            closed = true
            closedAt = now
        } else if (closed && m < th * 0.7f) {
            closed = false
            val d = now - closedAt
            if (d in 250..2999) addSymbol(if (d >= ds) '-' else '.')
        }

        if (brow > 0.55f && !browUp && now > browLock) {
            browUp = true
            browLock = now + 900
            if (seq.isNotEmpty()) seq = seq.dropLast(1) else text = text.dropLast(1)
            show("🤨 backspace")
            render()
        } else if (brow < 0.35f) browUp = false
    }

    private fun avg() = if (samples.isEmpty()) 0f else samples.sum() / samples.size

    private fun calibrate() {
        calibrating = true
        calBtn.isEnabled = false
        status.text = "Step 1: keep eyes OPEN, look at the screen..."
        var open = 0f
        handler.postDelayed({ samples.clear() }, 1500)
        handler.postDelayed({
            open = avg()
            samples.clear()
            beep(ToneGenerator.TONE_PROP_BEEP, 200)
            status.text = "Step 2: CLOSE your eyes until you hear the beep"
        }, 3500)
        handler.postDelayed({ samples.clear() }, 4200)
        handler.postDelayed({
            val closedAvg = avg()
            beep(ToneGenerator.TONE_PROP_BEEP, 200)
            if (closedAvg - open < 0.2f) {
                status.text = "Calibration failed. Add light, face the camera, try again."
            } else {
                th = (Math.round((open + (closedAvg - open) * 0.5f) * 20) / 20f).coerceIn(0.1f, 0.9f)
                prefs.edit().putFloat("th", th).apply()
                thBar.progress = ((th * 100).toInt() - 10).coerceIn(0, 80)
                refreshLabels()
                status.text = "Calibrated ✅ Open=${"%.2f".format(open)} Closed=${"%.2f".format(closedAvg)} Sensitivity=${"%.2f".format(th)}"
            }
            calibrating = false
            closed = false
            calBtn.isEnabled = true
        }, 6200)
    }

    // ---------- Camera ----------

    private fun startCamera() {
        faceText.text = "Loading face model..."
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (landmarker == null) {
                val base = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
                val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .build()
                landmarker = FaceLandmarker.createFromOptions(this, opts)
            }
            val bmp = proxy.toBitmap()
            val matrix = Matrix()
            matrix.postRotate(proxy.imageInfo.rotationDegrees.toFloat())
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            val res = landmarker!!.detect(BitmapImageBuilder(rotated).build())
            val shapes = res.faceBlendshapes().orElse(null)
            if (shapes != null && shapes.isNotEmpty()) {
                var l = 0f
                var r = 0f
                var b1 = 0f
                var b2 = 0f
                var b3 = 0f
                for (c in shapes[0]) {
                    when (c.categoryName()) {
                        "eyeBlinkLeft" -> l = c.score()
                        "eyeBlinkRight" -> r = c.score()
                        "browInnerUp" -> b1 = c.score()
                        "browOuterUpLeft" -> b2 = c.score()
                        "browOuterUpRight" -> b3 = c.score()
                    }
                }
                val brow = (b1 + b2 + b3) / 3f
                runOnUiThread { process(l, r, brow) }
            } else {
                runOnUiThread { faceText.text = "No face found. Move closer / add light." }
            }
        } catch (e: Exception) {
            runOnUiThread { status.text = "Error: ${e.message}" }
        } finally {
            proxy.close()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        landmarker?.close()
        tone?.release()
        super.onDestroy()
    }
}