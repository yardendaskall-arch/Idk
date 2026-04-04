package com.home.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private SettingsManager sm;
    private int accentColor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        sm = new SettingsManager(this);
        accentColor = sm.getAccentColor();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0F0C29);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(24), 0, dp(48));

        // Title bar
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(8), dp(8), dp(16), dp(8));

        TextView backBtn = new TextView(this);
        backBtn.setText("←");
        backBtn.setTextColor(0xFFFFFFFF);
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        backBtn.setPadding(dp(12), dp(8), dp(16), dp(8));
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        TextView titleTv = new TextView(this);
        titleTv.setText("Settings");
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);

        titleBar.addView(backBtn);
        titleBar.addView(titleTv);
        root.addView(titleBar);

        // Divider
        root.addView(makeDivider());

        // ── LAYOUT ──────────────────────────────────
        root.addView(sectionHeader("Layout"));

        root.addView(makeSeekRow("Grid columns", 2, 5, sm.getColumns() - 2,
            new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean u) { sm.set(SettingsManager.KEY_COLUMNS, p + 2); }
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) {}
            }, new int[]{2, 3, 4, 5}));

        root.addView(makeThreeWayRow("Icon size", new String[]{"S", "M", "L"},
            sm.getIconSize(), new ThreeWayCallback() {
                public void onSelect(int i) { sm.set(SettingsManager.KEY_ICON_SIZE, i); }
            }));

        root.addView(makeToggleRow("Show app labels", sm.showLabels(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_LABELS, c); }
            }));

        root.addView(makeThreeWayRow("Label size", new String[]{"S", "M", "L"},
            sm.getLabelSize(), new ThreeWayCallback() {
                public void onSelect(int i) { sm.set(SettingsManager.KEY_LABEL_SIZE, i); }
            }));

        root.addView(makeToggleRow("Sort Z→A", sm.getSortOrder() == 1,
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SORT_ORDER, c ? 1 : 0); }
            }));

        // ── CLOCK ──────────────────────────────────
        root.addView(sectionHeader("Clock"));

        root.addView(makeToggleRow("Show clock", sm.showClock(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_CLOCK, c); }
            }));

        root.addView(makeThreeWayRow("Clock size", new String[]{"S", "M", "L"},
            sm.getClockSize(), new ThreeWayCallback() {
                public void onSelect(int i) { sm.set(SettingsManager.KEY_CLOCK_SIZE, i); }
            }));

        root.addView(makeToggleRow("24-hour format", sm.is24h(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_CLOCK_24H, c); }
            }));

        root.addView(makeToggleRow("Show seconds", sm.showSeconds(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_SECONDS, c); }
            }));

        root.addView(makeToggleRow("Show date", sm.showDate(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_DATE, c); }
            }));

        // ── SEARCH ──────────────────────────────────
        root.addView(sectionHeader("Search"));

        root.addView(makeToggleRow("Show search bar", sm.showSearch(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_SEARCH, c); }
            }));

        root.addView(makeToggleRow("Search at bottom", sm.searchBottom(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SEARCH_BOTTOM, c); }
            }));

        // ── APPEARANCE ──────────────────────────────────
        root.addView(sectionHeader("Appearance"));

        root.addView(makeLabel("Background theme"));
        root.addView(makeBgPresetPicker());

        root.addView(makeLabel("Accent color"));
        root.addView(makeAccentPicker());

        root.addView(makeLabel("Icon shape"));
        root.addView(makeIconShapePicker());

        scroll.addView(root);
        setContentView(scroll);
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private View sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextColor(accentColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setLetterSpacing(0.12f);
        tv.setPadding(dp(20), dp(20), dp(20), dp(6));
        return tv;
    }

    private View makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x99FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(dp(20), dp(12), dp(20), dp(4));
        return tv;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        v.setLayoutParams(lp);
        return v;
    }

    private View makeRowDivider() {
        View v = new View(this);
        v.setBackgroundColor(0x11FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(20), 0, dp(20), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private View makeToggleRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setBackgroundColor(0x00000000);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(tvLp);

        CheckBox cb = new CheckBox(this);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener(listener);

        row.addView(tv);
        row.addView(cb);
        return row;
    }

    interface ThreeWayCallback { void onSelect(int index); }

    private View makeThreeWayRow(String label, String[] options, int selected, final ThreeWayCallback cb) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(10), dp(20), dp(10));

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextColor(0xFFFFFFFF);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lv.setPadding(0, 0, 0, dp(8));
        col.addView(lv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        final TextView[] tvs = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView btn = new TextView(this);
            btn.setText(options[i]);
            btn.setGravity(Gravity.CENTER);
            btn.setTextColor(i == selected ? 0xFF000000 : 0xFFFFFFFF);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setPadding(dp(16), dp(6), dp(16), dp(6));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(i == selected ? accentColor : 0x33FFFFFF);
            btn.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            btn.setLayoutParams(lp);
            tvs[i] = btn;
            btns.addView(btn);

            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    cb.onSelect(idx);
                    for (int j = 0; j < tvs.length; j++) {
                        GradientDrawable d = new GradientDrawable();
                        d.setCornerRadius(dp(20));
                        d.setColor(j == idx ? accentColor : 0x33FFFFFF);
                        tvs[j].setBackground(d);
                        tvs[j].setTextColor(j == idx ? 0xFF000000 : 0xFFFFFFFF);
                    }
                }
            });
        }
        col.addView(btns);
        return col;
    }

    private View makeSeekRow(String label, int min, int max, int current,
                             SeekBar.OnSeekBarChangeListener listener, int[] tickLabels) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(10), dp(20), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextColor(0xFFFFFFFF);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lv.setLayoutParams(lvLp);

        final TextView valTv = new TextView(this);
        valTv.setText(String.valueOf(tickLabels[current]));
        valTv.setTextColor(0xFFBBBBBB);
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        header.addView(lv);
        header.addView(valTv);
        col.addView(header);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(current);
        final int[] tl = tickLabels;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                valTv.setText(String.valueOf(tl[p]));
                listener.onProgressChanged(s, p, u);
            }
            public void onStartTrackingTouch(SeekBar s) { listener.onStartTrackingTouch(s); }
            public void onStopTrackingTouch(SeekBar s) { listener.onStopTrackingTouch(s); }
        });
        col.addView(sb);
        return col;
    }

    private View makeBgPresetPicker() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setPadding(dp(16), dp(8), dp(16), dp(12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final int current = sm.getBgPreset();
        final View[] circles = new View[SettingsManager.BG_PRESETS.length];

        for (int i = 0; i < SettingsManager.BG_PRESETS.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(6), 0, dp(6), 0);

            View circle = new View(this);
            int size = dp(52);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            circle.setLayoutParams(lp);

            GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{SettingsManager.BG_PRESETS[i][0], SettingsManager.BG_PRESETS[i][1]});
            gd.setCornerRadius(dp(26));
            if (i == current) {
                gd.setStroke(dp(3), accentColor);
            }
            circle.setBackground(gd);
            circles[i] = circle;

            cell.addView(circle);

            // tick mark if selected
            TextView tick = new TextView(this);
            tick.setText(i == current ? "✓" : "");
            tick.setTextColor(accentColor);
            tick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tick.setGravity(Gravity.CENTER);
            cell.addView(tick);

            final TextView tickRef = tick;
            circle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sm.set(SettingsManager.KEY_BG_PRESET, idx);
                    for (int j = 0; j < circles.length; j++) {
                        GradientDrawable d = new GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            new int[]{SettingsManager.BG_PRESETS[j][0], SettingsManager.BG_PRESETS[j][1]});
                        d.setCornerRadius(dp(26));
                        if (j == idx) d.setStroke(dp(3), accentColor);
                        circles[j].setBackground(d);
                    }
                }
            });

            row.addView(cell);
        }

        hsv.addView(row);
        return hsv;
    }

    private View makeAccentPicker() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), dp(8), dp(20), dp(12));

        final View[] dots = new View[SettingsManager.ACCENT_PRESETS.length];
        final int currentAccent = sm.getAccentColor();

        for (int i = 0; i < SettingsManager.ACCENT_PRESETS.length; i++) {
            final int col = SettingsManager.ACCENT_PRESETS[i];
            final int idx = i;
            View dot = new View(this);
            int size = dp(36);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(0, 0, dp(10), 0);
            dot.setLayoutParams(lp);

            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(col);
            if (col == currentAccent) gd.setStroke(dp(3), 0xFFFFFFFF);
            dot.setBackground(gd);
            dots[i] = dot;

            dot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sm.set(SettingsManager.KEY_ACCENT_COLOR, col);
                    accentColor = col;
                    for (int j = 0; j < dots.length; j++) {
                        GradientDrawable d = new GradientDrawable();
                        d.setShape(GradientDrawable.OVAL);
                        d.setColor(SettingsManager.ACCENT_PRESETS[j]);
                        if (j == idx) d.setStroke(dp(3), 0xFFFFFFFF);
                        dots[j].setBackground(d);
                    }
                }
            });
            row.addView(dot);
        }
        return row;
    }

    private View makeIconShapePicker() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), dp(8), dp(20), dp(16));

        String[] labels = {"Circle", "Rounded", "Square"};
        final int current = sm.getIconShape();
        final TextView[] btns = new TextView[3];
        float[] radii = {dp(30), dp(12), dp(4)};

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            final float radius = radii[i];
            TextView btn = new TextView(this);
            btn.setText(labels[i]);
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setTextColor(i == current ? 0xFF000000 : 0xFFFFFFFF);
            btn.setPadding(dp(16), dp(8), dp(16), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(radius);
            bg.setColor(i == current ? accentColor : 0x33FFFFFF);
            btn.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(10), 0);
            btn.setLayoutParams(lp);
            btns[i] = btn;
            row.addView(btn);

            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sm.set(SettingsManager.KEY_ICON_SHAPE, idx);
                    for (int j = 0; j < btns.length; j++) {
                        GradientDrawable d = new GradientDrawable();
                        d.setCornerRadius(radii[j]);
                        d.setColor(j == idx ? accentColor : 0x33FFFFFF);
                        btns[j].setBackground(d);
                        btns[j].setTextColor(j == idx ? 0xFF000000 : 0xFFFFFFFF);
                    }
                }
            });
        }
        return row;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
