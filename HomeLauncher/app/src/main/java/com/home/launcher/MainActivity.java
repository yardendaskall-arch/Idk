package com.home.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
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

    private LinearLayout rootLayout;
    private TextView clockView, dateView, appCountView;
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
        @Override
        public void onReceive(Context context, Intent intent) { updateClock(); }
    };

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
            if ((e.getY() - touchDownY) > dp(70) && touchDownY < dp(100)) expandNotifications();
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

    private void buildUI() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        applyBackground();
        rootLayout.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                return true;
            }
        });

        LinearLayout clockSec = buildClockSection();
        LinearLayout searchSec = buildSearchSection();
        GridView grid = buildGrid();

        if (!sm.searchBottom()) {
            rootLayout.addView(clockSec);
            if (sm.showSearch()) rootLayout.addView(searchSec);
            rootLayout.addView(grid);
        } else {
            rootLayout.addView(clockSec);
            rootLayout.addView(grid);
            if (sm.showSearch()) rootLayout.addView(searchSec);
        }

        setContentView(rootLayout);
        updateClock();
        loadApps();
    }

    private void applyBackground() {
        int[] bg = SettingsManager.BG_PRESETS[sm.getBgPreset()];
        GradientDrawable g = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{bg[0], bg[1]});
        rootLayout.setBackground(g);
    }

    private LinearLayout buildClockSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setGravity(Gravity.CENTER);
        sec.setPadding(0, dp(52), 0, dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sec.setLayoutParams(lp);
        sec.setVisibility(sm.showClock() ? View.VISIBLE : View.GONE);

        clockView = new TextView(this);
        clockView.setTextColor(0xFFFFFFFF);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sm.getClockSizeSp());
        clockView.setTypeface(null, android.graphics.Typeface.BOLD);
        clockView.setLetterSpacing(0.04f);

        dateView = new TextView(this);
        dateView.setTextColor(0xB3FFFFFF);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        dateView.setPadding(0, dp(4), 0, dp(16));
        dateView.setLetterSpacing(0.06f);
        dateView.setVisibility(sm.showDate() ? View.VISIBLE : View.GONE);

        sec.addView(clockView);
        sec.addView(dateView);
        return sec;
    }

    private LinearLayout buildSearchSection() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(20), dp(6), dp(20), dp(10));
        outer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        appCountView = new TextView(this);
        appCountView.setTextColor(0x80FFFFFF);
        appCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        appCountView.setPadding(dp(4), 0, 0, dp(6));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(23));
        pillBg.setColor(0x33FFFFFF);
        pill.setBackground(pillBg);
        pill.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD0D");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        icon.setPadding(0, 0, dp(8), 0);

        searchBar = new EditText(this);
        searchBar.setHint("Search apps...");
        searchBar.setTextColor(0xFFFFFFFF);
        searchBar.setHintTextColor(0x80FFFFFF);
        searchBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchBar.setBackground(null);
        searchBar.setSingleLine(true);
        searchBar.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBar.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { filterApps(s.toString()); }
        });

        pill.addView(icon);
        pill.addView(searchBar);
        outer.addView(appCountView);
        outer.addView(pill);
        return outer;
    }

    private GridView buildGrid() {
        appGrid = new GridView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        appGrid.setLayoutParams(lp);
        appGrid.setNumColumns(sm.getColumns());
        appGrid.setVerticalSpacing(dp(8));
        appGrid.setHorizontalSpacing(dp(4));
        appGrid.setPadding(dp(8), dp(4), dp(8), dp(16));
        appGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        appGrid.setScrollbarFadingEnabled(true);
        return appGrid;
    }

    private void startClock() {
        clockRunnable = new Runnable() {
            @Override public void run() { updateClock(); clockHandler.postDelayed(this, 1000); }
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
            : (sm.showSeconds() ? "hh:mm:ss a" : "hh:mm a");
        clockView.setText(new SimpleDateFormat(fmt, Locale.getDefault()).format(now));
        if (dateView != null && sm.showDate())
            dateView.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
    }

    private void loadApps() {
        allApps.clear();
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        final PackageManager pmRef = pm;
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                String la = a.loadLabel(pmRef).toString(), lb = b.loadLabel(pmRef).toString();
                return sm.getSortOrder() == 0 ? la.compareToIgnoreCase(lb) : lb.compareToIgnoreCase(la);
            }
        });
        for (ResolveInfo ri : list) {
            AppInfo info = new AppInfo();
            info.label = ri.loadLabel(pm).toString();
            info.icon = ri.loadIcon(pm);
            info.packageName = ri.activityInfo.packageName;
            info.activityName = ri.activityInfo.name;
            allApps.add(info);
        }
        if (appCountView != null) appCountView.setText(allApps.size() + " apps");
        filterApps(searchBar != null ? searchBar.getText().toString() : "");
    }

    private void filterApps(String query) {
        filteredApps.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            String q = query.toLowerCase(Locale.getDefault());
            for (AppInfo a : allApps)
                if (a.label.toLowerCase(Locale.getDefault()).contains(q)) filteredApps.add(a);
        }
        if (adapter == null) {
            adapter = new AppAdapter();
            appGrid.setAdapter(adapter);
            appGrid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override public void onItemClick(android.widget.AdapterView<?> p, final View v, int pos, long id) {
                    v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(90)
                        .withEndAction(new Runnable() {
                            @Override public void run() { v.animate().scaleX(1f).scaleY(1f).setDuration(110).start(); }
                        }).start();
                    AppInfo app = filteredApps.get(pos);
                    Intent launch = new Intent(Intent.ACTION_MAIN);
                    launch.addCategory(Intent.CATEGORY_LAUNCHER);
                    launch.setClassName(app.packageName, app.activityName);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(launch); }
                    catch (Exception e) { Toast.makeText(MainActivity.this, "Can't open " + app.label, Toast.LENGTH_SHORT).show(); }
                }
            });
            appGrid.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    return true;
                }
            });
        } else {
            adapter.notifyDataSetChanged();
        }
    }

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

    private int dp(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    static class AppInfo { String label, packageName, activityName; Drawable icon; }

    class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return filteredApps.size(); }
        @Override public Object getItem(int p) { return filteredApps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(4), dp(8), dp(4), dp(8));

                ImageView icon = new ImageView(MainActivity.this);
                int iconDp = sm.getIconSizeDp();
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);

                TextView label = new TextView(MainActivity.this);
                label.setGravity(Gravity.CENTER);
                label.setTextColor(0xFFFFFFFF);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sm.getLabelSizeSp());
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setPadding(0, dp(5), 0, 0);
                label.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(sm.getIconSizeDp() + 14), ViewGroup.LayoutParams.WRAP_CONTENT));
                label.setVisibility(sm.showLabels() ? View.VISIBLE : View.GONE);

                cell.addView(icon);
                cell.addView(label);
                h = new ViewHolder();
                h.icon = icon;
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
