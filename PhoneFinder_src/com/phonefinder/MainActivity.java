package com.phonefinder;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private EditText phoneInput;
    private Button callBtn;
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
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = 8 * dp;
        root.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("Ring your lost device to locate it");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(Color.GRAY);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.bottomMargin = 40 * dp;
        root.addView(subtitle, subtitleParams);

        TextView label = new TextView(this);
        label.setText("Phone number to call:");
        label.setTextSize(14f);
        label.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.bottomMargin = 6 * dp;
        root.addView(label, labelParams);

        phoneInput = new EditText(this);
        phoneInput.setHint("+1 555 123 4567");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setTextSize(18f);
        phoneInput.setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.bottomMargin = 24 * dp;
        root.addView(phoneInput, inputParams);

        callBtn = new Button(this);
        callBtn.setText("Call Now");
        callBtn.setTextSize(18f);
        callBtn.setTextColor(Color.WHITE);
        callBtn.setBackgroundColor(Color.parseColor("#1976D2"));
        callBtn.setPadding(0, 14 * dp, 0, 14 * dp);
        callBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dial();
            }
        });
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.bottomMargin = 20 * dp;
        root.addView(callBtn, btnParams);

        statusText = new TextView(this);
        statusText.setText("Enter the number of your lost device above");
        statusText.setTextSize(14f);
        statusText.setTextColor(Color.GRAY);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void dial() {
        String number = phoneInput.getText().toString().trim();
        if (number.isEmpty()) {
            phoneInput.setError("Please enter a phone number");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
        startActivity(intent);
        callCount++;
        String times = callCount == 1 ? "1 time" : callCount + " times";
        statusText.setText("Called " + times + " — tap again if not found yet");
        callBtn.setText("Call Again");
    }
}
