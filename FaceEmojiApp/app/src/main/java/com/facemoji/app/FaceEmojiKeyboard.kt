package com.facemoji.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.InputCompat
import androidx.core.view.ViewCompat
import java.io.File

/**
 * Face Emoji IME — a full QWERTY keyboard with:
 *  • Your generated face emojis (from the main app)
 *  • A full smiley / standard emoji panel
 *  • Regular typing, shift, symbols, backspace, enter
 */
class FaceEmojiKeyboard : InputMethodService() {

    // ── UI state ──────────────────────────────────────────────────────────────
    private var showingEmoji  = false
    private var showingSymbols = false
    private var capsOn        = false

    private lateinit var root:         FrameLayout
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var emojiPanel:    FrameLayout

    // Key rows in normal / caps / symbols modes
    private val ALPHA_ROWS = listOf(
        listOf("q","w","e","r","t","y","u","i","o","p"),
        listOf("a","s","d","f","g","h","j","k","l"),
        listOf("⇧","z","x","c","v","b","n","m","⌫"),
        listOf("?123","😀"," ",".","\n")
    )
    private val SYM_ROWS = listOf(
        listOf("1","2","3","4","5","6","7","8","9","0"),
        listOf("@","#","$","%","&","-","+","(",")","/"),
        listOf("=","*","\"","'",":",";","!","?","⌫"),
        listOf("ABC","😀"," ",",","\n")
    )

    // 80 standard smileys for the emoji panel
    private val SMILEY_EMOJIS = listOf(
        "😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇",
        "🙂","😉","😌","😍","🥰","😘","😋","😛","😜","🤪",
        "😎","🤩","🥳","😏","😒","😞","😔","😟","😕","🙁",
        "😣","😖","😫","😩","🥺","😢","😭","😤","😠","😡",
        "🤬","🤯","😳","🥵","🥶","😱","😨","😰","😥","😓",
        "🤗","🤔","🤭","🤫","🤥","😶","😐","😑","😬","🙄",
        "😯","😲","😴","🤤","😷","🤒","🤕","🤢","🤮","🤧",
        "😈","👿","💀","☠️","💩","🤡","👹","👺","👻","👾"
    )

