package com.home.launcher;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {
    private static final String PREFS = "launcher_prefs";
    private final SharedPreferences prefs;

    static final String KEY_COLUMNS             = "columns";
    static final String KEY_ICON_SIZE           = "icon_size";
    static final String KEY_SHOW_LABELS         = "show_labels";
    static final String KEY_LABEL_SIZE          = "label_size";
    static final String KEY_SHOW_CLOCK          = "show_clock";
    static final String KEY_CLOCK_24H           = "clock_24h";
    static final String KEY_SHOW_SECONDS        = "show_seconds";
    static final String KEY_SHOW_DATE           = "show_date";
    static final String KEY_CLOCK_SIZE          = "clock_size";
    static final String KEY_SHOW_SEARCH         = "show_search";
    static final String KEY_SEARCH_BOTTOM       = "search_bottom";
    static final String KEY_BG_PRESET           = "bg_preset";
    static final String KEY_ACCENT_COLOR        = "accent_color";
    static final String KEY_ICON_SHAPE          = "icon_shape";
    static final String KEY_SORT_ORDER          = "sort_order";
    static final String KEY_USE_SYSTEM_WP       = "use_system_wp";
    static final String KEY_WP_DIM              = "wp_dim";
    static final String KEY_DOCK_ENABLED        = "dock_enabled";
    static final String KEY_DOCK_PACKAGES       = "dock_packages";

    static final int[][] BG_PRESETS = {
        {0xFF0F0C29, 0xFF24243E}, // Deep Purple
        {0xFF000428, 0xFF004E92}, // Ocean Blue
        {0xFF1A1A2E, 0xFF16213E}, // Dark Navy
        {0xFF0D0D0D, 0xFF1A0533}, // Midnight
        {0xFF11998E, 0xFF38EF7D}, // Emerald
        {0xFF2C3E50, 0xFF4CA1AF}, // Steel Blue
        {0xFF360033, 0xFF0B8793}, // Deep Teal
        {0xFF1C1C1C, 0xFF3D3D3D}, // Charcoal
        {0xFF870000, 0xFF190A05}, // Dark Red
        {0xFF4A00E0, 0xFF8E2DE2}, // Violet
        {0xFF093028, 0xFF237A57}, // Forest
        {0xFF141E30, 0xFF243B55}, // Slate
    };

    static final String[] BG_NAMES = {
        "Night", "Ocean", "Navy", "Midnight",
        "Emerald", "Steel", "Teal", "Charcoal",
        "Crimson", "Violet", "Forest", "Slate"
    };

    static final int[] ACCENT_PRESETS = {
        0xFFBB86FC, // Purple
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

    public int     getColumns()          { return prefs.getInt(KEY_COLUMNS, 4); }
    public int     getIconSize()         { return prefs.getInt(KEY_ICON_SIZE, 1); }
    public boolean showLabels()          { return prefs.getBoolean(KEY_SHOW_LABELS, true); }
    public int     getLabelSize()        { return prefs.getInt(KEY_LABEL_SIZE, 1); }
    public boolean showClock()           { return prefs.getBoolean(KEY_SHOW_CLOCK, true); }
    public boolean is24h()               { return prefs.getBoolean(KEY_CLOCK_24H, true); }
    public boolean showSeconds()         { return prefs.getBoolean(KEY_SHOW_SECONDS, false); }
    public boolean showDate()            { return prefs.getBoolean(KEY_SHOW_DATE, true); }
    public int     getClockSize()        { return prefs.getInt(KEY_CLOCK_SIZE, 1); }
    public boolean showSearch()          { return prefs.getBoolean(KEY_SHOW_SEARCH, true); }
    public boolean searchBottom()        { return prefs.getBoolean(KEY_SEARCH_BOTTOM, false); }
    public int     getBgPreset()         { return prefs.getInt(KEY_BG_PRESET, 0); }
    public int     getAccentColor()      { return prefs.getInt(KEY_ACCENT_COLOR, 0xFFBB86FC); }
    public int     getIconShape()        { return prefs.getInt(KEY_ICON_SHAPE, 0); }
    public int     getSortOrder()        { return prefs.getInt(KEY_SORT_ORDER, 0); }
    public boolean useSystemWallpaper()  { return prefs.getBoolean(KEY_USE_SYSTEM_WP, false); }
    public int     getWpDim()            { return prefs.getInt(KEY_WP_DIM, 40); }
    public boolean dockEnabled()         { return prefs.getBoolean(KEY_DOCK_ENABLED, true); }
    public String  getDockPackages()     { return prefs.getString(KEY_DOCK_PACKAGES, ""); }

    public void set(String key, int val)     { prefs.edit().putInt(key, val).apply(); }
    public void set(String key, boolean val) { prefs.edit().putBoolean(key, val).apply(); }
    public void set(String key, String val)  { prefs.edit().putString(key, val).apply(); }

    public int getIconSizeDp() {
        switch (getIconSize()) { case 0: return 44; case 2: return 68; default: return 56; }
    }
    public int getClockSizeSp() {
        switch (getClockSize()) { case 0: return 56; case 2: return 92; default: return 72; }
    }
    public int getLabelSizeSp() {
        switch (getLabelSize()) { case 0: return 10; case 2: return 13; default: return 11; }
    }
}
