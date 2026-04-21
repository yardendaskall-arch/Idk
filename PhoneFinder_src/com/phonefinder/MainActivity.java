package com.phonefinder;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Method;

public class MainActivity extends Activity {

    private static final int PERM_REQUEST = 42;
    private EditText phoneInput;
    private Button callBtn;
    private CheckBox hideNumber;
    private TextView statusText;
    private int callCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int dp = Math.round(getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(32 * dp, 48 * dp, 32 * dp, 48 * dp);

        TextView title = new TextView(this);
        title.setText("Find My Phone");
        title.setTextSize(26f);
        title.setTextColor(Color.parseColor("#1565C0"));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = 8 * dp;
        root.addView(title, p);

        TextView subtitle = new TextView(this);
        subtitle.setText("Ring your lost device to locate it");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(Color.GRAY);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p2.bottomMargin = 40 * dp;
        root.addView(subtitle, p2);

        TextView label = new TextView(this);
        label.setText("Lost phone number:");
        label.setTextSize(14f);
        label.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p3.bottomMargin = 6 * dp;
        root.addView(label, p3);

        phoneInput = new EditText(this);
        phoneInput.setHint("+1 555 123 4567");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setTextSize(18f);
        phoneInput.setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp);
        LinearLayout.LayoutParams p4 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p4.bottomMargin = 16 * dp;
        root.addView(phoneInput, p4);

        hideNumber = new CheckBox(this);
        hideNumber.setText("Hide caller ID (*67) — appears as Unknown on lost phone");
        hideNumber.setChecked(true);
        hideNumber.setTextSize(13f);
        LinearLayout.LayoutParams p5 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p5.bottomMargin = 24 * dp;
        root.addView(hideNumber, p5);

        callBtn = new Button(this);
        callBtn.setText("Call Now");
        callBtn.setTextSize(18f);
        callBtn.setTextColor(Color.WHITE);
        callBtn.setBackgroundColor(Color.parseColor("#1976D2"));
        callBtn.setPadding(0, 14 * dp, 0, 14 * dp);
        callBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkAndCall();
            }
        });
        LinearLayout.LayoutParams p6 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p6.bottomMargin = 20 * dp;
        root.addView(callBtn, p6);

        statusText = new TextView(this);
        statusText.setText("Install on a friend's phone, enter your lost device's number, tap Call.");
        statusText.setTextSize(13f);
        statusText.setTextColor(Color.GRAY);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void checkAndCall() {
        String number = phoneInput.getText().toString().trim();
        if (number.isEmpty()) {
            phoneInput.setError("Enter your lost phone's number");
            return;
        }

        int result = getPackageManager().checkPermission(
                "android.permission.CALL_PHONE", getPackageName());

        if (result == PackageManager.PERMISSION_GRANTED) {
            makeCall();
        } else {
            askPermission();
        }
    }

    private void askPermission() {
        try {
            // requestPermissions() added in API 23 — call via reflection so we compile against API 16
            Method m = Activity.class.getMethod("requestPermissions", String[].class, int.class);
            m.invoke(this, new String[]{"android.permission.CALL_PHONE"}, PERM_REQUEST);
        } catch (Exception e) {
            statusText.setText("Grant 'Phone' permission in Settings > Apps > Phone Finder > Permissions");
        }
    }

    // Android 6+ calls this after the permission dialog — no @Override because API 23 isn't in our compile jar
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            makeCall();
        } else {
            statusText.setText("Permission denied — enable it in Settings > Apps > Phone Finder");
        }
    }

    private void makeCall() {
        String number = phoneInput.getText().toString().trim();
        if (number.isEmpty()) return;

        if (hideNumber.isChecked()) {
            number = "*67" + number;
        }

        try {
            startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number)));
            callCount++;
            String times = callCount == 1 ? "1 time" : callCount + " times";
            statusText.setText("Called " + times + " — tap again if not found yet");
            callBtn.setText("Call Again");
        } catch (Exception e) {
            statusText.setText("Failed: " + e.getMessage());
        }
    }
}
