package com.home.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private GridView appGrid;
    private List<AppInfo> appList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appGrid = (GridView) findViewById(R.id.app_grid);
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
    }

    private void loadApps() {
        appList = new ArrayList<>();
        PackageManager pm = getPackageManager();

        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
        Collections.sort(resolveInfos, new Comparator<ResolveInfo>() {
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
            appList.add(info);
        }

        AppAdapter adapter = new AppAdapter();
        appGrid.setAdapter(adapter);
        appGrid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AppInfo app = appList.get(position);
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
    }

    private static class AppInfo {
        String label;
        Drawable icon;
        String packageName;
        String activityName;
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() { return appList.size(); }

        @Override
        public Object getItem(int pos) { return appList.get(pos); }

        @Override
        public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(MainActivity.this).inflate(R.layout.app_item, parent, false);
            }
            AppInfo app = appList.get(position);
            ImageView icon = (ImageView) view.findViewById(R.id.app_icon);
            TextView label = (TextView) view.findViewById(R.id.app_label);
            icon.setImageDrawable(app.icon);
            label.setText(app.label);
            return view;
        }
    }
}
