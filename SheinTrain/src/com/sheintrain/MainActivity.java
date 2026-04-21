package com.sheintrain;

import android.app.Activity;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.TextView;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private NumberPicker pickerMinutes;
    private NumberPicker pickerSeconds;
    private TextView tvCountdown;
    private TextView tvStatus;
    private Button btnStart;
    private View pickerContainer;

    private CountDownTimer countDownTimer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvCountdown = (TextView) findViewById(R.id.tv_countdown);
        tvStatus    = (TextView) findViewById(R.id.tv_status);
        btnStart    = (Button)   findViewById(R.id.btn_start);
        pickerContainer = findViewById(R.id.picker_container);

        pickerMinutes = (NumberPicker) findViewById(R.id.picker_minutes);
        pickerSeconds = (NumberPicker) findViewById(R.id.picker_seconds);

        pickerMinutes.setMinValue(0);
        pickerMinutes.setMaxValue(99);
        pickerMinutes.setValue(0);
        pickerMinutes.setWrapSelectorWheel(false);

        pickerSeconds.setMinValue(0);
        pickerSeconds.setMaxValue(59);
        pickerSeconds.setValue(30);
        pickerSeconds.setWrapSelectorWheel(true);

        styleNumberPicker(pickerMinutes);
        styleNumberPicker(pickerSeconds);

        tts = new TextToSpeech(this, this);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) {
                    stopCountdown();
                } else {
                    startCountdown();
                }
            }
        });
    }

    private void styleNumberPicker(NumberPicker picker) {
        // Tint selection divider via reflection (best-effort on API 23)
        try {
            java.lang.reflect.Field f = NumberPicker.class.getDeclaredField("mSelectionDivider");
            f.setAccessible(true);
            android.graphics.drawable.ColorDrawable cd =
                new android.graphics.drawable.ColorDrawable(0xFFFF2E63);
            f.set(picker, cd);
        } catch (Exception ignored) {}
    }

    private void startCountdown() {
        int mins = pickerMinutes.getValue();
        int secs = pickerSeconds.getValue();
        long totalMs = (mins * 60L + secs) * 1000L;

        if (totalMs <= 0) {
            tvStatus.setText("Pick a time first!");
            return;
        }

        running = true;
        pickerContainer.setVisibility(View.INVISIBLE);
        btnStart.setText(getString(R.string.stop));
        tvStatus.setText("Boarding closes in...");

        countDownTimer = new CountDownTimer(totalMs, 100) {
            @Override
            public void onTick(long millisUntilFinished) {
                long totalSec = millisUntilFinished / 1000;
                long m = totalSec / 60;
                long s = totalSec % 60;
                tvCountdown.setText(String.format(Locale.US, "%02d:%02d", m, s));
            }

            @Override
            public void onFinish() {
                tvCountdown.setText("00:00");
                running = false;
                pickerContainer.setVisibility(View.VISIBLE);
                btnStart.setText(getString(R.string.start));
                tvStatus.setText("All aboard!");
                announceDepature();
            }
        }.start();
    }

    private void stopCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        running = false;
        pickerContainer.setVisibility(View.VISIBLE);
        btnStart.setText(getString(R.string.start));
        tvCountdown.setText("00:00");
        tvStatus.setText(getString(R.string.pick_time));
    }

    private void announceDepature() {
        if (!ttsReady) return;
        tts.speak("Shane Train Departing", TextToSpeech.QUEUE_FLUSH, null, "shein_depart");
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.US);
            ttsReady = (result != TextToSpeech.LANG_MISSING_DATA
                     && result != TextToSpeech.LANG_NOT_SUPPORTED);
            if (ttsReady) {
                tts.setSpeechRate(0.85f);
                tts.setPitch(1.1f);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) countDownTimer.cancel();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
