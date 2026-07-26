package com.microdose.discipline.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.microdose.discipline.R

/**
 * Draws a brief full-screen "time's up" overlay via SYSTEM_ALERT_WINDOW, reinforcing the
 * forced home-screen redirect with a visible reason the block just happened.
 */
@SuppressLint("ClickableViewAccessibility")
object OverlayBlocker {

    fun canDrawOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(context: Context, autoDismissMillis: Long = 2_500L) {
        if (!canDrawOverlay(context)) return

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        )

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(230, 20, 20, 20))
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.notif_block_title)
                    setTextColor(Color.WHITE)
                    textSize = 26f
                    gravity = Gravity.CENTER
                },
            )
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.notif_block_text)
                    setTextColor(Color.LTGRAY)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(0, 24, 0, 0)
                },
            )
        }

        runCatching {
            windowManager.addView(view, params)
            view.postDelayed({ runCatching { windowManager.removeView(view) } }, autoDismissMillis)
        }
    }
}
