package com.example.ghostwink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors
import kotlin.math.min

class GhostService : LifecycleService() {

    companion object {
        var instance: GhostService? = null
    }

    private val morse = mapOf(
        ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D", "." to "E",
        "..-." to "F", "--." to "G", "...." to "H", ".." to "I", ".---" to "J",
        "-.-" to "K", ".-.." to "L", "--" to "M", "-." to "N", "---" to "O",
        ".--." to "P", "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T",
        "..-" to "U", "...-" to "V", ".--" to "W", "-..-" to "X", "-.--" to "Y",
        "--.." to "Z", ".----" to "1", "..---" to "2", "...--" to "3",
        "....-" to "4", "....." to "5", "-...." to "6", "--..." to "7",
        "---.." to "8", "----." to "9", "-----" to "0"
    )

    // Read by the app screen
    var text = ""
    var seq = ""
    var evt = ""
    var status = ""
    var faceText = "Starting..."
    var eye = 0f

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: FaceLandmarker? = null
    private var tone: ToneGenerator? = null

    private var th = 0.5f
    private var ds = 800
    private var lg = 1200

    private var lastSym = 0L
    private var spaceAdded = false
    private var closed = false
    private var closedAt = 0L
    private var browUp = false
    private var browLock = 0L
    private var faceOk = false
    private var flashUntil = 0L

    private var calibrating = false
    private val samples = mutableListOf<Float>()

