package com.home.launcher;

import android.app.Activity;
import android.graphics.Typeface;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private SettingsManager sm;
    private int accent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        sm = new SettingsManager(this);
        accent = sm.getAccentColor();
        buildUI();
    }

    private void buildUI() {
        // Root scroll with dark gradient bg
        ScrollView scroll = new ScrollView(this);
        int[] bg = SettingsManager.BG_PRESETS[sm.getBgPreset()];
        GradientDrawable rootBg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{bg[0], bg[1]});
        scroll.setBackground(rootBg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(56), dp(16), dp(48));

        // ── Header ──────────────────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(8), dp(4), dp(20));

        TextView backBtn = new TextView(this);
        backBtn.setText("‹");
        backBtn.setTextColor(accent);
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        backBtn.setPadding(dp(4), 0, dp(16), dp(4));
        backBtn.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        TextView titleTv = new TextView(this);
        titleTv.setText("Launcher Settings");
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titleTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        titleTv.setLetterSpacing(0.02f);

        header.addView(backBtn);
        header.addView(titleTv);
        root.addView(header);

        // ── LAYOUT card ─────────────────────────────────────────────────
        LinearLayout layoutCard = startCard();

        addSectionLabel(layoutCard, "Layout");

        addSeekRow(layoutCard, "Grid columns", 2, 5, sm.getColumns() - 2,
            new int[]{2, 3, 4, 5},
            new SeekCallback() { public void onValue(int v) { sm.set(SettingsManager.KEY_COLUMNS, v + 2); }});

        addDivider(layoutCard);
        addThreeWay(layoutCard, "Icon size", new String[]{"S", "M", "L"}, sm.getIconSize(),
            new ChoiceCallback() { public void onSelect(int i) { sm.set(SettingsManager.KEY_ICON_SIZE, i); }});

        addDivider(layoutCard);
        addToggle(layoutCard, "Show labels", sm.showLabels(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_LABELS, c); }});

        addDivider(layoutCard);
        addThreeWay(layoutCard, "Label size", new String[]{"S", "M", "L"}, sm.getLabelSize(),
            new ChoiceCallback() { public void onSelect(int i) { sm.set(SettingsManager.KEY_LABEL_SIZE, i); }});

        addDivider(layoutCard);
        addToggle(layoutCard, "Sort Z → A", sm.getSortOrder() == 1,
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SORT_ORDER, c ? 1 : 0); }});

        root.addView(layoutCard);

        // ── CLOCK card ──────────────────────────────────────────────────
        LinearLayout clockCard = startCard();
        addSectionLabel(clockCard, "Clock");

        addToggle(clockCard, "Show clock", sm.showClock(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_CLOCK, c); }});

        addDivider(clockCard);
        addThreeWay(clockCard, "Clock size", new String[]{"S", "M", "L"}, sm.getClockSize(),
            new ChoiceCallback() { public void onSelect(int i) { sm.set(SettingsManager.KEY_CLOCK_SIZE, i); }});

        addDivider(clockCard);
        addToggle(clockCard, "24-hour format", sm.is24h(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_CLOCK_24H, c); }});

        addDivider(clockCard);
        addToggle(clockCard, "Show seconds", sm.showSeconds(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_SECONDS, c); }});

        addDivider(clockCard);
        addToggle(clockCard, "Show date", sm.showDate(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_DATE, c); }});

        root.addView(clockCard);

        // ── SEARCH card ─────────────────────────────────────────────────
        LinearLayout searchCard = startCard();
        addSectionLabel(searchCard, "Search");

        addToggle(searchCard, "Show search bar", sm.showSearch(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SHOW_SEARCH, c); }});

        addDivider(searchCard);
        addToggle(searchCard, "Search at bottom", sm.searchBottom(),
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) { sm.set(SettingsManager.KEY_SEARCH_BOTTOM, c); }});

        root.addView(searchCard);

        // ── APPEARANCE card ─────────────────────────────────────────────
        LinearLayout appearCard = startCard();
        addSectionLabel(appearCard, "Appearance");

        addSubLabel(appearCard, "Background");
        appearCard.addView(makeBgPresetPicker());

        addDivider(appearCard);
        addSubLabel(appearCard, "Accent color");
        appearCard.addView(makeAccentPicker());

        addDivider(appearCard);
        addSubLabel(appearCard, "Icon shape");
        appearCard.addView(makeIconShapePicker());

        root.addView(appearCard);

        scroll.addView(root);
        setContentView(scroll);
    }

    // ─── Card container ────────────────────────────────────────────────

    private LinearLayout startCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1AFFFFFF);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x22FFFFFF);
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(16));
        card.setLayoutParams(lp);
        return card;
    }

    private void addSectionLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextColor(accent);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setLetterSpacing(0.14f);
        tv.setPadding(dp(16), dp(16), dp(16), dp(4));
        parent.addView(tv);
    }

    private void addSubLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x88FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(16), dp(12), dp(16), dp(4));
        parent.addView(tv);
    }

    private void addDivider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(16), 0, dp(16), 0);
        v.setLayoutParams(lp);
        parent.addView(v);
    }

    // ─── Row builders ──────────────────────────────────────────────────

    private void addToggle(LinearLayout parent, String label, boolean checked,
                           CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xEEFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        CheckBox cb = new CheckBox(this);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener(listener);

        row.addView(tv);
        row.addView(cb);
        parent.addView(row);
    }

    interface ChoiceCallback { void onSelect(int index); }
    interface SeekCallback   { void onValue(int progress); }

    private void addThreeWay(LinearLayout parent, String label, String[] options,
                             int selected, final ChoiceCallback cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextColor(0xEEFFFFFF);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lv);

        final TextView[] tvs = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView btn = new TextView(this);
            btn.setText(options[i]);
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setTextColor(i == selected ? 0xFF000000 : 0xCCFFFFFF);
            btn.setPadding(dp(14), dp(5), dp(14), dp(5));
            GradientDrawable bgd = new GradientDrawable();
            bgd.setCornerRadius(dp(20));
            bgd.setColor(i == selected ? accent : 0x22FFFFFF);
            btn.setBackground(bgd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMargins(dp(6), 0, 0, 0);
            btn.setLayoutParams(lp);
            tvs[i] = btn;
            row.addView(btn);

            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    cb.onSelect(idx);
                    for (int j = 0; j < tvs.length; j++) {
                        GradientDrawable d = new GradientDrawable();
                        d.setCornerRadius(dp(20));
                        d.setColor(j == idx ? accent : 0x22FFFFFF);
                        tvs[j].setBackground(d);
                        tvs[j].setTextColor(j == idx ? 0xFF000000 : 0xCCFFFFFF);
                    }
                }
            });
        }
        parent.addView(row);
    }

    private void addSeekRow(LinearLayout parent, String label, int min, int max, int current,
                            final int[] ticks, final SeekCallback cb) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, 0, 0, dp(8));

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextColor(0xEEFFFFFF);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valTv = new TextView(this);
        valTv.setText(String.valueOf(ticks[current]));
        valTv.setTextColor(accent);
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        valTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        topRow.addView(lv);
        topRow.addView(valTv);
        col.addView(topRow);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(current);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                valTv.setText(String.valueOf(ticks[p]));
                cb.onValue(p);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        col.addView(sb);
        parent.addView(col);
    }

    // ─── Appearance pickers ────────────────────────────────────────────

    private View makeBgPresetPicker() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setPadding(dp(12), dp(8), dp(12), dp(16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final int current = sm.getBgPreset();
        final String[] names = {"Night", "Ocean", "Navy", "Midnight",
                                "Emerald", "Steel", "Teal", "Charcoal"};
        final View[] circles = new View[SettingsManager.BG_PRESETS.length];

        for (int i = 0; i < SettingsManager.BG_PRESETS.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(6), 0, dp(6), 0);

            View circle = new View(this);
            int size = dp(56);
            circle.setLayoutParams(new LinearLayout.LayoutParams(size, size));

            GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{SettingsManager.BG_PRESETS[i][0], SettingsManager.BG_PRESETS[i][1]});
            gd.setCornerRadius(dp(28));
            if (i == current) gd.setStroke(dp(3), accent);
            circle.setBackground(gd);
            circles[i] = circle;

            TextView name = new TextView(this);
            name.setText(names[i]);
            name.setTextColor(i == current ? accent : 0x66FFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(4), 0, 0);

            final TextView nameRef = name;
            circle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sm.set(SettingsManager.KEY_BG_PRESET, idx);
                    for (int j = 0; j < circles.length; j++) {
                        GradientDrawable d = new GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            new int[]{SettingsManager.BG_PRESETS[j][0], SettingsManager.BG_PRESETS[j][1]});
                        d.setCornerRadius(dp(28));
                        if (j == idx) d.setStroke(dp(3), accent);
                        circles[j].setBackground(d);
                    }
                }
            });

            cell.addView(circle);
            cell.addView(name);
            row.addView(cell);
        }

        hsv.addView(row);
        return hsv;
    }

    private View makeAccentPicker() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(16));

        final View[] dots = new View[SettingsManager.ACCENT_PRESETS.length];
        final int currentAccent = sm.getAccentColor();

        for (int i = 0; i < SettingsManager.ACCENT_PRESETS.length; i++) {
            final int col = SettingsManager.ACCENT_PRESETS[i];
            final int idx = i;
            View dot = new View(this);
            int size = dp(32);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMargins(dp(10), 0, 0, 0);
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
                    accent = col;
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
        row.setPadding(dp(16), dp(8), dp(16), dp(16));

        String[] labels = {"Circle", "Rounded", "Square"};
        final int current = sm.getIconShape();
        final TextView[] btns = new TextView[3];
        float[] radii = {dp(24), dp(10), dp(4)};

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            final float[] radiiRef = radii;
            TextView btn = new TextView(this);
            btn.setText(labels[i]);
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setTextColor(i == current ? 0xFF000000 : 0xCCFFFFFF);
            btn.setPadding(dp(18), dp(8), dp(18), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(radii[i]);
            bg.setColor(i == current ? accent : 0x22FFFFFF);
            btn.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMargins(dp(8), 0, 0, 0);
            btn.setLayoutParams(lp);
            btns[i] = btn;
            row.addView(btn);

            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sm.set(SettingsManager.KEY_ICON_SHAPE, idx);
                    for (int j = 0; j < btns.length; j++) {
                        GradientDrawable d = new GradientDrawable();
                        d.setCornerRadius(radiiRef[j]);
                        d.setColor(j == idx ? accent : 0x22FFFFFF);
                        btns[j].setBackground(d);
                        btns[j].setTextColor(j == idx ? 0xFF000000 : 0xCCFFFFFF);
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
