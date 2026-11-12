package com.sreeram.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Buddy's face: a soft glowing orb whose motion tells you what it is doing.
// Idle breathes slowly, listening pulses and brightens, thinking swirls in violet, speaking ripples with the voice.
class OrbView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var stateProvider: () -> State = { State.IDLE }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowColors = IntArray(3)
    private val blobColors = IntArray(3)

    private companion object {
        // energy, speed, hue, size for each State (same order as the enum)
        val TARGETS = arrayOf(floatArrayOf(0.15f, 0.5f, 0f, 1f), floatArrayOf(0.55f, 1.1f, 0.15f, 1.08f), floatArrayOf(0.4f, 2.6f, 1f, 0.92f), floatArrayOf(0.9f, 1.4f, 0.4f, 1.05f))
        val GLOW_STOPS = floatArrayOf(0.25f, 0.6f, 1f)
        val BLOB_STOPS = floatArrayOf(0f, 0.55f, 1f)
        val HIGHLIGHT = intArrayOf(0x55FFFFFF, 0)
    }
    private var t = 0f
    private var lastNs = 0L
    // Smoothed look parameters, eased toward each state's target so changes glide instead of jumping.
    private var energy = 0.2f      // how much it moves
    private var speed = 0.6f       // how fast the blobs orbit
    private var hue = 0f           // 0 = teal/cyan, 1 = violet
    private var size = 1f

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastNs = 0; postInvalidateOnAnimation() }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k
    private fun mix(a: Int, b: Int, k: Float): Int {
        val r = (android.graphics.Color.red(a) * (1 - k) + android.graphics.Color.red(b) * k).toInt()
        val g = (android.graphics.Color.green(a) * (1 - k) + android.graphics.Color.green(b) * k).toInt()
        val bl = (android.graphics.Color.blue(a) * (1 - k) + android.graphics.Color.blue(b) * k).toInt()
        return android.graphics.Color.rgb(r, g, bl)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        val s = stateProvider()
        val target = TARGETS[s.ordinal]
        val tEnergy = target[0]; val tSpeed = target[1]; val tHue = target[2]; val tSize = target[3]
        val k = 1 - Math.pow(0.001, dt.toDouble()).toFloat() // ease: ~95% of the way in half a second
        energy = lerp(energy, tEnergy, k); speed = lerp(speed, tSpeed, k); hue = lerp(hue, tHue, k); size = lerp(size, tSize, k)
        t += dt * speed

        val cx = width / 2f; val cy = height / 2f
        val base = min(width, height) * 0.30f
        // A speaking orb ripples unevenly like a voice; the others breathe in a slow sine.
        val pulse = if (s == State.SPEAKING) 0.5f * (sin(t * 7f) * 0.5f + sin(t * 11.3f) * 0.35f + sin(t * 3.1f) * 0.15f) + 0.5f
                    else sin(t * 2f) * 0.5f + 0.5f
        val r = base * size * (1f + energy * 0.12f * pulse)

        val teal = 0xFF2EE6C5.toInt(); val cyan = 0xFF3AA0FF.toInt(); val violet = 0xFF9B6BFF.toInt(); val pink = 0xFFFF6BC8.toInt()
        val c1 = mix(teal, violet, hue); val c2 = mix(cyan, pink, hue)

        // Outer glow.
        val glowR = minOf(r * (2.0f + energy * 0.5f), min(width, height) / 2f)
        paint.shader = RadialGradient(cx, cy, glowR, glowColors.also { it[0] = withAlpha(c1, (70 + 80 * energy).toInt()); it[1] = withAlpha(c2, 20) }, GLOW_STOPS, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, glowR, paint)

        // Orbiting colour blobs inside the sphere.
        for (i in 0..2) {
            val ang = t * (1.0f + i * 0.45f) + i * 2.1f
            val off = r * (0.28f + 0.12f * energy)
            val bx = cx + cos(ang) * off; val by = cy + sin(ang * 1.2f) * off
            val br = r * (0.85f - i * 0.08f)
            val col = if (i % 2 == 0) c1 else c2
            paint.shader = RadialGradient(bx, by, br, blobColors.also { it[0] = withAlpha(col, 235); it[1] = withAlpha(col, 120) }, BLOB_STOPS, Shader.TileMode.CLAMP)
            canvas.drawCircle(bx, by, br, paint)
        }

        // Glassy highlight.
        paint.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 0.7f, HIGHLIGHT, null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        postInvalidateOnAnimation()
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
}
