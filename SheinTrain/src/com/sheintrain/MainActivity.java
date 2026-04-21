package com.sheintrain;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Vibrator;
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
        announceCountdownStart(mins, secs);

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

    private void announceCountdownStart(int mins, int secs) {
        if (!ttsReady) return;
        StringBuilder sb = new StringBuilder("Shane Train departing in ");
        if (mins > 0) {
            sb.append(mins).append(mins == 1 ? " minute" : " minutes");
            if (secs > 0) sb.append(" and ");
        }
        if (secs > 0) {
            sb.append(secs).append(secs == 1 ? " second" : " seconds");
        }
        tts.speak(sb.toString(), TextToSpeech.QUEUE_FLUSH, null, "shein_start");
    }

    private void announceDepature() {
        vibrate();
        if (!ttsReady) return;
        tts.speak("Shane Train Departing", TextToSpeech.QUEUE_FLUSH, null, "shein_depart");
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        // Pattern: off, short, gap, short, gap, long  (train chug then horn)
        long[] pattern = {0, 200, 100, 200, 100, 600};
        if (Build.VERSION.SDK_INT >= 26) {
            // Use VibrationEffect via reflection to avoid compile dependency on API 26 stub
            try {
                Class<?> cls = Class.forName("android.os.VibrationEffect");
                java.lang.reflect.Method create = cls.getMethod("createWaveform", long[].class, int.class);
                Object effect = create.invoke(null, pattern, -1);
                Vibrator.class.getMethod("vibrate", cls).invoke(v, effect);
            } catch (Exception e) {
                v.vibrate(pattern, -1);
            }
        } else {
            v.vibrate(pattern, -1);
        }
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