    // ── dp / sp helpers ───────────────────────────────────────────────────────
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,  v, resources.displayMetrics).toInt()
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,   v, resources.displayMetrics)

    // ── IME lifecycle ─────────────────────────────────────────────────────────
    override fun onCreateInputView(): View {
        root = FrameLayout(this)
        buildKeyboard()
        buildEmojiPanel()
        root.addView(keyboardPanel)
        root.addView(emojiPanel.also { it.visibility = View.GONE })
        return root
    }

    // ── Keyboard builder ──────────────────────────────────────────────────────

    private fun buildKeyboard() {
        keyboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_KEYBOARD)
            setPadding(dp(4f), dp(6f), dp(4f), dp(4f))
        }
        refreshKeyboard()
    }

    private fun refreshKeyboard() {
        keyboardPanel.removeAllViews()
        val rows = if (showingSymbols) SYM_ROWS else ALPHA_ROWS
        for ((ri, row) in rows.withIndex()) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER
                setPadding(0, dp(2f), 0, dp(2f))
            }
            for (key in row) {
                val btn = makeKey(key, ri == rows.size - 1)
                rowView.addView(btn)
            }
            keyboardPanel.addView(rowView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)))
        }
    }

    private fun makeKey(key: String, isBottomRow: Boolean): View {
        val isSpecial = key in listOf("⇧","⌫","?123","ABC","😀","\n")
        val isSpace   = key == " "

        val btn = TextView(this).apply {
            text      = when (key) {
                "\n"  -> "↵"
                " "   -> "space"
                "⇧"   -> if (capsOn) "⇪" else "⇧"
                else  -> if (!showingSymbols && !isSpecial && capsOn) key.uppercase() else key
            }
            textSize  = sp(if (isSpecial && !isBottomRow) 16f else if (isSpace) 13f else 17f) / resources.displayMetrics.scaledDensity
            setTextColor(if (isSpecial) 0xFFCCCCCC.toInt() else 0xFFFFFFFF.toInt())
            gravity   = Gravity.CENTER
            background = keyBg(isSpecial || isSpace)
            isSoundEffectsEnabled = true
            isHapticFeedbackEnabled = true
        }

        val weight = when {
            isSpace   -> 4f
            key =="\n"-> 1.5f
            key =="⌫" -> 1.5f
            key =="⇧" -> 1.5f
            else      -> 1f
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        lp.setMargins(dp(3f), dp(2f), dp(3f), dp(2f))
        btn.layoutParams = lp

        btn.setOnClickListener {
            btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            handleKey(key)
        }
        if (key == "⌫") {
            btn.setOnLongClickListener { clearAll(); true }
        }
        return btn
    }

    private fun handleKey(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "⌫"    -> ic.deleteSurroundingText(1, 0)
            "\n"   -> { sendDefaultEditorAction(true) }
            " "    -> ic.commitText(" ", 1)
            "⇧"    -> { capsOn = !capsOn; refreshKeyboard() }
            "?123" -> { showingSymbols = true;  refreshKeyboard() }
            "ABC"  -> { showingSymbols = false; refreshKeyboard() }
            "😀"   -> toggleEmojiPanel()
            else   -> {
                val out = if (!showingSymbols && capsOn) key.uppercase() else key
                ic.commitText(out, 1)
                if (capsOn && !showingSymbols) { capsOn = false; refreshKeyboard() }
            }
        }
    }

    private fun clearAll() {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1000, 0)
        ic.endBatchEdit()
    }

    // ── Emoji panel builder ───────────────────────────────────────────────────

    private fun buildEmojiPanel() {
        emojiPanel = FrameLayout(this).apply {
            setBackgroundColor(BG_KEYBOARD)
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }

        // ── Your saved face emojis ──
        val savedFiles = EmojiStore.all(this)
        if (savedFiles.isNotEmpty()) {
            content.addView(sectionLabel("Your Face Emojis"))
            val myRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val myList = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4f), 0, dp(8f))
            }
            for (file in savedFiles) {
                val bmp = EmojiStore.load(file) ?: continue
                val img = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = keyBg(false)
                }
                val lp = LinearLayout.LayoutParams(dp(56f), dp(56f)).apply { setMargins(dp(4f),0,dp(4f),0) }
                img.layoutParams = lp
                img.setOnClickListener { sendCustomEmoji(file); hideEmojiPanel() }
                myList.addView(img)
            }
            myRow.addView(myList)
            content.addView(myRow)
        }

        // ── Generate a preview of all 6 expressions ──
        content.addView(sectionLabel("Expression Emojis"))
        val gen = EmojiGenerator()
        val exprRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4f), 0, dp(8f))
        }
        for (expr in EmojiGenerator.Expression.values()) {
            val bmp = gen.generateForExpression(expr)
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = keyBg(false)
                contentDescription = expr.name
            }
            val lp = LinearLayout.LayoutParams(0, dp(56f), 1f).apply { setMargins(dp(3f),0,dp(3f),0) }
            img.layoutParams = lp
            img.setOnClickListener { sendBitmapEmoji(bmp, expr.name); hideEmojiPanel() }
            exprRow.addView(img)
        }
        content.addView(exprRow)

        // ── Standard Unicode smileys ──
        content.addView(sectionLabel("Smileys & People"))
        val grid = GridLayout(this).apply {
            columnCount = 8
        }
        for (emoji in SMILEY_EMOJIS) {
            val tv = TextView(this).apply {
                text     = emoji
                textSize = 26f
                gravity  = Gravity.CENTER
                background = keyBg(false)
            }
            val lp = GridLayout.LayoutParams().apply {
                width  = dp(44f); height = dp(44f)
                setMargins(dp(2f), dp(2f), dp(2f), dp(2f))
            }
            tv.layoutParams = lp
            tv.setOnClickListener {
                currentInputConnection?.commitText(emoji, 1)
                hideEmojiPanel()
            }
            grid.addView(tv)
        }
        content.addView(grid)

        // Close / back-to-keyboard bar at bottom
        val closeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            setBackgroundColor(BG_SPECIAL)
            setPadding(0, dp(4f), 0, dp(4f))
        }
        val closeBtn = TextView(this).apply {
            text     = "⌨  Back to keyboard"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            gravity  = Gravity.CENTER
        }
        closeBar.addView(closeBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)))
        closeBtn.setOnClickListener { hideEmojiPanel() }

        scroll.addView(content)

        val outerLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outerLayout.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(230f)))
        outerLayout.addView(closeBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        emojiPanel.addView(outerLayout)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize  = 12f
        setTextColor(0xFF888888.toInt())
        setPadding(dp(2f), dp(4f), 0, dp(2f))
    }

    // ── Emoji sending ─────────────────────────────────────────────────────────

    /**
     * Send a custom face emoji bitmap.
     * Tries commitContent() (Android 7.1+) first so it inserts inline;
     * falls back to clipboard copy with a toast.
     */
    private fun sendBitmapEmoji(bmp: Bitmap, label: String) {
        val file = EmojiStore.save(this, bmp)
        sendCustomEmoji(file)
    }

    private fun sendCustomEmoji(file: File) {
        val ic = currentInputConnection ?: return
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        // Try commitContent for apps that accept image input (Messages, Signal, etc.)
        try {
            val desc   = android.view.inputmethod.InputContentInfo(
                uri,
                android.content.ClipDescription("emoji", arrayOf("image/png")),
                null
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                ic.commitContent(desc, android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                return
            }
        } catch (_: Exception) {}

        // Fallback: copy to clipboard
        val clip = android.content.ClipData.newUri(contentResolver, "Face Emoji", uri)
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
        Toast.makeText(this, "Emoji copied — long-press to paste!", Toast.LENGTH_SHORT).show()
    }

    // ── Panel switching ───────────────────────────────────────────────────────

    private fun toggleEmojiPanel() {
        if (showingEmoji) hideEmojiPanel() else showEmojiPanel()
    }

    private fun showEmojiPanel() {
        showingEmoji = true
        // Rebuild emoji panel to pick up any new saved emojis
        emojiPanel.removeAllViews()
        buildEmojiPanel()
        emojiPanel.visibility  = View.VISIBLE
        keyboardPanel.visibility = View.GONE
    }

    private fun hideEmojiPanel() {
        showingEmoji = false
        emojiPanel.visibility  = View.GONE
        keyboardPanel.visibility = View.VISIBLE
    }

    // ── Styling helpers ───────────────────────────────────────────────────────

    private fun keyBg(special: Boolean): StateListDrawable {
        fun gd(color: Int) = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f).toFloat()
            setColor(color)
        }
        val normal  = if (special) BG_SPECIAL else BG_KEY
        val pressed = if (special) 0xFF555570.toInt() else 0xFF4A4A6A.toInt()
        return StateListDrawable().apply {
            addState(intArrayOf( android.R.attr.state_pressed), gd(pressed))
            addState(intArrayOf(),                               gd(normal))
        }
    }

    companion object {
        private val BG_KEYBOARD = 0xFF1A1A2E.toInt()
        private val BG_KEY      = 0xFF2E2E4E.toInt()
        private val BG_SPECIAL  = 0xFF12122A.toInt()
    }
}
