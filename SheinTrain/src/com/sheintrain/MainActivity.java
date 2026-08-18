package com.sheintrain;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String CODE_MOONPIE = "moonpie";
    private static final String CODE_ADMIN   = "harborquartz77";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final EditText etCode  = (EditText) findViewById(R.id.et_code);
        final TextView tvError = (TextView) findViewById(R.id.tv_error);
        final Button   btnEnter = (Button)  findViewById(R.id.btn_enter);

        btnEnter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String code = etCode.getText().toString().trim();
                tvError.setVisibility(View.GONE);

                if (CODE_MOONPIE.equals(code)) {
                    startActivity(new Intent(MainActivity.this, SuggestionActivity.class));
                } else if (CODE_ADMIN.equals(code)) {
                    startActivity(new Intent(MainActivity.this, AdminActivity.class));
                } else {
                    tvError.setText("Invalid code. Try again.");
                    tvError.setVisibility(View.VISIBLE);
                    etCode.setText("");
                }
            }
        });
    }
}
