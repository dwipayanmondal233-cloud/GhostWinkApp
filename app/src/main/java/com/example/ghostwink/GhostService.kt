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
import java.util.Locale
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

    // Read by the app screens
    var text = ""
    var seq = ""
    var evt = ""
    var status = ""
    var faceText = "Starting..."
    var eye = 0f
    var controlMode = false
    var cursorMode = false

    // Practice mode hooks
    var practice = false
    var lastLetter = ""
    var letterSerial = 0
    var lastCmd = ""
    var cmdSerial = 0
    var practiceClick: ((Float, Float) -> Unit)? = null

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: FaceLandmarker? = null
    private var tone: ToneGenerator? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastNoFace = 0L

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

    // mouth gestures
    private var tongueAt = 0L
    private var jawAt = 0L
    private var smileAt = 0L
    private var puckerAt = 0L
    private var mouthLock = 0L
    private var volLock = 0L
    private var volUp = true

    private var calibrating = false
    private val samples = mutableListOf<Float>()

    // cursor
    private var monkey: MonkeyCursor? = null
    private var neutralX = 0.5f
    private var neutralY = 0.5f
    private var lastNx = 0.5f
    private var lastNy = 0.5f
    private val filtX = OneEuro(0.6f, 6f)
    private val filtY = OneEuro(0.6f, 6f)
    private var edgeAt = 0L
    private var edgeLast = 0L

    private var dot: View? = null
    private var dotBg: GradientDrawable? = null
    private var nightView: View? = null
    private var wm: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("gw", MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
        applyNight()
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

    fun say(msg: String) {
        if (!ttsReady || !prefs.getBoolean("voice", true)) return
        tts?.setSpeechRate(prefs.getInt("vrate", 100) / 100f)
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "gw")
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
            .setContentTitle("GHOSTWINK // ACTIVE")
            .setContentText("Tap Stop to turn the camera off")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(action)
            .build()
    }

    // ---------- Night mode ----------

    fun applyNight() {
        if (!prefs.getBoolean("night", false)) {
            removeNight()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            show("ALLOW DISPLAY OVER OTHER APPS")
            return
        }
        val level = prefs.getInt("nlevel", 50).coerceIn(10, 75)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.setColor(Color.argb(level * 255 / 100, 24, 10, 0))
        if (prefs.getBoolean("glow", false)) {
            bg.setStroke(
                (12 * resources.displayMetrics.density).toInt(),
                Color.argb(200, 255, 240, 200)
            )
        }
        var v = nightView
        if (v == null) {
            v = View(this)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            try {
                wm?.addView(v, p)
            } catch (e: Exception) {
                return
            }
            nightView = v
        }
        v.background = bg
    }

    private fun removeNight() {
        try {
            nightView?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        nightView = null
    }

    // ---------- Status dot ----------

    private fun showDot() {
        if (!Settings.canDrawOverlays(this)) return
        try {
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
                        lastLetter = ch
                        letterSerial++
                        if (!practice) GhostAccess.instance?.typeText(ch)
                    }
                    show(if (controlMode) ch else "Letter $ch")
                    if (!controlMode && prefs.getBoolean("sayletters", false)) say(ch)
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
                if (!controlMode && !practice) GhostAccess.instance?.typeText(" ")
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

    // ---------- Main face logic ----------

    private fun process(
        l: Float, r: Float, brow: Float, nx: Float, ny: Float,
        jaw: Float, tongue: Float, smile: Float, pucker: Float
    ) {
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

        // blinks
        if (!closed && m > th) {
            closed = true
            closedAt = now
        } else if (closed && m < th * 0.7f) {
            closed = false
            val d = now - closedAt
            if (d >= 2500) toggleMode()
            else if (cursorMode) {
                if (d >= 250) {
                    if (d >= ds) pressCursor() else clickCursor()
                }
            } else if (d >= 250) addSymbol(if (d >= ds) '-' else '.')
        }

        // eyebrows
        if (brow > 0.55f && !browUp && now > browLock) {
            browUp = true
            browLock = now + 900
            if (controlMode) {
                toggleCursor()
            } else if (seq.isNotEmpty()) {
                seq = seq.dropLast(1)
                show("🤨 backspace")
            } else {
                text = text.dropLast(1)
                if (!practice) GhostAccess.instance?.deleteLast()
                show("🤨 backspace")
            }
        } else if (brow < 0.35f) browUp = false

        // mouth gestures (CONTROL and CURSOR modes only)
        if (controlMode) {
            if (tongue > 0.45f) {
                if (tongueAt == 0L) tongueAt = now
            } else tongueAt = 0L
            if (jaw > 0.55f && tongue < 0.3f) {
                if (jawAt == 0L) jawAt = now
            } else jawAt = 0L
            if (smile > 0.65f && jaw < 0.3f) {
                if (smileAt == 0L) smileAt = now
            } else smileAt = 0L
            if (pucker > 0.6f && jaw < 0.35f && tongue < 0.3f && smile < 0.3f) {
                if (puckerAt == 0L) puckerAt = now
            } else puckerAt = 0L

            if (now >= mouthLock) {
                if (tongueAt != 0L && now - tongueAt > 500) navigate("BACK")
                else if (jawAt != 0L && now - jawAt > 700) navigate("HOME")
                else if (cursorMode && prefs.getBoolean("smile", true) &&
                    smileAt != 0L && now - smileAt > 600
                ) likeCursor()
                else if (prefs.getBoolean("kiss", true) &&
                    puckerAt != 0L && now - puckerAt > 500 && now >= volLock
                ) stepVolume(now)
            }
        } else {
            tongueAt = 0L
            jawAt = 0L
            smileAt = 0L
            puckerAt = 0L
        }

        // move cursor (held still while eyes closed or a mouth gesture is in progress)
        if (cursorMode && !closed && tongueAt == 0L && jawAt == 0L &&
            smileAt == 0L && puckerAt == 0L
        ) {
            updateCursor(nx, ny, now)
            edgeScroll(now)
        } else {
            edgeAt = 0L
        }
        refreshDot()
    }

    private fun lockMouth() {
        mouthLock = SystemClock.uptimeMillis() + 1800
        tongueAt = 0L
        jawAt = 0L
        smileAt = 0L
        puckerAt = 0L
    }

    private fun navigate(name: String) {
        lockMouth()
        if (practice) {
            lastCmd = name
            cmdSerial++
        } else {
            val a = GhostAccess.instance
            if (a == null) {
                show("ENABLE GHOSTWINK IN ACCESSIBILITY")
                return
            }
            if (name == "BACK") a.back() else a.home()
        }
        show(name)
        beep(ToneGenerator.TONE_PROP_ACK, 150)
        flash()
    }

    private fun stepVolume(now: Long) {
        volLock = now + 600
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (volUp && cur >= max) volUp = false
            else if (!volUp && cur <= 0) volUp = true
            if (practice) {
                show(if (volUp) "VOLUME UP" else "VOLUME DOWN")
                return
            }
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (volUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0
            )
            val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            show((if (volUp) "VOLUME ▲ " else "VOLUME ▼ ") + "$v/$max")
            beep(ToneGenerator.TONE_PROP_BEEP, 60)
        } catch (e: Exception) {
            show("VOLUME ERROR")
        }
    }

    // ---------- Modes ----------

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
        if (practice) {
            val name = when (code) {
                "." -> "SCROLL DOWN"
                "-" -> "SCROLL UP"
                "..." -> "NOTIFICATIONS"
                "-." -> "TAP"
                ".-" -> "RECENTS"
                else -> null
            }
            if (name != null) {
                lastCmd = name
                cmdSerial++
            }
            return name
        }
        val a = GhostAccess.instance ?: return "ENABLE GHOSTWINK IN ACCESSIBILITY"
        return when (code) {
            "." -> { a.scroll(true); "SCROLL DOWN" }
            "-" -> { a.scroll(false); "SCROLL UP" }
            "..." -> { a.notifications(); "NOTIFICATIONS" }
            "-." -> { a.tapCenter(); "TAP" }
            ".-" -> { a.recents(); "RECENTS" }
            else -> null
        }
    }

    // ---------- Cursor ----------

    fun applySkin() {
        monkey?.setSkin(prefs.getString("cskin", "🐵") ?: "🐵")
    }

    private fun toggleCursor() {
        if (!cursorMode) {
            if (!Settings.canDrawOverlays(this)) {
                show("ALLOW DISPLAY OVER OTHER APPS")
                return
            }
            if (GhostAccess.instance == null && !practice) {
                show("ENABLE ACTIONS (ACCESSIBILITY) FIRST")
                return
            }
            cursorMode = true
            seq = ""
            neutralX = lastNx
            neutralY = lastNy
            filtX.reset()
            filtY.reset()
            edgeAt = 0L
            if (monkey == null) monkey = MonkeyCursor(this)
            applySkin()
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

    private fun updateCursor(nx: Float, ny: Float, now: Long) {
        val dm = resources.displayMetrics
        val s = prefs.getInt("csmooth", 5).coerceIn(1, 10)
        val cutoff = 3.2f / s
        filtX.minCutoff = cutoff
        filtY.minCutoff = cutoff
        val fx = filtX.filter(nx, now)
        val fy = filtY.filter(ny, now)
        val gain = prefs.getInt("cgain", 6).toFloat()
        val sign = if (prefs.getBoolean("cinvert", false)) -1f else 1f
        val tx = dm.widthPixels / 2f + (neutralX - fx) * sign * gain * dm.widthPixels
        val ty = dm.heightPixels / 2f + (fy - neutralY) * gain * 1.5f * dm.heightPixels
        monkey?.moveTo(tx, ty)
    }

    private fun edgeScroll(now: Long) {
        val m = monkey ?: return
        if (!prefs.getBoolean("edge", true) || m.isBusy) {
            edgeAt = 0L
            return
        }
        val h = resources.displayMetrics.heightPixels
        val zone = h * 0.07f
        val dir = when {
            m.posY < zone -> -1
            m.posY > h - zone -> 1
            else -> 0
        }
        if (dir == 0) {
            edgeAt = 0L
            return
        }
        if (edgeAt == 0L) {
            edgeAt = now
            return
        }
        if (now - edgeAt > 450 && now - edgeLast > 800) {
            edgeLast = now
            if (!practice) GhostAccess.instance?.scroll(dir > 0)
            show(if (dir > 0) "EDGE SCROLL ▼" else "EDGE SCROLL ▲")
        }
    }

    private fun clickCursor() {
        monkey?.setBoom("💥")
        monkey?.slam { x, y ->
            if (practice) practiceClick?.invoke(x, y) else GhostAccess.instance?.tapAt(x, y)
        }
        show("SELECT")
        beep(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    private fun pressCursor() {
        monkey?.setBoom("⏳")
        monkey?.slam { x, y ->
            if (!practice) GhostAccess.instance?.longPressAt(x, y)
        }
        show("LONG PRESS")
        beep(ToneGenerator.TONE_PROP_BEEP2, 150)
    }

    private fun likeCursor() {
        lockMouth()
        monkey?.setBoom("❤️")
        monkey?.slam { x, y ->
            if (!practice) GhostAccess.instance?.doubleTapAt(x, y)
        }
        show("LIKE ❤️")
        beep(ToneGenerator.TONE_PROP_ACK, 150)
        flash()
    }

    fun resetModes() {
        if (cursorMode) leaveCursor()
        controlMode = false
        seq = ""
    }

    fun resetText() {
        text = ""
        seq = ""
    }

    // ---------- Calibration ----------

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
                say("Calibrated")
                status = "Calibrated ✅ Open=${"%.2f".format(open)} Closed=${"%.2f".format(closedAvg)} Sensitivity=${"%.2f".format(th)}"
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
            val marks = res.faceLandmarks()
            if (shapes != null && shapes.isNotEmpty() && marks.isNotEmpty()) {
                var l = 0f
                var r = 0f
                var b1 = 0f
                var b2 = 0f
                var b3 = 0f
                var jaw = 0f
                var tongue = 0f
                var sl = 0f
                var sr = 0f
                var pucker = 0f
                for (c in shapes[0]) {
                    when (c.categoryName()) {
                        "eyeBlinkLeft" -> l = c.score()
                        "eyeBlinkRight" -> r = c.score()
                        "browInnerUp" -> b1 = c.score()
                        "browOuterUpLeft" -> b2 = c.score()
                        "browOuterUpRight" -> b3 = c.score()
                        "jawOpen" -> jaw = c.score()
                        "tongueOut" -> tongue = c.score()
                        "mouthSmileLeft" -> sl = c.score()
                        "mouthSmileRight" -> sr = c.score()
                        "mouthPucker" -> pucker = c.score()
                    }
                }
                val brow = (b1 + b2 + b3) / 3f
                val smile = (sl + sr) / 2f
                val nose = marks[0][1]
                val nx = nose.x()
                val ny = nose.y()
                handler.post { process(l, r, brow, nx, ny, jaw, tongue, smile, pucker) }
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
        monkey?.hide()
        removeNight()
        try {
            dot?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        executor.shutdown()
        landmarker?.close()
        tone?.release()
        super.onDestroy()
    }
}