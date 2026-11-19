package com.example.ghostwink

import kotlin.math.abs

class OneEuro(var minCutoff: Float, var beta: Float) {
    private val dCutoff = 1f
    private var xPrev = 0f
    private var dxPrev = 0f
    private var tPrev = 0L
    private var ready = false

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun reset() {
        ready = false
    }

    fun filter(x: Float, tMs: Long): Float {
        if (!ready) {
            ready = true
            xPrev = x
            dxPrev = 0f
            tPrev = tMs
            return x
        }
        val dt = (tMs - tPrev) / 1000f
        if (dt <= 0.001f) return xPrev
        tPrev = tMs
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1f - aD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1f - a) * xPrev
        xPrev = xHat
        dxPrev = dxHat
        return xHat
    }
}