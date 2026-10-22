package com.yarden.decor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import com.yarden.decor.scenes.Palette
import com.yarden.decor.scenes.Scene
import com.yarden.decor.scenes.SceneType

/** Live, tappable preview of a scene inside the app. */
class PreviewView(context: Context) : View(context) {
    private var scene: Scene? = null
    private var lastNanos = 0L

    /** 1.0 is normal speed. */
    var speed = 1f

    init {
        val radius = 24f * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    fun show(type: SceneType, palette: Palette) {
        scene = type.create(palette, resources.displayMetrics.density).also {
            if (width > 0 && height > 0) it.resize(width, height)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scene?.resize(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNanos = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val s = scene ?: return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
        lastNanos = now
        s.update(dt * speed)
        s.draw(canvas)
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scene?.touch(event.x, event.y)
            return true
        }
        return super.onTouchEvent(event)
    }
}
