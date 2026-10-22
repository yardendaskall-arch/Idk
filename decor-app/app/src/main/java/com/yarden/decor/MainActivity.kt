package com.yarden.decor

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.yarden.decor.scenes.Palette
import com.yarden.decor.scenes.SceneType

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var preview: PreviewView
    private var sceneType = SceneType.AURORA
    private var palette = Palette.SUNSET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        sceneType = Prefs.scene(prefs)
        palette = Prefs.palette(prefs)
        val speedPercent = Prefs.speedPercent(prefs)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        content.addView(label("Decor", 34f, COLOR_TEXT, bold = true))
        content.addView(label("Pretty, calm things for your home screen", 15f, COLOR_MUTED))

        preview = PreviewView(this).apply { speed = speedPercent / 100f }
        content.addView(preview, LinearLayout.LayoutParams(dp(220), dp(390)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        })
        content.addView(
            label("Tap the preview to play with it", 13f, COLOR_MUTED).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        )

        content.addView(sectionLabel("Scene"))
        content.addView(
            chipRow(SceneType.entries, sceneType, { it.label }, { COLOR_ACCENT }) {
                sceneType = it
                prefs.edit().putString(Prefs.KEY_SCENE, it.name).apply()
                preview.show(sceneType, palette)
            }
        )

        content.addView(sectionLabel("Colors"))
        content.addView(
            chipRow(Palette.entries, palette, { it.label }, { it.colors[0] }) {
                palette = it
                prefs.edit().putString(Prefs.KEY_PALETTE, it.name).apply()
                preview.show(sceneType, palette)
            }
        )

        content.addView(sectionLabel("Speed"))
        content.addView(
            SeekBar(this).apply {
                max = 150
                progress = speedPercent - 50
                progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
                thumbTintList = ColorStateList.valueOf(COLOR_ACCENT)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        preview.speed = (value + 50) / 100f
                        prefs.edit().putInt(Prefs.KEY_SPEED, value + 50).apply()
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            }
        )

        content.addView(
            Button(this).apply {
                text = "Set as wallpaper"
                isAllCaps = false
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_ON_ACCENT)
                background = rounded(COLOR_ACCENT, 28f)
                setOnClickListener { openWallpaperPicker() }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(28) }
        )
        content.addView(
            label(
                "Once it's set, changes here update your wallpaper right away.",
                13f, COLOR_MUTED
            ).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        )

        val root = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // Targeting Android 15+ draws edge to edge, so keep content clear of the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)

        preview.show(sceneType, palette)
    }

    private fun openWallpaperPicker() {
        val component = ComponentName(this, DecoWallpaperService::class.java)
        val change = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        try {
            startActivity(change)
        } catch (_: ActivityNotFoundException) {
            // Some launchers don't handle the direct request; fall back to the system chooser.
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun <T> chipRow(
        items: List<T>,
        selected: T,
        labelOf: (T) -> String,
        accentOf: (T) -> Int,
        onPick: (T) -> Unit
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()

        fun refresh(current: T) {
            items.forEachIndexed { i, item -> styleChip(chips[i], item == current, accentOf(item)) }
        }

        for (item in items) {
            val chip = TextView(this).apply {
                text = labelOf(item)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener {
                    refresh(item)
                    onPick(item)
                }
            }
            chips.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        refresh(selected)

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean, accent: Int) {
        val shape = rounded(if (selected) accent else 0x00000000, 24f)
        if (!selected) shape.setStroke(dp(1), COLOR_OUTLINE)
        chip.background = shape
        chip.setTextColor(if (selected) COLOR_ON_ACCENT else COLOR_TEXT)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun sectionLabel(text: String) =
        label(text, 13f, COLOR_MUTED, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(24), 0, dp(10))
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val COLOR_BG = 0xFF0E0A1A.toInt()
        const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        const val COLOR_MUTED = 0xFFB8B0CC.toInt()
        const val COLOR_OUTLINE = 0x55FFFFFF
        const val COLOR_ACCENT = 0xFFB69CFF.toInt()
        const val COLOR_ON_ACCENT = 0xFF1B0B2E.toInt()
    }
}
