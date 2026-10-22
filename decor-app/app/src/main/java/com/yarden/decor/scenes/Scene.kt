package com.yarden.decor.scenes

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.sin
import kotlin.random.Random

/** One animated decoration. Hosts (the wallpaper engine and the in-app preview) drive it. */
interface Scene {
    fun resize(width: Int, height: Int)

    /** Advance the animation by [dt] seconds. */
    fun update(dt: Float)

    fun draw(canvas: Canvas)

    fun touch(x: Float, y: Float)
}

enum class SceneType(val label: String) {
    AURORA("Aurora"),
    BUBBLES("Bubbles"),
    STARS("Starry night"),
    FIREFLIES("Fireflies");

    fun create(palette: Palette, density: Float): Scene = when (this) {
        AURORA -> AuroraScene(palette, density)
        BUBBLES -> BubblesScene(palette, density)
        STARS -> StarsScene(palette, density)
        FIREFLIES -> FirefliesScene(palette, density)
    }
}

abstract class BaseScene(
    protected val palette: Palette,
    protected val density: Float
) : Scene {
    protected var w = 0f
    protected var h = 0f

    // Double so long-running wallpapers don't lose animation precision over days.
    private var time = 0.0
    protected val rnd: Random = Random(System.nanoTime())
    private val ripples = Ripples(palette.colors, density)

    final override fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        onResize()
    }

    final override fun update(dt: Float) {
        time += dt
        if (w > 0f) onUpdate(dt)
        ripples.update(dt)
    }

    final override fun draw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        if (w > 0f) onDraw(canvas)
        ripples.draw(canvas)
    }

    final override fun touch(x: Float, y: Float) {
        ripples.add(x, y)
        onTouch(x, y)
    }

    protected open fun onResize() {}
    protected abstract fun onUpdate(dt: Float)
    protected abstract fun onDraw(canvas: Canvas)
    protected open fun onTouch(x: Float, y: Float) {}

    protected fun dp(v: Float): Float = v * density

    protected fun rand(min: Float, max: Float): Float = min + rnd.nextFloat() * (max - min)

    /** A smooth -1..1 oscillation at [freq] radians per second. */
    protected fun wave(freq: Float, phase: Float): Float = sin(time * freq + phase).toFloat()
}

/** Expanding rings that appear where the screen is tapped. */
class Ripples(private val colors: IntArray, private val density: Float) {
    private class Ring(val x: Float, val y: Float, val color: Int, var age: Float = 0f)

    private val rings = ArrayList<Ring>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var next = 0

    fun add(x: Float, y: Float) {
        if (rings.size >= MAX_RINGS) rings.removeAt(0)
        rings.add(Ring(x, y, colors[next++ % colors.size]))
    }

    fun update(dt: Float) {
        val iter = rings.iterator()
        while (iter.hasNext()) {
            val ring = iter.next()
            ring.age += dt
            if (ring.age >= LIFE) iter.remove()
        }
    }

    fun draw(canvas: Canvas) {
        for (ring in rings) {
            val t = ring.age / LIFE
            val eased = 1f - (1f - t) * (1f - t)
            paint.color = ring.color
            paint.alpha = ((1f - t) * 200f).toInt()
            paint.strokeWidth = density * (1f + 3f * (1f - t))
            canvas.drawCircle(ring.x, ring.y, density * (10f + eased * 90f), paint)
        }
    }

    private companion object {
        const val LIFE = 1.1f
        const val MAX_RINGS = 8
    }
}