    private var dot: View? = null
    private var dotBg: GradientDrawable? = null
    private var wm: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("gw", MODE_PRIVATE)
        reloadSettings()
        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                say("GhostWink active")
            }
        }
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        } catch (e: Exception) {
        }
        ServiceCompat.startForeground(
            this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        showDot()
        startCamera()
        handler.post(gapRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == "STOP") stopSelf()
        return START_NOT_STICKY
    }

    fun reloadSettings() {
        th = prefs.getFloat("th", 0.5f)
        ds = prefs.getInt("ds", 800)
        lg = prefs.getInt("lg", 1200)
    }

    private fun buildNotification(): Notification {
        val channelId = "ghost_channel"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "GhostWink", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, GhostService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop", stop
        ).build()
        return Notification.Builder(this, channelId)
            .setContentTitle("GhostWink is watching 👻")
            .setContentText("Tap Stop to turn the camera off")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(action)
            .build()
    }

    // ---------- Status dot ----------

    private fun showDot() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val size = (28 * resources.displayMetrics.density).toInt()
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(Color.GRAY)
            bg.setStroke(4, Color.WHITE)
            val v = View(this)
            v.background = bg
            val p = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.END
            p.x = 24
            p.y = 90
            wm?.addView(v, p)
            dot = v
            dotBg = bg
        } catch (e: Exception) {
        }
    }

    private fun refreshDot() {
        val now = SystemClock.uptimeMillis()
        val color = when {
            now < flashUntil -> Color.parseColor("#00E5FF")
            closed -> Color.parseColor("#FF2E63")
            faceOk -> Color.parseColor("#39FF88")
            else -> Color.GRAY
        }
        dotBg?.setColor(color)
        dot?.invalidate()
    }

    private fun flash() {
        flashUntil = SystemClock.uptimeMillis() + 350
        refreshDot()
        handler.postDelayed({ refreshDot() }, 400)
    }

    // ---------- Typing logic ----------

    private fun show(msg: String) {
        evt = msg
        handler.removeCallbacks(clearEvt)
        handler.postDelayed(clearEvt, 1200)
    }

    private val clearEvt = Runnable { evt = "" }

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
                val ch = if (controlMode) runCommand(seq) else morse[seq]
                if (ch != null) {
                    if (!controlMode) {
                        text += ch
                        GhostAccess.instance?.typeText(ch)
                    }
                    show(if (controlMode) ch else "Letter $ch")
                    if (controlMode) { /* silent */ }
                    else if (prefs.getBoolean("sayletters", false)) say(ch, flush = false)
                    beep(ToneGenerator.TONE_PROP_ACK, 120)
                    flash()
                } else {
                    show("Unknown code")
                    beep(ToneGenerator.TONE_PROP_NACK, 200)
                }
                seq = ""
                lastSym = now
            } else if (seq.isEmpty() && text.isNotEmpty() && !spaceAdded &&
                lastSym != 0L && now - lastSym > 2500 && !text.endsWith(" ")
            ) {
                text += " "
                if (!controlMode) GhostAccess.instance?.typeText(" ")
                spaceAdded = true
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
    }

    private fun process(l: Float, r: Float, brow: Float, nx: Float, ny: Float) {
        val now = SystemClock.uptimeMillis()
        val m = min(l, r)
        eye = m
        lastNx = nx
        lastNy = ny
        faceOk = true
        faceText = "FACE LOCKED"
        if (calibrating) {
            samples.add(m)
            refreshDot()
            return
        }
        if (!closed && m > th) {
            closed = true
            closedAt = now
        } else if (closed && m < th * 0.7f) {
            closed = false
            val d = now - closedAt
            if (d >= 2500) toggleMode()
            else if (cursorMode) {
                if (d >= ds) clickCursor()
            } else if (d >= 250) addSymbol(if (d >= ds) '-' else '.')
        }
        if (brow > 0.55f && !browUp && now > browLock) {
            browUp = true
            browLock = now + 900
            if (controlMode) {
                toggleCursor()
            } else if (seq.isNotEmpty()) seq = seq.dropLast(1)
            else if (!controlMode) {
                text = text.dropLast(1)
                GhostAccess.instance?.deleteLast()
            }
            if (!controlMode) show("🤨 backspace")
        } else if (brow < 0.35f) browUp = false
        if (cursorMode && !closed) updateCursor(nx, ny)
        refreshDot()
    }

    var controlMode = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastNoFace = 0L

    fun say(msg: String, flush: Boolean = true) {
        if (!ttsReady || !prefs.getBoolean("voice", true)) return
        tts?.setSpeechRate(prefs.getInt("vrate", 100) / 100f)
        val q = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(msg, q, null, "gw")
    }

    private fun toggleMode() {
        if (cursorMode) leaveCursor()
        controlMode = !controlMode
        seq = ""
        show(if (controlMode) "MODE: CONTROL" else "MODE: TYPE")
        say(if (controlMode) "Switched to control mode" else "Switched to type mode")
        beep(ToneGenerator.TONE_PROP_BEEP2, 400)
        flash()
    }

    private fun runCommand(code: String): String? {
        val a = GhostAccess.instance ?: return "ENABLE GHOSTWINK IN ACCESSIBILITY"
        return when (code) {
            "." -> { a.scroll(true); "SCROLL DOWN" }
            "-" -> { a.scroll(false); "SCROLL UP" }
            ".." -> { a.back(); "BACK" }
            "--" -> { a.home(); "HOME" }
            "..." -> { a.notifications(); "NOTIFICATIONS" }
            "-." -> { a.tapCenter(); "TAP" }
            ".-" -> { a.recents(); "RECENTS" }
            else -> null
        }
    }

    var cursorMode = false
    private var monkey: MonkeyCursor? = null
    private var neutralX = 0.5f
    private var neutralY = 0.5f
    private var lastNx = 0.5f
    private var lastNy = 0.5f

    private fun toggleCursor() {
        if (!cursorMode) {
            if (!Settings.canDrawOverlays(this)) {
                show("ALLOW DISPLAY OVER OTHER APPS")
                return
            }
            if (GhostAccess.instance == null) {
                show("ENABLE ACTIONS (ACCESSIBILITY) FIRST")
                return
            }
            cursorMode = true
            seq = ""
            neutralX = lastNx
            neutralY = lastNy
            if (monkey == null) monkey = MonkeyCursor(this)
            val dm = resources.displayMetrics
            monkey?.show(dm.widthPixels / 2f, dm.heightPixels / 2f)
            show("MODE: CURSOR")
            say("Cursor mode")
        } else {
            leaveCursor()
            show("MODE: CONTROL")
            say("Control mode")
        }
        beep(ToneGenerator.TONE_PROP_BEEP2, 250)
        flash()
    }

    private fun leaveCursor() {
        cursorMode = false
        monkey?.hide()
    }

    private fun updateCursor(nx: Float, ny: Float) {
        val dm = resources.displayMetrics
        val gain = prefs.getInt("cgain", 6).toFloat()
        val sign = if (prefs.getBoolean("cinvert", false)) -1f else 1f
        val tx = dm.widthPixels / 2f + (neutralX - nx) * sign * gain * dm.widthPixels
        val ty = dm.heightPixels / 2f + (ny - neutralY) * gain * 1.5f * dm.heightPixels
        monkey?.moveTo(tx, ty)
    }

    private fun clickCursor() {
        monkey?.slam { x, y -> GhostAccess.instance?.tapAt(x, y) }
        beep(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    fun resetText() {
        text = ""
        seq = ""
    }

    private fun avg() = if (samples.isEmpty()) 0f else samples.sum() / samples.size

    fun calibrate() {
        if (calibrating) return
        calibrating = true
        status = "Step 1: keep eyes OPEN, look at the screen..."
        say("Keep your eyes open")
        var open = 0f
        handler.postDelayed({ samples.clear() }, 1500)
        handler.postDelayed({
            open = avg()
            samples.clear()
            beep(ToneGenerator.TONE_PROP_BEEP, 200)
            status = "Step 2: CLOSE your eyes until you hear the beep"
            say("Now close your eyes")
        }, 3500)
        handler.postDelayed({ samples.clear() }, 4200)
        handler.postDelayed({
            val closedAvg = avg()
            beep(ToneGenerator.TONE_PROP_BEEP, 200)
            if (closedAvg - open < 0.2f) {
                status = "Calibration failed. Add light, face the camera, try again."
            } else {
                th = (Math.round((open + (closedAvg - open) * 0.5f) * 20) / 20f).coerceIn(0.1f, 0.9f)
                prefs.edit().putFloat("th", th).apply()
                status = "Calibrated ✅ Open=${"%.2f".format(open)} Closed=${"%.2f".format(closedAvg)} Sensitivity=${"%.2f".format(th)}"
                say("Calibrated")
            }
            calibrating = false
            closed = false
        }, 6200)
    }

    // ---------- Camera ----------

    private fun startCamera() {
        faceText = "Loading face model..."
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
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
                val nose = res.faceLandmarks()[0][1]
                val nx = nose.x()
                val ny = nose.y()
                handler.post { process(l, r, brow, nx, ny) }
            } else {
                handler.post {
                    faceOk = false
                    faceText = "NO FACE - move closer / add light"
                    if (SystemClock.uptimeMillis() - lastNoFace > 15000) {
                        lastNoFace = SystemClock.uptimeMillis()
                        say("I can't see your face")
                    }
                    refreshDot()
                }
            }
        } catch (e: Exception) {
            handler.post { status = "Error: ${e.message}" }
        } finally {
            proxy.close()
        }
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try {
            dot?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        monkey?.hide()
        tts?.stop()
        tts?.shutdown()
        executor.shutdown()
        landmarker?.close()
        tone?.release()
        super.onDestroy()
    }
}