package com.home.launcher;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {
    private static final String PREFS = "launcher_prefs";
    private final SharedPreferences prefs;

    // Keys
    static final String KEY_COLUMNS        = "columns";
    static final String KEY_ICON_SIZE      = "icon_size";      // 0=small 1=medium 2=large
    static final String KEY_SHOW_LABELS    = "show_labels";
    static final String KEY_LABEL_SIZE     = "label_size";     // 0=small 1=medium 2=large
    static final String KEY_SHOW_CLOCK     = "show_clock";
    static final String KEY_CLOCK_24H      = "clock_24h";
    static final String KEY_SHOW_SECONDS   = "show_seconds";
    static final String KEY_SHOW_DATE      = "show_date";
    static final String KEY_CLOCK_SIZE     = "clock_size";     // 0=small 1=medium 2=large
    static final String KEY_SHOW_SEARCH    = "show_search";
    static final String KEY_SEARCH_BOTTOM  = "search_bottom";
    static final String KEY_BG_PRESET      = "bg_preset";      // 0-7
    static final String KEY_ACCENT_COLOR   = "accent_color";   // ARGB int
    static final String KEY_ICON_SHAPE     = "icon_shape";     // 0=circle 1=rounded 2=square
    static final String KEY_SORT_ORDER     = "sort_order";     // 0=A-Z 1=Z-A

    // Background gradient presets [start, end]
    static final int[][] BG_PRESETS = {
        {0xFF0F0C29, 0xFF24243E}, // Deep purple
        {0xFF000428, 0xFF004E92}, // Ocean blue
        {0xFF1A1A2E, 0xFF16213E}, // Dark navy
        {0xFF0D0D0D, 0xFF1A0533}, // Midnight
        {0xFF11998E, 0xFF38EF7D}, // Emerald (dark)
        {0xFF2C3E50, 0xFF4CA1AF}, // Steel
        {0xFF360033, 0xFF0B8793}, // Deep teal
        {0xFF1C1C1C, 0xFF3D3D3D}, // Charcoal
    };

    // Accent color presets
    static final int[] ACCENT_PRESETS = {
        0xFFBB86FC, // Purple (default)
        0xFF03DAC6, // Teal
        0xFFFF6B6B, // Red
        0xFFFFD93D, // Yellow
        0xFF6BCB77, // Green
        0xFF4D96FF, // Blue
        0xFFFF922B, // Orange
        0xFFFF6EFF, // Pink
    };

    public SettingsManager(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int getColumns()       { return prefs.getInt(KEY_COLUMNS, 4); }
    public int getIconSize()      { return prefs.getInt(KEY_ICON_SIZE, 1); }
    public boolean showLabels()   { return prefs.getBoolean(KEY_SHOW_LABELS, true); }
    public int getLabelSize()     { return prefs.getInt(KEY_LABEL_SIZE, 1); }
    public boolean showClock()    { return prefs.getBoolean(KEY_SHOW_CLOCK, true); }
    public boolean is24h()        { return prefs.getBoolean(KEY_CLOCK_24H, true); }
    public boolean showSeconds()  { return prefs.getBoolean(KEY_SHOW_SECONDS, false); }
    public boolean showDate()     { return prefs.getBoolean(KEY_SHOW_DATE, true); }
    public int getClockSize()     { return prefs.getInt(KEY_CLOCK_SIZE, 1); }
    public boolean showSearch()   { return prefs.getBoolean(KEY_SHOW_SEARCH, true); }
    public boolean searchBottom() { return prefs.getBoolean(KEY_SEARCH_BOTTOM, false); }
    public int getBgPreset()      { return prefs.getInt(KEY_BG_PRESET, 0); }
    public int getAccentColor()   { return prefs.getInt(KEY_ACCENT_COLOR, 0xFFBB86FC); }
    public int getIconShape()     { return prefs.getInt(KEY_ICON_SHAPE, 0); }
    public int getSortOrder()     { return prefs.getInt(KEY_SORT_ORDER, 0); }

    public void set(String key, int val)     { prefs.edit().putInt(key, val).apply(); }
    public void set(String key, boolean val) { prefs.edit().putBoolean(key, val).apply(); }

    public int getIconSizeDp() {
        switch (getIconSize()) {
            case 0: return 44;
            case 2: return 68;
            default: return 56;
        }
    }

    public int getClockSizeSp() {
        switch (getClockSize()) {
            case 0: return 56;
            case 2: return 96;
            default: return 76;
        }
    }

    public int getLabelSizeSp() {
        switch (getLabelSize()) {
            case 0: return 10;
            case 2: return 13;
            default: return 11;
        }
    }
}
