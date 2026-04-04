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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private GridView appGrid;
    private EditText searchBar;
    private TextView clockView;
    private TextView dateView;
    private TextView appCountView;

    private List<AppInfo> allApps = new ArrayList<>();
    private List<AppInfo> filteredApps = new ArrayList<>();
    private AppAdapter adapter;

    private Handler clockHandler = new Handler();
    private Runnable clockRunnable;

    private BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Immersive fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        setContentView(R.layout.activity_main);

        clockView = (TextView) findViewById(R.id.clock);
        dateView = (TextView) findViewById(R.id.date);
        appCountView = (TextView) findViewById(R.id.app_count);
        searchBar = (EditText) findViewById(R.id.search_bar);
        appGrid = (GridView) findViewById(R.id.app_grid);

        updateClock();

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        startClock();
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
        registerReceiver(timeReceiver, filter);
        loadApps();

        // Clear search on resume
        searchBar.setText("");
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopClock();
        try { unregisterReceiver(timeReceiver); } catch (Exception ignored) {}
    }

    private void startClock() {
        clockRunnable = new Runnable() {
            @Override
            public void run() {
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
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
    }

    private void loadApps() {
        allApps.clear();
        PackageManager pm = getPackageManager();

        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);

        Collections.sort(resolveInfos, new java.util.Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                PackageManager pm2 = getPackageManager();
                return a.loadLabel(pm2).toString().compareToIgnoreCase(b.loadLabel(pm2).toString());
            }
        });

        for (ResolveInfo ri : resolveInfos) {
            AppInfo info = new AppInfo();
            info.label = ri.loadLabel(pm).toString();
            info.icon = ri.loadIcon(pm);
            info.packageName = ri.activityInfo.packageName;
            info.activityName = ri.activityInfo.name;
            allApps.add(info);
        }

        if (appCountView != null) {
            appCountView.setText(allApps.size() + " apps");
        }

        filterApps(searchBar.getText().toString());
    }

    private void filterApps(String query) {
        filteredApps.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            String lower = query.toLowerCase(Locale.getDefault());
            for (AppInfo app : allApps) {
                if (app.label.toLowerCase(Locale.getDefault()).contains(lower)) {
                    filteredApps.add(app);
                }
            }
        }

        if (adapter == null) {
            adapter = new AppAdapter();
            appGrid.setAdapter(adapter);
            appGrid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    AppInfo app = filteredApps.get(position);
                    view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                            }
                        }).start();

                    Intent launchIntent = new Intent(Intent.ACTION_MAIN);
                    launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                    launchIntent.setClassName(app.packageName, app.activityName);
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(launchIntent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Cannot open " + app.label, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    static Bitmap toCircleBitmap(Bitmap src, int size) {
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        Rect src2 = new Rect(0, 0, src.getWidth(), src.getHeight());
        Rect dst = new Rect(0, 0, size, size);
        canvas.drawBitmap(src, src2, dst, paint);
        return output;
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

    static class AppInfo {
        String label;
        Drawable icon;
        String packageName;
        String activityName;
    }

    class AppAdapter extends BaseAdapter {
        private static final int ICON_SIZE_DP = 56;

        @Override public int getCount() { return filteredApps.size(); }
        @Override public Object getItem(int pos) { return filteredApps.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.app_item, parent, false);
                holder = new ViewHolder();
                holder.icon = (ImageView) convertView.findViewById(R.id.app_icon);
                holder.label = (TextView) convertView.findViewById(R.id.app_label);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            AppInfo app = filteredApps.get(position);
            holder.label.setText(app.label);

            int sizePx = (int) (ICON_SIZE_DP * getResources().getDisplayMetrics().density);
            Bitmap bmp = drawableToBitmap(app.icon, sizePx);
            holder.icon.setImageBitmap(bmp);

            return convertView;
        }

        class ViewHolder {
            ImageView icon;
            TextView label;
        }
    }
}
