package app.laptopremote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view-building helpers: the whole UI is built in code (no XML layouts). */
final class Ui {
    static final int BG = 0xFF0E1318;
    static final int SURFACE = 0xFF18212A;
    static final int SURFACE2 = 0xFF22303D;
    static final int ACCENT = 0xFF2F7FE0;
    static final int TEXT = 0xFFE8EEF4;
    static final int MUTED = 0xFF8FA1B3;
    static final int DANGER = 0xFFC9453D;
    static final int OK = 0xFF3FB27F;

    interface Action {
        void run();
    }

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int lighten(int color, float amount) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        r += (255 - r) * amount;
        g += (255 - g) * amount;
        b += (255 - b) * amount;
        return Color.rgb(r, g, b);
    }

    static GradientDrawable box(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static StateListDrawable pressable(Context c, int color, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, box(c, lighten(color, 0.28f), radiusDp));
        s.addState(new int[]{}, box(c, color, radiusDp));
        return s;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static Button button(Context c, String label, int color) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(TEXT);
        b.setBackground(pressable(c, color, 12));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        return b;
    }

    static EditText edit(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(17);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setBackground(box(c, SURFACE2, 10));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return e;
    }

    static final int TEXT_URI = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    /** Equal-share child of a horizontal LinearLayout. */
    static LinearLayout.LayoutParams share(Context c, int heightPx, float marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, heightPx, 1f);
        int m = dp(c, marginDp);
        p.setMargins(m, m, m, m);
        return p;
    }

    static LinearLayout.LayoutParams stackGap(Context c, int w, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    static LinearLayout column(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        return r;
    }

    static TextView mono(Context c, String s, float sp) {
        TextView t = text(c, s, sp, TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    /** Fires {@code action} on press and repeats it while the button is held (arrow keys, volume...). */
    static final class RepeatOnHold implements View.OnTouchListener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Action action;
        private final boolean repeat;
        private final Runnable tick = new Runnable() {
            public void run() {
                action.run();
                handler.postDelayed(this, 70);
            }
        };

        RepeatOnHold(Action action, boolean repeat) {
            this.action = action;
            this.repeat = repeat;
        }

        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    action.run();
                    if (repeat) handler.postDelayed(tick, 380);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(tick);
                    break;
                default:
                    break;
            }
            return false;   // let the button draw its pressed state
        }
    }

    static void onPress(View v, Action action, boolean repeat) {
        v.setOnTouchListener(new RepeatOnHold(action, repeat));
    }

    static void matchWidth(ViewGroup parent, View child, int heightPx, float topMarginDp) {
        parent.addView(child, stackGap(parent.getContext(), ViewGroup.LayoutParams.MATCH_PARENT, heightPx, topMarginDp));
    }
}
