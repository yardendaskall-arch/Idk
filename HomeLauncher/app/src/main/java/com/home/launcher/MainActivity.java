package com.home.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
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
    private LinearLayout contentLayout;
    private TextView clockView, dateView;
    private EditText searchBar;
    private GridView appGrid;

    private List<AppInfo> allApps = new ArrayList<AppInfo>();
    private List<AppInfo> filteredApps = new ArrayList<AppInfo>();
    private AppAdapter adapter;
    private SettingsManager sm;

    private Handler clockHandler = new Handler();
    private Runnable clockRunnable;
    private float touchDownY;

    private BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) { updateClock(); }
    };

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
        sm = new SettingsManager(this);
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
        if (e.getAction() == MotionEvent.ACTION_UP) {
            if ((e.getY() - touchDownY) > dp(60) && touchDownY < dp(120)) expandNotifications();
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

    // ─── UI Construction ───────────────────────────────────────────────

    private void buildUI() {
        // Root frame (allows layering)
        rootFrame = new FrameLayout(this);
        applyBackground(rootFrame);

        // Long press background → settings
        rootFrame.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                openSettings();
                return true;
            }
        });

        // Content column
        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);

        // Build sections
        View clockSec  = buildClockSection();
        View searchSec = buildSearchSection();
        GridView grid  = buildGrid();

        boolean searchBottom = sm.searchBottom();
        boolean showSearch   = sm.showSearch();

        contentLayout.addView(clockSec);
        if (showSearch && !searchBottom) contentLayout.addView(searchSec);
        contentLayout.addView(grid);
        if (showSearch && searchBottom)  contentLayout.addView(searchSec);

        rootFrame.addView(contentLayout, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        updateClock();
        loadApps();
    }

    private void applyBackground(View v) {
        int[] bg = SettingsManager.BG_PRESETS[sm.getBgPreset()];
        GradientDrawable g = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{bg[0], bg[1]});
        v.setBackground(g);
    }

    // ─── Clock ─────────────────────────────────────────────────────────

    private View buildClockSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setGravity(Gravity.CENTER_HORIZONTAL);
        // Status bar space + padding
        sec.setPadding(dp(24), dp(60), dp(24), dp(16));
        sec.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        sec.setVisibility(sm.showClock() ? View.VISIBLE : View.GONE);

        clockView = new TextView(this);
        clockView.setTextColor(0xFFFFFFFF);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sm.getClockSizeSp());
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockView.setLetterSpacing(-0.03f);
        clockView.setShadowLayer(dp(8), 0, dp(2), 0x55000000);

        dateView = new TextView(this);
        dateView.setTextColor(0x99FFFFFF);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dateView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        dateView.setLetterSpacing(0.08f);
        dateView.setPadding(0, dp(2), 0, dp(4));
        dateView.setVisibility(sm.showDate() ? View.VISIBLE : View.GONE);

        sec.addView(clockView);
        sec.addView(dateView);
        sec.addView(makeDivider());
        return sec;
    }

    // ─── Search ────────────────────────────────────────────────────────

    private View buildSearchSection() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(16), dp(10), dp(16), dp(14));
        outer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(16), 0, dp(16), 0);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(28));
        pillBg.setColor(0x22FFFFFF);
        pillBg.setStroke(dp(1), 0x33FFFFFF);
        pill.setBackground(pillBg);
        pill.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD0D");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        icon.setPadding(0, 0, dp(10), 0);

        searchBar = new EditText(this);
        searchBar.setHint("Search apps");
        searchBar.setTextColor(0xFFFFFFFF);
        searchBar.setHintTextColor(0x66FFFFFF);
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

    // ─── Grid ──────────────────────────────────────────────────────────

    private GridView buildGrid() {
        appGrid = new GridView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        appGrid.setLayoutParams(lp);
        appGrid.setNumColumns(sm.getColumns());
        appGrid.setVerticalSpacing(dp(4));
        appGrid.setHorizontalSpacing(dp(4));
        appGrid.setPadding(dp(6), dp(6), dp(6), dp(80));
        appGrid.setClipToPadding(false);
        appGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        appGrid.setScrollbarFadingEnabled(true);
        appGrid.setBackground(null);
        return appGrid;
    }

    // ─── Clock ticking ─────────────────────────────────────────────────

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

    // ─── App loading ───────────────────────────────────────────────────

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
            AppInfo info = new AppInfo();
            info.label       = ri.loadLabel(pm).toString();
            info.icon        = ri.loadIcon(pm);
            info.packageName = ri.activityInfo.packageName;
            info.activityName= ri.activityInfo.name;
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
                                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
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

    private void showAppMenu(final AppInfo app) {
        final String pkg = app.packageName;
        final boolean isSelf = pkg.equals(getPackageName());
        final String[] options = isSelf
            ? new String[]{"Open", "App Info", "Launcher Settings"}
            : new String[]{"Open", "App Info", "Uninstall", "Launcher Settings"};

        new AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        Intent launch = new Intent(Intent.ACTION_MAIN);
                        launch.addCategory(Intent.CATEGORY_LAUNCHER);
                        launch.setClassName(app.packageName, app.activityName);
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(launch); } catch (Exception ignored) {}
                    } else if (which == 1) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + pkg)));
                    } else if (!isSelf && which == 2) {
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
                    } else {
                        openSettings();
                    }
                }
            })
            .show();
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

    private View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(0x18FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(32), dp(4), dp(32), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ─── Data model ────────────────────────────────────────────────────

    static class AppInfo { String label, packageName, activityName; Drawable icon; }

    // ─── Adapter ───────────────────────────────────────────────────────

    class AppAdapter extends BaseAdapter {
        @Override public int getCount()       { return filteredApps.size(); }
        @Override public Object getItem(int p){ return filteredApps.get(p); }
        @Override public long getItemId(int p){ return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(4), dp(12), dp(4), dp(12));

                // Icon with subtle drop shadow background
                FrameLayout iconFrame = new FrameLayout(MainActivity.this);
                int iconDp = sm.getIconSizeDp();
                LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp));
                iconFrame.setLayoutParams(frameLp);

                ImageView icon = new ImageView(MainActivity.this);
                icon.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iconFrame.addView(icon);

                TextView label = new TextView(MainActivity.this);
                label.setGravity(Gravity.CENTER);
                label.setTextColor(0xEEFFFFFF);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sm.getLabelSizeSp());
                label.setMaxLines(1);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setPadding(dp(2), dp(6), dp(2), 0);
                label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                label.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(sm.getIconSizeDp() + 16), ViewGroup.LayoutParams.WRAP_CONTENT));
                label.setVisibility(sm.showLabels() ? View.VISIBLE : View.GONE);

                cell.addView(iconFrame);
                cell.addView(label);

                h = new ViewHolder();
                h.icon  = icon;
                h.label = label;
                cell.setTag(h);
                convertView = cell;
            } else {
                h = (ViewHolder) convertView.getTag();
            }

            AppInfo app = filteredApps.get(position);
            h.label.setText(app.label);
            h.icon.setImageBitmap(shapedIcon(app.icon, dp(sm.getIconSizeDp()), sm.getIconShape()));
            return convertView;
        }

        class ViewHolder { ImageView icon; TextView label; }
    }
}
