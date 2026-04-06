package com.home.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private FrameLayout rootFrame;
    private TextView clockView, dateView;
    private EditText searchBar;
    private GridView appGrid;

    private List<AppInfo> allApps      = new ArrayList<AppInfo>();
    private List<AppInfo> filteredApps = new ArrayList<AppInfo>();
    private AppAdapter adapter;
    private SettingsManager sm;

    private String pendingUninstallPkg = null;
    private BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String pkg = i.getData() != null ? i.getData().getSchemeSpecificPart() : null;
            if (pkg == null) return;
            if (pkg.equals(pendingUninstallPkg)) {
                pendingUninstallPkg = null;
                poofRemovedApp(pkg);
            }
        }
    };

    // Filter state
    private String currentCategory = "";  // "" = ALL
    private String currentLetter   = "";  // "" = all letters
    private String currentSearch   = "";

    private LinearLayout catPillRow;
    private LinearLayout azRow;
    private LinearLayout filterBar;
    private TextView activeLetterPill = null;
    private TextView activeCatPill    = null;

    private Handler clockHandler = new Handler();
    private Runnable clockRunnable;
    private float touchDownY;
    private KeyguardManager km;
    private boolean showingLock = false;
    private TextView lockClockView, lockDateView;

    // AppWidget support
    private android.appwidget.AppWidgetHost appWidgetHost;
    private android.appwidget.AppWidgetManager appWidgetManager;
    private LinearLayout widgetContainer;
    private int pendingWidgetId = -1;
    private static final int WIDGET_HOST_ID  = 1024;
    private static final int REQ_PICK_WIDGET = 2001;
    private static final int REQ_BIND_WIDGET = 2002;

    private static final String[] CATEGORIES = {
        "ALL", "GAMES", "SOCIAL", "MEDIA", "TOOLS", "BROWSER",
        "FINANCE", "HEALTH", "SHOPPING", "EDUCATION", "SYSTEM", "OTHER"
    };

    private BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        sm = new SettingsManager(this);
        km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        appWidgetHost = new android.appwidget.AppWidgetHost(this, WIDGET_HOST_ID);
        appWidgetManager = android.appwidget.AppWidgetManager.getInstance(this);
        // Register package receiver for the lifetime of this activity
        IntentFilter pkgFilter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);
        buildUI();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) {}
        try { appWidgetHost.stopListening(); } catch (Exception ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        sm = new SettingsManager(this);
        adapter = null;
        appWidgetHost.startListening();
        buildUI();
        startClock();
        registerReceiver(timeReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
        // Auto-check for updates once per day (silent — only acts if update found)
        long now = System.currentTimeMillis();
        if (now - sm.getLastUpdateCheck() > 86400000L) {
            sm.setLastUpdateCheck(now);
            final UpdateManager um = new UpdateManager(this);
            um.checkForUpdate(new UpdateManager.CheckCallback() {
                @Override public void onResult(Boolean available, int serverVer) {
                    if (available != null && available) {
                        showUpdatePrompt(serverVer, um);
                    }
                }
            });
        }
    }

    @Override protected void onPause() {
        super.onPause();
        stopClock();
        try { unregisterReceiver(timeReceiver); } catch (Exception ignored) {}
        try { appWidgetHost.stopListening(); } catch (Exception ignored) {}
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) touchDownY = e.getY();
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float dy = touchDownY - e.getY(); // positive = swipe up
            float downDy = e.getY() - touchDownY; // positive = swipe down
            // Swipe down from top = notifications (legacy)
            if (downDy > dp(60) && touchDownY < dp(120)) expandNotifications();
            // Swipe up anywhere
            if (dy > dp(80)) {
                int action = sm.getSwipeUpAction();
                if (action == 1 && searchBar != null) {
                    searchBar.requestFocus();
                } else if (action == 2) {
                    expandNotifications();
                }
            }
        }
        return super.onTouchEvent(e);
    }

    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class<?> cls = Class.forName("android.app.StatusBarManager");
            Method m = cls.getMethod("expandNotificationsPanel");
            m.invoke(sb);
        } catch (Exception ignored) {}
    }

    // ─── Build UI ─────────────────────────────────────────────────────────

    private void buildUI() {
        showingLock = sm.lockScreenEnabled() && km != null && km.isKeyguardLocked();

        rootFrame = new FrameLayout(this);

        if (showingLock) {
            buildLockUI();
            setContentView(rootFrame);
            return;
        }

        // Clear any previously set dismiss-keyguard flag when returning to home
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        if (sm.useSystemWallpaper())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);

        applyBackground();
        rootFrame.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showHomeLongPressMenu(); return true; }
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        if (!isDefaultLauncher()) content.addView(buildSetupBanner());
        content.addView(buildClockSection());
        if (sm.showSearch() && !sm.searchBottom()) content.addView(buildSearchSection());
        content.addView(buildAppWidgetArea());
        content.addView(buildGrid());
        if (sm.showSearch() && sm.searchBottom()) content.addView(buildSearchSection());
        content.addView(buildFilterBar());
        if (sm.dockEnabled()) content.addView(buildDock());

        rootFrame.addView(content, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        updateClock();
        loadApps();
    }

    // ─── Lock screen ──────────────────────────────────────────────────────

    private void buildLockUI() {
        int bgIdx = sm.getLockScreenBg();
        int[][] presets = SettingsManager.LOCK_BG_PRESETS;
        int[] colors = presets[Math.min(bgIdx, presets.length - 1)];
        GradientDrawable lockBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        rootFrame.setBackground(lockBg);
        int accent = sm.getAccentColor();

        // Full-height vertical layout
        LinearLayout lock = new LinearLayout(this);
        lock.setOrientation(LinearLayout.VERTICAL);
        lock.setGravity(Gravity.CENTER_HORIZONTAL);
        rootFrame.addView(lock, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Swipe-up touch on the lock view itself (not rootFrame — child intercepts parent)
        lock.setOnTouchListener(new View.OnTouchListener() {
            private float downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { downY = e.getY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (downY - e.getY() > dp(60)) { unlockAndShowHome(); return true; }
                }
                return true;
            }
        });

        // ── Top spacer
        View topSpacer = new View(this);
        lock.addView(topSpacer, new LinearLayout.LayoutParams(0, 0, 1f));

        // ── Clock block
        LinearLayout clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        clockBlock.setGravity(Gravity.CENTER);
        clockBlock.setPadding(dp(32), 0, dp(32), 0);

        lockClockView = new TextView(this);
        lockClockView.setTextColor(0xFFFFFFFF);
        lockClockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 88);
        lockClockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        lockClockView.setGravity(Gravity.CENTER);
        lockClockView.setLetterSpacing(-0.05f);
        lockClockView.setShadowLayer(dp(20), 0, dp(4), 0x33000000);
        clockBlock.addView(lockClockView);

        lockDateView = new TextView(this);
        lockDateView.setTextColor(0x99FFFFFF);
        lockDateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lockDateView.setLetterSpacing(0.15f);
        lockDateView.setGravity(Gravity.CENTER);
        lockDateView.setPadding(0, dp(4), 0, dp(24));
        clockBlock.addView(lockDateView);

        // Thin accent line under date
        View accentLine = new View(this);
        accentLine.setBackgroundColor(accent);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(dp(48), dp(2));
        lineLp.gravity = Gravity.CENTER_HORIZONTAL;
        accentLine.setLayoutParams(lineLp);
        clockBlock.addView(accentLine);

        lock.addView(clockBlock);

        // ── Battery widget
        lock.addView(buildLockBatteryView(accent));

        // ── Bottom spacer
        View botSpacer = new View(this);
        lock.addView(botSpacer, new LinearLayout.LayoutParams(0, 0, 1f));

        // ── Swipe hint at bottom
        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(48));

        View upChevron = new View(this) {
            @Override protected void onDraw(Canvas canvas) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0x66FFFFFF);
                p.setStrokeWidth(dp(2));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                float sz = dp(12);
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(cx - sz, cy + sz / 2f);
                path.lineTo(cx, cy - sz / 2f);
                path.lineTo(cx + sz, cy + sz / 2f);
                canvas.drawPath(path, p);
            }
        };
        upChevron.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(24)));

        TextView swipeTv = new TextView(this);
        swipeTv.setText("SWIPE UP TO UNLOCK");
        swipeTv.setTextColor(0x55FFFFFF);
        swipeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        swipeTv.setLetterSpacing(0.2f);
        swipeTv.setGravity(Gravity.CENTER);
        swipeTv.setPadding(0, dp(6), 0, 0);

        hint.addView(upChevron);
        hint.addView(swipeTv);
        lock.addView(hint);

        updateLockClock();
    }

    private View buildLockBatteryView(int accent) {
        Intent battIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battIntent != null ? battIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = battIntent != null ? battIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        int pct = (level >= 0 && scale > 0) ? (level * 100 / scale) : 50;
        boolean charging = battIntent != null &&
            battIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING;

        LinearLayout bv = new LinearLayout(this);
        bv.setOrientation(LinearLayout.VERTICAL);
        bv.setGravity(Gravity.CENTER);
        bv.setPadding(dp(48), dp(32), dp(48), 0);

        // Battery bar track
        FrameLayout track = new FrameLayout(this);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(200), dp(4));
        trackLp.gravity = Gravity.CENTER_HORIZONTAL;
        track.setLayoutParams(trackLp);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x22FFFFFF);
        trackBg.setCornerRadius(dp(2));
        track.setBackground(trackBg);

        // Battery fill
        View fill = new View(this);
        int fillColor = pct > 20 ? (charging ? accent : 0xFF6BCB77) : 0xFFFF5555;
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(fillColor);
        fillBg.setCornerRadius(dp(2));
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(
            (int)(dp(200) * pct / 100f), FrameLayout.LayoutParams.MATCH_PARENT);
        fill.setLayoutParams(fillLp);
        fill.setBackground(fillBg);
        track.addView(fill);
        bv.addView(track);

        // Percentage text
        TextView pctTv = new TextView(this);
        pctTv.setText(pct + "%" + (charging ? "  CHARGING" : ""));
        pctTv.setTextColor(0x66FFFFFF);
        pctTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        pctTv.setLetterSpacing(0.12f);
        pctTv.setGravity(Gravity.CENTER);
        pctTv.setPadding(0, dp(8), 0, 0);
        bv.addView(pctTv);

        return bv;
    }

    private void unlockAndShowHome() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        showingLock = false;
        adapter = null;
        buildUI();
    }

    private void updateLockClock() {
        if (lockClockView == null) return;
        Date now = new Date();
        String fmt = sm.is24h() ? "HH:mm" : "h:mm";
        lockClockView.setText(new SimpleDateFormat(fmt, Locale.getDefault()).format(now));
        if (lockDateView != null)
            lockDateView.setText(
                new SimpleDateFormat("EEEE  \u2022  MMMM d", Locale.getDefault())
                    .format(now).toUpperCase(Locale.getDefault()));
    }

    // ─── Widget row ───────────────────────────────────────────────────────

    private View buildWidgetRow() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(dp(12), dp(4), dp(12), dp(4));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(4));

        if (sm.widgetCalendar()) row.addView(buildCalendarWidget());
        if (sm.widgetBattery())  row.addView(buildBatteryWidget());
        if (sm.widgetNotes())    row.addView(buildNotesWidget());

        hsv.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(2));
        hsv.setLayoutParams(lp);
        return hsv;
    }

    private LinearLayout makeWidgetCard(int widthDp, int heightDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(16));
        bg.setStroke(1, 0x1EFFFFFF);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        lp.setMargins(0, 0, dp(10), 0);
        card.setLayoutParams(lp);
        return card;
    }

    private View buildCalendarWidget() {
        LinearLayout card = makeWidgetCard(120, 100);
        Date now = new Date();
        int accent = sm.getAccentColor();

        TextView monthTv = new TextView(this);
        monthTv.setText(new SimpleDateFormat("MMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        monthTv.setTextColor(accent);
        monthTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        monthTv.setLetterSpacing(0.14f);
        monthTv.setGravity(Gravity.CENTER);

        TextView dayTv = new TextView(this);
        dayTv.setText(new SimpleDateFormat("d", Locale.getDefault()).format(now));
        dayTv.setTextColor(0xFFFFFFFF);
        dayTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        dayTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        dayTv.setGravity(Gravity.CENTER);
        dayTv.setPadding(0, dp(2), 0, dp(2));

        TextView weekTv = new TextView(this);
        weekTv.setText(new SimpleDateFormat("EEE", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        weekTv.setTextColor(0x55FFFFFF);
        weekTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        weekTv.setLetterSpacing(0.1f);
        weekTv.setGravity(Gravity.CENTER);

        card.addView(monthTv);
        card.addView(dayTv);
        card.addView(weekTv);
        return card;
    }

    private View buildBatteryWidget() {
        Intent battIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battIntent != null ? battIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = battIntent != null ? battIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        int pct   = (level >= 0 && scale > 0) ? (level * 100 / scale) : 0;
        boolean charging = battIntent != null &&
            battIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING;
        int accent = sm.getAccentColor();

        LinearLayout card = makeWidgetCard(120, 100);

        TextView labelTv = new TextView(this);
        labelTv.setText(charging ? "CHARGING" : "BATTERY");
        labelTv.setTextColor(charging ? accent : 0x55FFFFFF);
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        labelTv.setLetterSpacing(0.14f);
        labelTv.setGravity(Gravity.CENTER);

        TextView pctTv = new TextView(this);
        pctTv.setText(pct + "%");
        pctTv.setTextColor(0xFFFFFFFF);
        pctTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        pctTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        pctTv.setGravity(Gravity.CENTER);
        pctTv.setPadding(0, dp(4), 0, dp(6));

        // Mini bar
        FrameLayout track = new FrameLayout(this);
        track.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(3)));
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x22FFFFFF);
        trackBg.setCornerRadius(dp(2));
        track.setBackground(trackBg);
        View fill = new View(this);
        int fillColor = pct > 20 ? (charging ? accent : 0xFF6BCB77) : 0xFFFF5555;
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(fillColor);
        fillBg.setCornerRadius(dp(2));
        fill.setLayoutParams(new FrameLayout.LayoutParams((int)(dp(72) * pct / 100f), FrameLayout.LayoutParams.MATCH_PARENT));
        fill.setBackground(fillBg);
        track.addView(fill);

        card.addView(labelTv);
        card.addView(pctTv);
        card.addView(track);
        return card;
    }

    private View buildNotesWidget() {
        final LinearLayout card = makeWidgetCard(160, 100);
        final String note = sm.getNotesText();

        TextView labelTv = new TextView(this);
        labelTv.setText("NOTES");
        labelTv.setTextColor(sm.getAccentColor());
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        labelTv.setLetterSpacing(0.14f);
        labelTv.setGravity(Gravity.CENTER);

        final TextView noteTv = new TextView(this);
        noteTv.setText(note.isEmpty() ? "tap to add" : note);
        noteTv.setTextColor(note.isEmpty() ? 0x44FFFFFF : 0xCCFFFFFF);
        noteTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        noteTv.setMaxLines(3);
        noteTv.setPadding(0, dp(6), 0, 0);

        card.addView(labelTv);
        card.addView(noteTv);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showNoteEditor(); }
        });
        return card;
    }

    private void showNoteEditor() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF17161F);
        bg.setCornerRadius(dp(16));
        bg.setStroke(1, 0x33FFFFFF);
        layout.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("QUICK NOTE");
        title.setTextColor(sm.getAccentColor());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        final EditText et = new EditText(this);
        et.setText(sm.getNotesText());
        et.setTextColor(0xEEFFFFFF);
        et.setHintTextColor(0x44FFFFFF);
        et.setHint("Write something...");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setMinLines(3);
        et.setMaxLines(6);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(0x18FFFFFF);
        etBg.setCornerRadius(dp(10));
        et.setBackground(etBg);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.setMargins(0, dp(12), 0, dp(12));
        et.setLayoutParams(etLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView saveBtn = new TextView(this);
        saveBtn.setText("Save");
        saveBtn.setTextColor(0xFF000000);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        saveBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        saveBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(sm.getAccentColor());
        saveBg.setCornerRadius(dp(20));
        saveBtn.setBackground(saveBg);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sm.set(SettingsManager.KEY_NOTES_TEXT, et.getText().toString().trim());
                dialog.dismiss();
                adapter = null; buildUI();
            }
        });

        btnRow.addView(saveBtn);
        layout.addView(title);
        layout.addView(et);
        layout.addView(btnRow);
        dialog.setContentView(layout);

        WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
        wlp.width = dp(300);
        dialog.getWindow().setAttributes(wlp);
        dialog.show();
    }

    private boolean isDefaultLauncher() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private View buildSetupBanner() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(52), dp(16), 0);
        card.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x22FFFFFF);
        bg.setCornerRadius(dp(14));
        bg.setStroke(1, 0x33FFFFFF);
        card.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("NOT SET AS DEFAULT LAUNCHER");
        title.setTextColor(sm.getAccentColor());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(0.1f);
        card.addView(title);

        TextView steps = new TextView(this);
        steps.setText("To set as your home screen:\nSettings  →  Apps  →  Default apps  →  Home app  →  select Home Launcher");
        steps.setTextColor(0xBBFFFFFF);
        steps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        steps.setPadding(0, dp(6), 0, dp(12));
        card.addView(steps);

        TextView btn = new TextView(this);
        btn.setText("Open Default App Settings");
        btn.setTextColor(0xFF000000);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(sm.getAccentColor());
        btnBg.setCornerRadius(dp(20));
        btn.setBackground(btnBg);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"));
                    } catch (Exception e2) {
                        startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                    }
                }
            }
        });
        card.addView(btn);
        return card;
    }

    private void applyBackground() {
        String customUri = sm.getCustomWpUri();
        if (customUri != null && !customUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(customUri);
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = 2;
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is, null, opts);
                if (is != null) is.close();
                if (bmp != null) {
                    int dimAlpha = (int)(sm.getWpDim() / 100f * 220);
                    Bitmap dimmed = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(dimmed);
                    c.drawBitmap(bmp, 0, 0, null);
                    Paint p = new Paint();
                    p.setColor(Color.argb(dimAlpha, 0, 0, 0));
                    c.drawRect(0, 0, dimmed.getWidth(), dimmed.getHeight(), p);
                    bmp.recycle();
                    rootFrame.setBackground(new BitmapDrawable(getResources(), dimmed));
                    return;
                }
            } catch (Exception ignored) {}
        }
        if (sm.useSystemWallpaper()) {
            int alpha = (int)(sm.getWpDim() / 100f * 255);
            rootFrame.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
        } else {
            int[] bg = SettingsManager.BG_PRESETS[sm.getBgPreset()];
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{bg[0], bg[1]});
            rootFrame.setBackground(g);
        }
    }

    // ─── Clock ────────────────────────────────────────────────────────────

    private View buildClockSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setGravity(Gravity.CENTER_HORIZONTAL);
        sec.setPadding(dp(24), dp(60), dp(24), dp(2));
        sec.setVisibility(sm.showClock() ? View.VISIBLE : View.GONE);

        clockView = new TextView(this);
        clockView.setTextColor(0xFFFFFFFF);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sm.getClockSizeSp());
        clockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clockView.setLetterSpacing(-0.04f);
        clockView.setGravity(Gravity.CENTER);

        dateView = new TextView(this);
        dateView.setTextColor(0x66FFFFFF);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dateView.setLetterSpacing(0.08f);
        dateView.setGravity(Gravity.CENTER);
        dateView.setPadding(0, dp(3), 0, 0);
        dateView.setVisibility(sm.showDate() ? View.VISIBLE : View.GONE);

        sec.addView(clockView);
        sec.addView(dateView);
        return sec;
    }

    // ─── Search ───────────────────────────────────────────────────────────

    private View buildSearchSection() {
        LinearLayout outer = new LinearLayout(this);
        outer.setPadding(dp(20), dp(10), dp(20), dp(4));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(20), 0, dp(16), 0);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(26));
        pillBg.setColor(0x18FFFFFF);
        pill.setBackground(pillBg);
        pill.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // Magnifier symbol using a plain Unicode char
        TextView searchIcon = new TextView(this);
        searchIcon.setText("\uD83D\uDD0D");
        searchIcon.setTextColor(0x40FFFFFF);
        searchIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        searchIcon.setPadding(0, 0, dp(10), 0);

        searchBar = new EditText(this);
        searchBar.setHint("Search apps");
        searchBar.setTextColor(0xFFFFFFFF);
        searchBar.setHintTextColor(0x40FFFFFF);
        searchBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchBar.setBackground(null);
        searchBar.setSingleLine(true);
        searchBar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBar.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                currentSearch = s.toString();
                applyFilters();
                if (filterBar != null)
                    filterBar.setVisibility(currentSearch.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });

        pill.addView(searchIcon);
        pill.addView(searchBar);
        outer.addView(pill);
        return outer;
    }

    // ─── Grid ─────────────────────────────────────────────────────────────

    private GridView buildGrid() {
        appGrid = new GridView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        appGrid.setLayoutParams(lp);
        appGrid.setNumColumns(sm.getColumns());
        appGrid.setVerticalSpacing(dp(6));
        appGrid.setHorizontalSpacing(dp(6));
        appGrid.setPadding(dp(14), dp(8), dp(14), dp(8));
        appGrid.setClipToPadding(false);
        appGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        appGrid.setScrollbarFadingEnabled(true);
        appGrid.setBackground(null);
        return appGrid;
    }

    // ─── Filter bar (categories + A-Z) ───────────────────────────────────

    private View buildFilterBar() {
        filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.VERTICAL);
        filterBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        filterBar.setVisibility(View.GONE); // hidden unless search is active
        LinearLayout bar = filterBar;

        // thin separator
        View sep = new View(this);
        sep.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        sepLp.setMargins(dp(24), 0, dp(24), 0);
        sep.setLayoutParams(sepLp);
        bar.addView(sep);

        // A-Z row
        HorizontalScrollView azScroll = new HorizontalScrollView(this);
        azScroll.setHorizontalScrollBarEnabled(false);
        azScroll.setPadding(dp(10), dp(6), dp(10), dp(2));

        azRow = new LinearLayout(this);
        azRow.setOrientation(LinearLayout.HORIZONTAL);
        azRow.setGravity(Gravity.CENTER_VERTICAL);

        for (int i = 0; i < 26; i++) {
            final String letter = String.valueOf((char)('A' + i));
            final TextView pill = new TextView(this);
            pill.setText(letter);
            pill.setTextColor(0xAAFFFFFF);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            pill.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.setMargins(dp(2), 0, dp(2), 0);
            pill.setLayoutParams(lp);
            setLetterPillInactive(pill);
            pill.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (currentLetter.equals(letter)) {
                        currentLetter = "";
                        setLetterPillInactive(pill);
                        activeLetterPill = null;
                    } else {
                        if (activeLetterPill != null) setLetterPillInactive(activeLetterPill);
                        currentLetter = letter;
                        setLetterPillActive(pill);
                        activeLetterPill = pill;
                    }
                    applyFilters();
                }
            });
            azRow.addView(pill);
        }

        azScroll.addView(azRow);
        bar.addView(azScroll);

        // Category row
        HorizontalScrollView catScroll = new HorizontalScrollView(this);
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.setPadding(dp(10), dp(4), dp(10), dp(10));

        catPillRow = new LinearLayout(this);
        catPillRow.setOrientation(LinearLayout.HORIZONTAL);
        catPillRow.setGravity(Gravity.CENTER_VERTICAL);

        for (final String cat : CATEGORIES) {
            final TextView pill = new TextView(this);
            pill.setText(cat);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            pill.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
            lp.setMargins(dp(3), 0, dp(3), 0);
            pill.setLayoutParams(lp);
            pill.setPadding(dp(14), 0, dp(14), 0);

            boolean isAll = cat.equals("ALL");
            if (isAll) {
                setCatPillActive(pill);
                activeCatPill = pill;
            } else {
                setCatPillInactive(pill);
            }

            pill.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (activeCatPill != null) setCatPillInactive(activeCatPill);
                    currentCategory = cat.equals("ALL") ? "" : cat;
                    setCatPillActive(pill);
                    activeCatPill = pill;
                    applyFilters();
                }
            });
            catPillRow.addView(pill);
        }

        catScroll.addView(catPillRow);
        bar.addView(catScroll);
        return filterBar;
    }

    private void setLetterPillActive(TextView pill) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(5));
        bg.setColor(sm.getAccentColor());
        pill.setBackground(bg);
        pill.setTextColor(0xFFFFFFFF);
    }
    private void setLetterPillInactive(TextView pill) {
        pill.setBackground(null);
        pill.setTextColor(0x66FFFFFF);
    }
    private void setCatPillActive(TextView pill) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(sm.getAccentColor());
        pill.setBackground(bg);
        pill.setTextColor(0xFFFFFFFF);
    }
    private void setCatPillInactive(TextView pill) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x18FFFFFF);
        bg.setStroke(1, 0x28FFFFFF);
        pill.setBackground(bg);
        pill.setTextColor(0x88FFFFFF);
    }

    // ─── Dock ─────────────────────────────────────────────────────────────

    private View buildDock() {
        // Outer container with bottom padding for nav bar
        LinearLayout dockWrap = new LinearLayout(this);
        dockWrap.setOrientation(LinearLayout.VERTICAL);
        dockWrap.setPadding(dp(20), dp(6), dp(20), dp(24));

        // Frosted-glass pill
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x22FFFFFF);
        pillBg.setCornerRadius(dp(30));
        pillBg.setStroke(1, 0x18FFFFFF);
        pill.setBackground(pillBg);

        String[] wantedPkgs = {
            "com.android.dialer","com.google.android.dialer",
            "com.android.mms","com.google.android.apps.messaging",
            "com.android.camera2","com.google.android.GoogleCamera",
            "com.android.chrome","com.google.android.apps.chrome",
            "com.android.settings"
        };
        List<AppInfo> dockApps = new ArrayList<AppInfo>();
        outer:
        for (String pkg : wantedPkgs) {
            for (AppInfo a : allApps)
                if (a.packageName.equals(pkg)) { dockApps.add(a); if (dockApps.size() >= 5) break outer; break; }
        }
        for (int i = 0; i < allApps.size() && dockApps.size() < 4; i++) {
            AppInfo a = allApps.get(i);
            boolean already = false;
            for (AppInfo d : dockApps) if (d.packageName.equals(a.packageName)) { already = true; break; }
            if (!already) dockApps.add(a);
        }

        int iconDp = sm.getIconSizeDp();
        for (final AppInfo app : dockApps) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cell.setLayoutParams(cellLp);
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(shapedIcon(app.icon, dp(iconDp), sm.getIconShape()));
            cell.addView(iv);
            pill.addView(cell);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction(new Runnable() {
                        @Override public void run() { v.animate().scaleX(1f).scaleY(1f).setDuration(100).start(); }
                    }).start();
                    launchApp(app);
                }
            });
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { showAppMenu(app, v); return true; }
            });
        }
        dockWrap.addView(pill);
        return dockWrap;
    }

    // ─── Apps ─────────────────────────────────────────────────────────────

    private void loadApps() {
        allApps.clear();
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                PackageManager p = getPackageManager();
                String la = a.loadLabel(p).toString(), lb = b.loadLabel(p).toString();
                return sm.getSortOrder() == 0 ? la.compareToIgnoreCase(lb) : lb.compareToIgnoreCase(la);
            }
        });
        for (ResolveInfo ri : list) {
            AppInfo info      = new AppInfo();
            info.label        = ri.loadLabel(pm).toString();
            info.icon         = ri.loadIcon(pm);
            info.packageName  = ri.activityInfo.packageName;
            info.activityName = ri.activityInfo.name;
            info.category     = detectCategory(info);
            allApps.add(info);
        }
        applyFilters();
    }

    private void applyFilters() {
        filteredApps.clear();
        for (AppInfo a : allApps) {
            if (!currentCategory.isEmpty() && !currentCategory.equals(a.category)) continue;
            if (!currentLetter.isEmpty() && !a.label.toUpperCase(Locale.getDefault()).startsWith(currentLetter)) continue;
            if (!currentSearch.isEmpty() && !a.label.toLowerCase(Locale.getDefault()).contains(currentSearch.toLowerCase(Locale.getDefault()))) continue;
            filteredApps.add(a);
        }
        if (adapter == null) {
            adapter = new AppAdapter();
            appGrid.setAdapter(adapter);
            appGrid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override public void onItemClick(android.widget.AdapterView<?> p, final View v, int pos, long id) {
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction(new Runnable() {
                        @Override public void run() { v.animate().scaleX(1f).scaleY(1f).setDuration(100).start(); }
                    }).start();
                    launchApp(filteredApps.get(pos));
                }
            });
            appGrid.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                    showAppMenu(filteredApps.get(pos), v); return true;
                }
            });
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void launchApp(AppInfo app) {
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setClassName(app.packageName, app.activityName);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(launch); }
        catch (Exception e) { Toast.makeText(this, "Can't open " + app.label, Toast.LENGTH_SHORT).show(); }
    }

    // ─── Category heuristics ──────────────────────────────────────────────

    static String detectCategory(AppInfo a) {
        String s = (a.packageName + " " + a.label).toLowerCase(Locale.getDefault());
        if (contains(s, "game","minecraft","pubg","roblox","clash","chess","puzzle","rpg","arcade","sonic","mario","fortnite","candy","angry","bird","shoot","racing","fifa","nba","mlb","nfl","brawl","pokemon","hearthstone","dungeon","ludo","snake","tetris","solitaire","mahjong")) return "GAMES";
        if (contains(s, "instagram","facebook","twitter","whatsapp","telegram","snapchat","tiktok","discord","reddit","linkedin","messenger","signal","viber","wechat","line","skype","kik","tumblr","pinterest","mastodon")) return "SOCIAL";
        if (contains(s, "spotify","netflix","youtube","music","video","player","media","podcast","vlc","plex","tidal","deezer","soundcloud","audible","twitch","hulu","disney","amazon.video","prime.video","photos","gallery","camera","photo","film")) return "MEDIA";
        if (contains(s, "chrome","firefox","opera","brave","browser","edge","safari","duckduck","internet","dolphin","web","surf")) return "BROWSER";
        if (contains(s, "bank","finance","money","paypal","cash","venmo","wallet","invest","crypto","bitcoin","trading","insurance","tax","mint","robinhood","coinbase")) return "FINANCE";
        if (contains(s, "health","fitness","workout","gym","run","calories","diet","yoga","meditat","sleep","heart","steps","pedometer","strava","myfitnesspal","nike","adidas")) return "HEALTH";
        if (contains(s, "shop","amazon","ebay","store","mall","walmart","target","etsy","wish","ali","market","cart","purchase","order")) return "SHOPPING";
        if (contains(s, "learn","edu","school","course","quiz","study","math","science","duolingo","khan","udemy","coursera","dictionary","book","kindle","read","library")) return "EDUCATION";
        if (contains(s, "settings","system","phone","dialer","launcher","clock","calendar","contacts","files","manager","backup","clean","security","antivirus","vpn","tools","utility","permission","root","adb","terminal","battery","cpu","ram","storage")) return "SYSTEM";
        if (contains(s, "tool","util","note","todo","task","reminder","scanner","pdf","doc","excel","office","translate","map","navigation","weather","compass","calculator","converter","measure","barcode","qr")) return "TOOLS";
        return "OTHER";
    }

    private static boolean contains(String src, String... keys) {
        for (String k : keys) if (src.contains(k)) return true;
        return false;
    }

    // ─── App popup menu ───────────────────────────────────────────────────

    private void showAppMenu(final AppInfo app, final View anchor) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setDimAmount(0.45f);

        int popupW = dp(268);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int px = (loc[0] + anchor.getWidth() / 2) - popupW / 2;
        px = Math.max(dp(8), Math.min(px, screenW - popupW - dp(8)));
        int py = loc[1] + anchor.getHeight() + dp(6);
        if (py + dp(320) > screenH) py = Math.max(dp(8), loc[1] - dp(326));

        dialog.getWindow().setGravity(Gravity.TOP | Gravity.START);
        WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
        wlp.x = px; wlp.y = py; wlp.width = popupW; wlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        dialog.getWindow().setAttributes(wlp);

        int accent = sm.getAccentColor();
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(0xFF17161F);
        sheetBg.setCornerRadius(dp(16));
        sheetBg.setStroke(1, 0x33FFFFFF);
        sheet.setBackground(sheetBg);
        sheet.setPadding(0, dp(6), 0, dp(6));

        // Header
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(18), dp(6), dp(18), dp(14));

        ImageView iconV = new ImageView(this);
        iconV.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
        iconV.setImageBitmap(shapedIcon(app.icon, dp(46), sm.getIconShape()));

        LinearLayout nameG = new LinearLayout(this);
        nameG.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ngLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ngLp.setMargins(dp(14), 0, 0, 0);
        nameG.setLayoutParams(ngLp);

        TextView nameTv = new TextView(this);
        nameTv.setText(app.label);
        nameTv.setTextColor(0xFFFFFFFF);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        TextView catTv = new TextView(this);
        catTv.setText(app.category);
        catTv.setTextColor(0x44FFFFFF);
        catTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        catTv.setLetterSpacing(0.08f);

        nameG.addView(nameTv);
        nameG.addView(catTv);
        hdr.addView(iconV);
        hdr.addView(nameG);
        sheet.addView(hdr);
        sheet.addView(makeDivider());

        sheet.addView(makeMenuRow(dialog, "Open", accent, new Runnable() {
            public void run() { launchApp(app); }
        }));
        sheet.addView(makeDivider());
        sheet.addView(makeMenuRow(dialog, "App Info", 0xCCFFFFFF, new Runnable() {
            public void run() {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.packageName)));
            }
        }));
        if (!app.packageName.equals(getPackageName())) {
            sheet.addView(makeDivider());
            sheet.addView(makeMenuRow(dialog, "Uninstall", 0xFFFF5555, new Runnable() {
                public void run() {
                    pendingUninstallPkg = app.packageName;
                    Intent del = new Intent(Intent.ACTION_DELETE);
                    del.setData(Uri.parse("package:" + app.packageName));
                    del.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(del); } catch (Exception e) {
                        pendingUninstallPkg = null;
                        Toast.makeText(MainActivity.this, "Cannot uninstall", Toast.LENGTH_SHORT).show();
                    }
                }
            }));
        }
        sheet.addView(makeDivider());
        sheet.addView(makeMenuRow(dialog, "Launcher Settings", 0x88FFFFFF, new Runnable() {
            public void run() { openSettings(); }
        }));

        dialog.setContentView(sheet);
        dialog.show();
    }

    private void poofRemovedApp(final String packageName) {
        // Find the position of the removed app in the grid and animate it
        int targetPos = -1;
        for (int i = 0; i < filteredApps.size(); i++) {
            if (filteredApps.get(i).packageName.equals(packageName)) { targetPos = i; break; }
        }
        if (targetPos >= 0) {
            int firstVisible = appGrid.getFirstVisiblePosition();
            int childIndex = targetPos - firstVisible;
            final View cell = (childIndex >= 0 && childIndex < appGrid.getChildCount())
                ? appGrid.getChildAt(childIndex) : null;
            if (cell != null) {
                cell.animate()
                    .scaleX(1.25f).scaleY(1.25f).setDuration(80)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            cell.animate()
                                .scaleX(0f).scaleY(0f).alpha(0f).setDuration(200)
                                .withEndAction(new Runnable() {
                                    @Override public void run() {
                                        cell.setScaleX(1f); cell.setScaleY(1f); cell.setAlpha(1f);
                                        loadApps();
                                    }
                                }).start();
                        }
                    }).start();
                return;
            }
        }
        loadApps(); // fallback if cell not visible
    }

    private View makeMenuRow(final Dialog dialog, String label, int textColor, final Runnable action) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(textColor);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        row.setPadding(dp(22), dp(14), dp(22), dp(14));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); action.run(); }
        });
        return row;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(18), 0, dp(18), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void openSettings() { startActivity(new Intent(this, SettingsActivity.class)); }

    private void showUpdatePrompt(int serverVer, final UpdateManager um) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("Version " + serverVer + " is available (you have " + UpdateManager.CURRENT_VERSION + "). Download now?")
            .setPositiveButton("Update", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { um.downloadAndInstall(); }
            })
            .setNegativeButton("Later", null)
            .show();
    }

    // ─── Home long press menu ─────────────────────────────────────────────

    private void showHomeLongPressMenu() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setDimAmount(0.3f);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(0xEE0D0D14);
        sheetBg.setCornerRadius(dp(20));
        sheetBg.setStroke(1, 0x22FFFFFF);
        sheet.setBackground(sheetBg);
        sheet.setPadding(0, dp(8), 0, dp(8));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        dialog.getWindow().setGravity(Gravity.CENTER);
        WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
        wlp.width = Math.min(dp(280), screenW - dp(40));
        wlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        dialog.getWindow().setAttributes(wlp);

        sheet.addView(makeMenuRow(dialog, "Launcher Settings", sm.getAccentColor(), new Runnable() {
            public void run() { openSettings(); }
        }));
        sheet.addView(makeDivider());
        sheet.addView(makeMenuRow(dialog, "Add Widget", 0xCCFFFFFF, new Runnable() {
            public void run() { launchWidgetPicker(); }
        }));
        sheet.addView(makeDivider());
        sheet.addView(makeMenuRow(dialog, "Wallpaper", 0xCCFFFFFF, new Runnable() {
            public void run() {
                Intent wp = new Intent(Intent.ACTION_SET_WALLPAPER);
                startActivity(Intent.createChooser(wp, "Choose wallpaper"));
            }
        }));

        dialog.setContentView(sheet);
        dialog.show();
    }

    // ─── AppWidget area ───────────────────────────────────────────────────

    private View buildAppWidgetArea() {
        widgetContainer = new LinearLayout(this);
        widgetContainer.setOrientation(LinearLayout.VERTICAL);
        widgetContainer.setPadding(dp(16), dp(4), dp(16), 0);

        List<Integer> ids = getSavedWidgetIds();
        for (final int wid : ids) {
            android.appwidget.AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(wid);
            if (info == null) { removeWidgetId(wid); continue; }
            android.appwidget.AppWidgetHostView hv = appWidgetHost.createView(this, wid, info);
            hv.setAppWidget(wid, info);
            int minH = Math.max(info.minHeight, dp(100));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, minH);
            lp.setMargins(0, 0, 0, dp(8));
            hv.setLayoutParams(lp);
            final int widgetId = wid;
            hv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    confirmRemoveWidget(widgetId);
                    return true;
                }
            });
            widgetContainer.addView(hv);
        }
        return widgetContainer;
    }

    private void launchWidgetPicker() {
        pendingWidgetId = appWidgetHost.allocateAppWidgetId();
        Intent pick = new Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_PICK);
        pick.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
        startActivityForResult(pick, REQ_PICK_WIDGET);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_WIDGET) {
            if (resultCode == RESULT_OK && data != null) {
                int id = data.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
                android.appwidget.AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(id);
                if (info != null && info.configure != null) {
                    // needs config step — bind first
                    Intent cfg = new Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
                    cfg.setComponent(info.configure);
                    cfg.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    try { startActivityForResult(cfg, REQ_BIND_WIDGET); }
                    catch (Exception e) { addWidgetId(id); adapter = null; buildUI(); }
                } else {
                    addWidgetId(id);
                    adapter = null;
                    buildUI();
                }
            } else {
                if (pendingWidgetId >= 0) appWidgetHost.deleteAppWidgetId(pendingWidgetId);
                pendingWidgetId = -1;
            }
        } else if (requestCode == REQ_BIND_WIDGET) {
            if (pendingWidgetId >= 0) addWidgetId(pendingWidgetId);
            adapter = null;
            buildUI();
        }
    }

    private List<Integer> getSavedWidgetIds() {
        String s = sm.getWidgetIds();
        List<Integer> ids = new ArrayList<Integer>();
        if (s == null || s.isEmpty()) return ids;
        for (String part : s.split(",")) {
            try { ids.add(Integer.parseInt(part.trim())); } catch (Exception ignored) {}
        }
        return ids;
    }

    private void addWidgetId(int id) {
        List<Integer> ids = getSavedWidgetIds();
        ids.add(id);
        saveWidgetIds(ids);
        pendingWidgetId = -1;
    }

    private void removeWidgetId(int id) {
        List<Integer> ids = getSavedWidgetIds();
        java.util.Iterator<Integer> it = ids.iterator();
        while (it.hasNext()) { if (it.next() == id) { it.remove(); break; } }
        appWidgetHost.deleteAppWidgetId(id);
        saveWidgetIds(ids);
    }

    private void saveWidgetIds(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        sm.set(SettingsManager.KEY_WIDGET_IDS, sb.toString());
    }

    private void confirmRemoveWidget(final int id) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setMessage("Remove this widget?");
        b.setPositiveButton("Remove", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                removeWidgetId(id);
                adapter = null;
                buildUI();
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    // ─── Clock ────────────────────────────────────────────────────────────

    private void startClock() {
        clockRunnable = new Runnable() {
            @Override public void run() { updateClock(); clockHandler.postDelayed(this, 1000); }
        };
        clockHandler.post(clockRunnable);
    }
    private void stopClock() { if (clockRunnable != null) clockHandler.removeCallbacks(clockRunnable); }
    private void updateClock() {
        Date now = new Date();
        if (showingLock) {
            updateLockClock();
            return;
        }
        if (clockView == null) return;
        String fmt = sm.is24h() ? (sm.showSeconds() ? "HH:mm:ss" : "HH:mm") : (sm.showSeconds() ? "h:mm:ss a" : "h:mm a");
        clockView.setText(new SimpleDateFormat(fmt, Locale.getDefault()).format(now));
        if (dateView != null && sm.showDate())
            dateView.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
    }

    // ─── Icon rendering ───────────────────────────────────────────────────

    static Bitmap shapedIcon(Drawable drawable, int sizePx, int shape) {
        Bitmap src = drawableToBitmap(drawable, sizePx);
        if (shape == 2) return src;
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path path = new Path();
        if (shape == 0) path.addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW);
        else { float r = sizePx * 0.22f; path.addRoundRect(new RectF(0, 0, sizePx, sizePx), r, r, Path.Direction.CW); }
        canvas.drawPath(path, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()), new Rect(0, 0, sizePx, sizePx), paint);
        return out;
    }

    static Bitmap drawableToBitmap(Drawable d, int size) {
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null) return Bitmap.createScaledBitmap(b, size, size, true);
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    int dp(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    // ─── Data ─────────────────────────────────────────────────────────────

    static class AppInfo { String label, packageName, activityName, category; Drawable icon; }

    class AppAdapter extends BaseAdapter {
        @Override public int getCount()        { return filteredApps.size(); }
        @Override public Object getItem(int p) { return filteredApps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(4), dp(10), dp(4), dp(8));

                FrameLayout frame = new FrameLayout(MainActivity.this);
                int iconDp = sm.getIconSizeDp();
                frame.setLayoutParams(new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

                ImageView icon = new ImageView(MainActivity.this);
                icon.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                frame.addView(icon);

                TextView label = new TextView(MainActivity.this);
                label.setGravity(Gravity.CENTER);
                label.setTextColor(0xDDFFFFFF);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sm.getLabelSizeSp());
                label.setMaxLines(1);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setPadding(dp(2), dp(4), dp(2), 0);
                label.setLayoutParams(new LinearLayout.LayoutParams(dp(sm.getIconSizeDp() + 16), ViewGroup.LayoutParams.WRAP_CONTENT));
                label.setVisibility(sm.showLabels() ? View.VISIBLE : View.GONE);

                cell.addView(frame);
                cell.addView(label);
                h = new ViewHolder();
                h.icon = icon; h.label = label;
                cell.setTag(h);
                convertView = cell;
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            AppInfo app = filteredApps.get(pos);
            h.label.setText(app.label);
            h.icon.setImageBitmap(shapedIcon(app.icon, dp(sm.getIconSizeDp()), sm.getIconShape()));
            return convertView;
        }

        class ViewHolder { ImageView icon; TextView label; }
    }
}
