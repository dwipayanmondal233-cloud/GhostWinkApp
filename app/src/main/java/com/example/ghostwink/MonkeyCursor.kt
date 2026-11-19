package com.example.ghostwink

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class MonkeyCursor(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private val w = (84 * d).toInt()
    private val jump = (64 * d).toInt()
    private val h = w + jump
    private val handler = Handler(Looper.getMainLooper())

    private val root = FrameLayout(ctx)
    private val monkey = TextView(ctx)
    private val zzz = TextView(ctx)
    private val boom = TextView(ctx)
    private val lp = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private var attached = false
    private var x = 0f
    private var y = 0f
    private var sleeping = false
    private var busy = false
    private var tilt = 1f
    private var lastMove = 0L
    private var walk: ValueAnimator? = null
    private var zAnim: ValueAnimator? = null

    init {
        monkey.text = "\uD83D\uDC35"
        monkey.textSize = 40f
        monkey.gravity = Gravity.CENTER
        monkey.pivotX = w / 2f
        monkey.pivotY = w.toFloat()
        root.addView(monkey, FrameLayout.LayoutParams(w, w, Gravity.BOTTOM))

        zzz.text = "\uD83D\uDCA4"
        zzz.textSize = 20f
        zzz.visibility = View.GONE
        val zl = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        zl.topMargin = jump
        root.addView(zzz, zl)

        boom.text = "\uD83D\uDCA5"
        boom.textSize = 34f
        boom.visibility = View.GONE
        root.addView(
            boom,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
        lp.gravity = Gravity.TOP or Gravity.START
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            val idle = SystemClock.uptimeMillis() - lastMove
            if (!busy) {
                if (idle > 350 && walk != null) stopWalk()
                if (idle > 3000 && !sleeping) sleep()
            }
            handler.postDelayed(this, 250)
        }
    }

    fun setBoom(e: String) {
        boom.text = e
    }

    fun setSkin(s: String) {
        if (s == "dot") {
            monkey.text = "◎"
            monkey.textSize = 34f
            monkey.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
            monkey.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        } else {
            monkey.text = s
            monkey.textSize = 40f
            monkey.setTextColor(android.graphics.Color.WHITE)
            monkey.gravity = Gravity.CENTER
        }
    }

    private fun place() {
        lp.x = (x - w / 2f).toInt()
        lp.y = (y - h).toInt()
        if (attached) {
            try {
                wm.updateViewLayout(root, lp)
            } catch (e: Exception) {
            }
        }
    }

    fun show(sx: Float, sy: Float) {
        x = sx
        y = sy
        place()
        if (!attached) {
            try {
                wm.addView(root, lp)
                attached = true
            } catch (e: Exception) {
                return
            }
        }
        lastMove = SystemClock.uptimeMillis()
        wake()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 250)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        stopWalk()
        zAnim?.cancel()
        monkey.animate().cancel()
        boom.animate().cancel()
        if (attached) {
            try {
                wm.removeView(root)
            } catch (e: Exception) {
            }
        }
        attached = false
        busy = false
    }

    fun moveTo(tx: Float, ty: Float) {
        if (!attached || busy) return
        val dm = ctx.resources.displayMetrics
        val cx = tx.coerceIn(0f, dm.widthPixels.toFloat())
        val cy = ty.coerceIn(0f, dm.heightPixels.toFloat())
        val dx = cx - x
        val dy = cy - y
        if (sqrt(dx * dx + dy * dy) < 10 * d) return // dead zone = steady cursor
        x += dx * 0.35f
        y += dy * 0.35f
        place()
        lastMove = SystemClock.uptimeMillis()
        if (sleeping) wake()
        startWalk(dx)
    }

    private fun startWalk(dx: Float) {
        if (abs(dx) > 2f) tilt = if (dx > 0) 1f else -1f
        if (walk != null) return
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 380
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val p = it.animatedValue as Float
            val hop = abs(sin(p * Math.PI)).toFloat()
            val sway = sin(p * 2.0 * Math.PI).toFloat()
            monkey.translationY = -hop * 9f * d
            monkey.rotation = sway * 9f + tilt * 5f
        }
        walk = a
        a.start()
    }

    private fun stopWalk() {
        walk?.cancel()
        walk = null
        monkey.translationY = 0f
        monkey.rotation = 0f
    }

    private fun sleep() {
        sleeping = true
        monkey.alpha = 0.75f
        monkey.rotation = 14f
        zzz.visibility = View.VISIBLE
        zAnim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1800
        a.repeatCount = ValueAnimator.INFINITE
        a.addUpdateListener {
            val p = it.animatedValue as Float
            zzz.translationY = -22f * d * p
            zzz.alpha = if (p < 0.7f) 1f else (1f - p) / 0.3f
        }
        zAnim = a
        a.start()
    }

    private fun wake() {
        sleeping = false
        zAnim?.cancel()
        zzz.visibility = View.GONE
        monkey.alpha = 1f
        monkey.rotation = 0f
    }

    fun slam(onHit: (Float, Float) -> Unit) {
        if (!attached || busy) return
        busy = true
        stopWalk()
        wake()
        val hx = x
        val hy = y
        monkey.animate().cancel()
        monkey.animate().translationY(-jump * 0.8f).scaleY(1.15f).setDuration(140)
            .withEndAction {
                monkey.animate().translationY(0f).scaleY(0.7f).scaleX(1.25f).setDuration(80)
                    .withEndAction {
                        onHit(hx, hy)
                        boom.alpha = 1f
                        boom.visibility = View.VISIBLE
                        boom.animate().alpha(0f).setDuration(350)
                            .withEndAction { boom.visibility = View.GONE }.start()
                        monkey.animate().scaleX(1f).scaleY(1f).setDuration(280)
                            .setInterpolator(OvershootInterpolator(3f))
                            .withEndAction {
                                busy = false
                                lastMove = SystemClock.uptimeMillis()
                            }.start()
                    }.start()
            }.start()
    }
}