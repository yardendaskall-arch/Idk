package com.yarden.decor

import android.app.WallpaperManager
import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.yarden.decor.scenes.Palette
import com.yarden.decor.scenes.Scene
import com.yarden.decor.scenes.SceneType

class DecoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DecoEngine()

    private inner class DecoEngine : Engine(),
        Choreographer.FrameCallback,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val prefs = Prefs.of(this@DecoWallpaperService)
        private val density = this@DecoWallpaperService.resources.displayMetrics.density
        private val choreographer = Choreographer.getInstance()

        private var sceneType: SceneType = SceneType.AURORA
        private var palette: Palette = Palette.SUNSET
        private var speed = 1f
        private var scene: Scene? = null

        private var width = 0
        private var height = 0
        private var visible = false
        private var lastDrawNanos = 0L
        private var lastTouchNanos = 0L
        private var lastMoveNanos = 0L

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            prefs.registerOnSharedPreferenceChangeListener(this)
            loadPrefs(forceRebuild = true)
        }

        override fun onDestroy() {
            stopLoop()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) startLoop() else stopLoop()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            scene?.resize(width, height)
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            visible = false
            stopLoop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            event ?: return
            val now = System.nanoTime()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchNanos = now
                    lastMoveNanos = now
                    scene?.touch(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> if (now - lastMoveNanos > MOVE_INTERVAL_NANOS) {
                    lastMoveNanos = now
                    scene?.touch(event.x, event.y)
                }
            }
        }

        // Some launchers deliver taps as a command instead of touch events.
        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP &&
                System.nanoTime() - lastTouchNanos > TAP_DEDUPE_NANOS
            ) {
                scene?.touch(x.toFloat(), y.toFloat())
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            loadPrefs(forceRebuild = false)
            if (visible) drawFrame()
        }

        private fun loadPrefs(forceRebuild: Boolean) {
            val newType = Prefs.scene(prefs)
            val newPalette = Prefs.palette(prefs)
            speed = Prefs.speedPercent(prefs) / 100f
            if (forceRebuild || newType != sceneType || newPalette != palette || scene == null) {
                sceneType = newType
                palette = newPalette
                scene = sceneType.create(palette, density).also {
                    if (width > 0 && height > 0) it.resize(width, height)
                }
            }
        }

        private fun startLoop() {
            lastDrawNanos = 0L
            choreographer.removeFrameCallback(this)
            choreographer.postFrameCallback(this)
        }

        private fun stopLoop() {
            choreographer.removeFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            choreographer.postFrameCallback(this)

            // Draw at roughly 30 fps: smooth enough for ambient motion, easy on the battery.
            val elapsed = frameTimeNanos - lastDrawNanos
            if (lastDrawNanos != 0L && elapsed < FRAME_INTERVAL_NANOS - FRAME_SLACK_NANOS) return

            val dt = if (lastDrawNanos == 0L) 0f else (elapsed / 1e9f).coerceAtMost(0.05f)
            lastDrawNanos = frameTimeNanos
            scene?.update(dt * speed)
            drawFrame()
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            if (!holder.surface.isValid) return
            val canvas = lockCanvas(holder) ?: return
            try {
                scene?.draw(canvas)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: IllegalStateException) {
                    // Surface went away while drawing; the next surface callback recovers.
                }
            }
        }

        private fun lockCanvas(holder: SurfaceHolder): Canvas? =
            try {
                holder.lockHardwareCanvas()
            } catch (_: Exception) {
                try {
                    holder.lockCanvas()
                } catch (_: Exception) {
                    null
                }
            }
    }

    private companion object {
        const val FRAME_INTERVAL_NANOS = 33_333_333L
        const val FRAME_SLACK_NANOS = 4_000_000L
        const val MOVE_INTERVAL_NANOS = 150_000_000L
        const val TAP_DEDUPE_NANOS = 150_000_000L
    }
}
