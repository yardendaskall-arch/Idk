package com.home.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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

    private List<AppInfo> allApps    = new ArrayList<AppInfo>();
    private List<AppInfo> filteredApps = new ArrayList<AppInfo>();
    private AppAdapter adapter;
    private SettingsManager sm;

    private Handler clockHandler  = new Handler();
    private Runnable clockRunnable;
    private float touchDownY;

    private BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    // ─── Lifecycle ─────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        sm = new SettingsManager(this);
        buildUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sm     = new SettingsManager(this);
        adapter = null;
        buildUI();
        startClock();
        registerReceiver(timeReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopClock();
        try { unregisterReceiver(timeReceiver); } catch (Exception ignored) {}
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) touchDownY = e.getY();
        if (e.getAction() == MotionEvent.ACTION_UP)
            if ((e.getY() - touchDownY) > dp(60) && touchDownY < dp(120)) expandNotifications();
        return super.onTouchEvent(e);
    }

    private void expandNotifications() {
        try {
            Object sb  = getSystemService("statusbar");
            Class<?> cls = Class.forName("android.app.StatusBarManager");
            Method m   = cls.getMethod("expandNotificationsPanel");
            m.invoke(sb);
        } catch (Exception ignored) {}
    }

    // ─── UI ────────────────────────────────────────────────────────────

    private void buildUI() {
        // Wallpaper flag
        if (sm.useSystemWallpaper()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        }

        rootFrame = new FrameLayout(this);
        applyBackground();

        rootFrame.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { openSettings(); return true; }
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        boolean searchBottom = sm.searchBottom();
        boolean showSearch   = sm.showSearch();

        content.addView(buildClockSection());
        if (showSearch && !searchBottom) content.addView(buildSearchSection());
        content.addView(buildGrid());
        if (showSearch && searchBottom)  content.addView(buildSearchSection());
        if (sm.dockEnabled())            content.addView(buildDock());

        rootFrame.addView(content, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        updateClock();
        loadApps();
    }

    private void applyBackground() {
        if (sm.useSystemWallpaper()) {
            // Dark overlay on top of system wallpaper
            int alpha = (int) (sm.getWpDim() / 100f * 255);
            rootFrame.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
        } else {
            int[] bg = SettingsManager.BG_PRESETS[sm.getBgPreset()];
            GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{bg[0], bg[1]});
            rootFrame.setBackground(g);
        }
    }

    // ─── Clock ─────────────────────────────────────────────────────────

    private View buildClockSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setGravity(Gravity.CENTER_HORIZONTAL);
        sec.setPadding(dp(24), dp(62), dp(24), dp(12));
        sec.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sec.setVisibility(sm.showClock() ? View.VISIBLE : View.GONE);

        clockView = new TextView(this);
        clockView.setTextColor(0xFFFFFFFF);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sm.getClockSizeSp());
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockView.setLetterSpacing(-0.03f);
        clockView.setShadowLayer(dp(10), 0, dp(3), 0x66000000);

        dateView = new TextView(this);
        dateView.setTextColor(0x99FFFFFF);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dateView.setLetterSpacing(0.1f);
        dateView.setPadding(0, dp(3), 0, dp(4));
        dateView.setVisibility(sm.showDate() ? View.VISIBLE : View.GONE);

        sec.addView(clockView);
        sec.addView(dateView);

        // Thin separator
        View sep = new View(this);
        sep.setBackgroundColor(0x18FFFFFF);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        sepLp.setMargins(dp(32), dp(10), dp(32), 0);
        sep.setLayoutParams(sepLp);
        sec.addView(sep);
        return sec;
    }

    // ─── Search ────────────────────────────────────────────────────────

    private View buildSearchSection() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(16), dp(10), dp(16), dp(6));
        outer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(16), 0, dp(14), 0);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(28));
        pillBg.setColor(0x22FFFFFF);
        pillBg.setStroke(1, 0x33FFFFFF);
        pill.setBackground(pillBg);
        pill.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD0D");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        icon.setPadding(0, 0, dp(10), 0);

        searchBar = new EditText(this);
        searchBar.setHint("Search apps");
        searchBar.setTextColor(0xFFFFFFFF);
        searchBar.setHintTextColor(0x55FFFFFF);
        searchBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchBar.setBackground(null);
        searchBar.setSingleLine(true);
        searchBar.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBar.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { filterApps(s.toString()); }
        });

        pill.addView(icon);
        pill.addView(searchBar);
        outer.addView(pill);
        return outer;
    }

    // ─── App grid ──────────────────────────────────────────────────────

    private GridView buildGrid() {
        appGrid = new GridView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        appGrid.setLayoutParams(lp);
        appGrid.setNumColumns(sm.getColumns());
        appGrid.setVerticalSpacing(dp(2));
        appGrid.setHorizontalSpacing(dp(2));
        appGrid.setPadding(dp(4), dp(4), dp(4), dp(16));
        appGrid.setClipToPadding(false);
        appGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        appGrid.setScrollbarFadingEnabled(true);
        appGrid.setBackground(null);
        return appGrid;
    }

    // ─── Dock ──────────────────────────────────────────────────────────

    private View buildDock() {
        // Semi-transparent pill bar at the bottom
        LinearLayout dockWrap = new LinearLayout(this);
        dockWrap.setOrientation(LinearLayout.VERTICAL);
        dockWrap.setPadding(dp(12), dp(8), dp(12), dp(24));
        dockWrap.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Thin separator
        View sep = new View(this);
        sep.setBackgroundColor(0x18FFFFFF);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        sepLp.setMargins(dp(32), 0, dp(32), dp(10));
        sep.setLayoutParams(sepLp);
        dockWrap.addView(sep);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Try to find dock apps: Phone, Messages, Camera, Browser, Settings
        String[] wantedPkgs = {
            "com.android.dialer", "com.google.android.dialer",
            "com.android.mms", "com.google.android.apps.messaging",
            "com.android.camera", "com.google.android.GoogleCamera",
            "com.android.chrome", "com.google.android.apps.chrome",
            "com.android.settings"
        };

        List<AppInfo> dockApps = new ArrayList<AppInfo>();
        outer:
        for (String pkg : wantedPkgs) {
            for (AppInfo a : allApps) {
                if (a.packageName.equals(pkg)) {
                    dockApps.add(a);
                    if (dockApps.size() >= 5) break outer;
                    break;
                }
            }
        }
        // Fill remaining slots from allApps if < 4
        for (int i = 0; i < allApps.size() && dockApps.size() < 4; i++) {
            AppInfo a = allApps.get(i);
            boolean already = false;
            for (AppInfo d : dockApps) if (d.packageName.equals(a.packageName)) { already = true; break; }
            if (!already) dockApps.add(a);
        }

        int iconDp = sm.getIconSizeDp() - 8;
        for (final AppInfo app : dockApps) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(14), dp(6), dp(14), dp(6));

            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(shapedIcon(app.icon, dp(iconDp), sm.getIconShape()));

            cell.addView(iv);
            row.addView(cell);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent launch = new Intent(Intent.ACTION_MAIN);
                    launch.addCategory(Intent.CATEGORY_LAUNCHER);
                    launch.setClassName(app.packageName, app.activityName);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(launch); } catch (Exception ignored) {}
                }
            });
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showAppMenu(app); return true;
                }
            });
        }

        hsv.addView(row);
        dockWrap.addView(hsv);
        return dockWrap;
    }

    // ─── Clock ─────────────────────────────────────────────────────────

    private void startClock() {
        clockRunnable = new Runnable() {
            @Override public void run() {
                updateClock();
                clockHandler.postDelayed(this, 1000);
            }
        };
        clockHandler.post(clockRunnable);
    }
    private void stopClock() {
        if (clockRunnable != null) clockHandler.removeCallbacks(clockRunnable);
    }
    private void updateClock() {
        if (clockView == null) return;
        Date now = new Date();
        String fmt = sm.is24h()
            ? (sm.showSeconds() ? "HH:mm:ss" : "HH:mm")
            : (sm.showSeconds() ? "h:mm:ss a" : "h:mm a");
        clockView.setText(new SimpleDateFormat(fmt, Locale.getDefault()).format(now));
        if (dateView != null && sm.showDate())
            dateView.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
                .format(now).toUpperCase(Locale.getDefault()));
    }

    // ─── Apps ──────────────────────────────────────────────────────────

    private void loadApps() {
        allApps.clear();
        final PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                String la = a.loadLabel(pm).toString(), lb = b.loadLabel(pm).toString();
                return sm.getSortOrder() == 0 ? la.compareToIgnoreCase(lb) : lb.compareToIgnoreCase(la);
            }
        });
        for (ResolveInfo ri : list) {
            AppInfo info       = new AppInfo();
            info.label         = ri.loadLabel(pm).toString();
            info.icon          = ri.loadIcon(pm);
            info.packageName   = ri.activityInfo.packageName;
            info.activityName  = ri.activityInfo.name;
            allApps.add(info);
        }
        filterApps(searchBar != null ? searchBar.getText().toString() : "");
    }

    private void filterApps(String query) {
        filteredApps.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            String q = query.toLowerCase(Locale.getDefault());
            for (AppInfo a : allApps)
                if (a.label.toLowerCase(Locale.getDefault()).contains(q))
                    filteredApps.add(a);
        }
        if (adapter == null) {
            adapter = new AppAdapter();
            appGrid.setAdapter(adapter);

            appGrid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override public void onItemClick(android.widget.AdapterView<?> p, final View v,
                                                  int pos, long id) {
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                            }
                        }).start();
                    AppInfo app = filteredApps.get(pos);
                    Intent launch = new Intent(Intent.ACTION_MAIN);
                    launch.addCategory(Intent.CATEGORY_LAUNCHER);
                    launch.setClassName(app.packageName, app.activityName);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(launch); }
                    catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Can't open " + app.label,
                            Toast.LENGTH_SHORT).show();
                    }
                }
            });

            appGrid.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(android.widget.AdapterView<?> p, View v,
                                                        int pos, long id) {
                    showAppMenu(filteredApps.get(pos));
                    return true;
                }
            });
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    // ─── Custom App Popup ──────────────────────────────────────────────

    private void showAppMenu(final AppInfo app) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setGravity(Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.getWindow().setDimAmount(0.6f);

        final boolean isSelf = app.packageName.equals(getPackageName());
        int accent = sm.getAccentColor();

        // Root sheet
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(0xFF1C1B2E);
        sheetBg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        sheet.setBackground(sheetBg);
        sheet.setPadding(0, dp(12), 0, dp(28));

        // Drag handle
        View handle = new View(this);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x44FFFFFF);
        handleBg.setCornerRadius(dp(3));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.setMargins(0, 0, 0, dp(16));
        handle.setLayoutParams(handleLp);
        sheet.addView(handle);

        // App header
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(20), dp(4), dp(20), dp(16));

        ImageView iconView = new ImageView(this);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        iconView.setImageBitmap(shapedIcon(app.icon, dp(52), sm.getIconShape()));

        LinearLayout nameGroup = new LinearLayout(this);
        nameGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ngLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ngLp.setMargins(dp(14), 0, 0, 0);
        nameGroup.setLayoutParams(ngLp);

        TextView nameTv = new TextView(this);
        nameTv.setText(app.label);
        nameTv.setTextColor(0xFFFFFFFF);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        TextView pkgTv = new TextView(this);
        pkgTv.setText(app.packageName);
        pkgTv.setTextColor(0x55FFFFFF);
        pkgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pkgTv.setMaxLines(1);
        pkgTv.setEllipsize(TextUtils.TruncateAt.END);

        nameGroup.addView(nameTv);
        nameGroup.addView(pkgTv);
        hdr.addView(iconView);
        hdr.addView(nameGroup);
        sheet.addView(hdr);

        // Separator
        sheet.addView(makeDivider(sheet));

        // Action: Open
        sheet.addView(makeMenuRow(dialog, "\u25B6  Open", accent, new Runnable() {
            public void run() {
                Intent launch = new Intent(Intent.ACTION_MAIN);
                launch.addCategory(Intent.CATEGORY_LAUNCHER);
                launch.setClassName(app.packageName, app.activityName);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(launch); } catch (Exception ignored) {}
            }
        }));
        sheet.addView(makeDivider(sheet));

        // Action: App Info
        sheet.addView(makeMenuRow(dialog, "\u2139\uFE0F  App Info", 0xFFFFFFFF, new Runnable() {
            public void run() {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + app.packageName)));
            }
        }));

        // Action: Uninstall (not for self)
        if (!isSelf) {
            sheet.addView(makeDivider(sheet));
            sheet.addView(makeMenuRow(dialog, "\uD83D\uDDD1\uFE0F  Uninstall", 0xFFFF5555, new Runnable() {
                public void run() {
                    try {
                        Intent del = new Intent(Intent.ACTION_DELETE);
                        del.setData(Uri.parse("package:" + app.packageName));
                        del.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(del);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Cannot uninstall", Toast.LENGTH_SHORT).show();
                    }
                }
            }));
        }

        sheet.addView(makeDivider(sheet));

        // Action: Launcher Settings
        sheet.addView(makeMenuRow(dialog, "\u2699\uFE0F  Launcher Settings", 0xFFFFFFFF, new Runnable() {
            public void run() { openSettings(); }
        }));

        dialog.setContentView(sheet);
        dialog.show();
    }

    private View makeMenuRow(final Dialog dialog, String label, int textColor, final Runnable action) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(textColor);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        row.setPadding(dp(24), dp(16), dp(24), dp(16));
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                action.run();
            }
        });
        return row;
    }

    private View makeDivider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(0x18FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(20), 0, dp(20), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ─── Icon rendering ────────────────────────────────────────────────

    static Bitmap shapedIcon(Drawable drawable, int sizePx, int shape) {
        Bitmap src = drawableToBitmap(drawable, sizePx);
        if (shape == 2) return src;
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path path = new Path();
        if (shape == 0) {
            path.addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW);
        } else {
            float r = sizePx * 0.22f;
            path.addRoundRect(new RectF(0, 0, sizePx, sizePx), r, r, Path.Direction.CW);
        }
        canvas.drawPath(path, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()),
            new Rect(0, 0, sizePx, sizePx), paint);
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

    int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ─── Data ──────────────────────────────────────────────────────────

    static class AppInfo { String label, packageName, activityName; Drawable icon; }

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
                cell.setPadding(dp(4), dp(10), dp(4), dp(10));

                FrameLayout frame = new FrameLayout(MainActivity.this);
                int iconDp = sm.getIconSizeDp();
                frame.setLayoutParams(new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

                ImageView icon = new ImageView(MainActivity.this);
                icon.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                frame.addView(icon);

                TextView label = new TextView(MainActivity.this);
                label.setGravity(Gravity.CENTER);
                label.setTextColor(0xEEFFFFFF);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sm.getLabelSizeSp());
                label.setMaxLines(1);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setPadding(dp(2), dp(5), dp(2), 0);
                label.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(sm.getIconSizeDp() + 16), ViewGroup.LayoutParams.WRAP_CONTENT));
                label.setVisibility(sm.showLabels() ? View.VISIBLE : View.GONE);

                cell.addView(frame);
                cell.addView(label);
                h = new ViewHolder();
                h.icon  = icon;
                h.label = label;
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
