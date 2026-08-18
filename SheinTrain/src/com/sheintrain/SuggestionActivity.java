package com.sheintrain;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class SuggestionActivity extends Activity {

    private EditText etItem;
    private Button   btnSubmit;
    private TextView tvStatus;
    private Handler  uiHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_suggestion);

        etItem    = (EditText) findViewById(R.id.et_item);
        btnSubmit = (Button)   findViewById(R.id.btn_submit);
        tvStatus  = (TextView) findViewById(R.id.tv_status);
        uiHandler = new Handler(Looper.getMainLooper());

        btnSubmit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String item = etItem.getText().toString().trim();
                if (item.isEmpty()) {
                    showStatus("Please enter an item name.", 0xFFFF4444);
                    return;
                }
                btnSubmit.setEnabled(false);
                showStatus("Submitting...", 0xFF888888);

                SupabaseClient.insertSuggestion(item, new SupabaseClient.Callback() {
                    public void onResult(final boolean ok, final String error) {
                        uiHandler.post(new Runnable() {
                            public void run() {
                                btnSubmit.setEnabled(true);
                                if (ok) {
                                    etItem.setText("");
                                    showStatus("Submitted! Add another?", 0xFF44CC44);
                                } else {
                                    showStatus("Failed: " + error, 0xFFFF4444);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    private void showStatus(String msg, int color) {
        tvStatus.setText(msg);
        tvStatus.setTextColor(color);
        tvStatus.setVisibility(View.VISIBLE);
    }
}
