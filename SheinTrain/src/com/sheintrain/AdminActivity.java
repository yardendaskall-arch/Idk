package com.sheintrain;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

public class AdminActivity extends Activity implements TextToSpeech.OnInitListener {

    private NumberPicker pickerMinutes, pickerSeconds;
    private TextView     tvCountdown, tvStatus;
    private Button       btnStart, btnRefresh;
    private View         pickerContainer;
    private LinearLayout suggestionsList;

    private CountDownTimer countDownTimer;
    private TextToSpeech   tts;
    private Handler        uiHandler;
    private boolean        ttsReady = false;
    private boolean        running  = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin);

        uiHandler      = new Handler(Looper.getMainLooper());
        tvCountdown    = (TextView)      findViewById(R.id.tv_countdown);
        tvStatus       = (TextView)      findViewById(R.id.tv_status);
        btnStart       = (Button)        findViewById(R.id.btn_start);
        btnRefresh     = (Button)        findViewById(R.id.btn_refresh);
        pickerContainer = findViewById(R.id.picker_container);
        suggestionsList = (LinearLayout) findViewById(R.id.suggestions_list);
        pickerMinutes  = (NumberPicker)  findViewById(R.id.picker_minutes);
        pickerSeconds  = (NumberPicker)  findViewById(R.id.picker_seconds);

        pickerMinutes.setMinValue(0); pickerMinutes.setMaxValue(99);
        pickerMinutes.setValue(0);    pickerMinutes.setWrapSelectorWheel(false);
        pickerSeconds.setMinValue(0); pickerSeconds.setMaxValue(59);
        pickerSeconds.setValue(30);   pickerSeconds.setWrapSelectorWheel(true);

        tts = new TextToSpeech(this, this);

        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (running) stopCountdown(); else startCountdown();
            }
        });

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { loadSuggestions(); }
        });

        loadSuggestions();
    }

    private void startCountdown() {
        int mins = pickerMinutes.getValue();
        int secs = pickerSeconds.getValue();
        long totalMs = (mins * 60L + secs) * 1000L;
        if (totalMs <= 0) { tvStatus.setText("Pick a time first!"); return; }

        running = true;
        pickerContainer.setVisibility(View.INVISIBLE);
        btnStart.setText(getString(R.string.stop));
        tvStatus.setText("Boarding closes in...");
        announceStart(mins, secs);

        countDownTimer = new CountDownTimer(totalMs, 100) {
            public void onTick(long ms) {
                long s = ms / 1000, m = s / 60;
                tvCountdown.setText(String.format(Locale.US, "%02d:%02d", m, s % 60));
            }
            public void onFinish() {
                tvCountdown.setText("00:00");
                running = false;
                pickerContainer.setVisibility(View.VISIBLE);
                btnStart.setText(getString(R.string.start));
                tvStatus.setText("All aboard!");
                onDeparture();
            }
        }.start();
    }

    private void stopCountdown() {
        if (countDownTimer != null) { countDownTimer.cancel(); countDownTimer = null; }
        running = false;
        pickerContainer.setVisibility(View.VISIBLE);
        btnStart.setText(getString(R.string.start));
        tvCountdown.setText("00:00");
        tvStatus.setText(getString(R.string.pick_time));
    }

    private void announceStart(int mins, int secs) {
        if (!ttsReady) return;
        StringBuilder sb = new StringBuilder("Shane Train departing in ");
        if (mins > 0) { sb.append(mins).append(mins == 1 ? " minute" : " minutes"); if (secs > 0) sb.append(" and "); }
        if (secs > 0)   sb.append(secs).append(secs == 1 ? " second" : " seconds");
        tts.speak(sb.toString(), TextToSpeech.QUEUE_FLUSH, null, "shein_start");
    }

    private void onDeparture() {
        vibrate();
        if (ttsReady) tts.speak("Shane Train Departing", TextToSpeech.QUEUE_FLUSH, null, "shein_depart");
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = {0, 200, 100, 200, 100, 600};
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> cls = Class.forName("android.os.VibrationEffect");
                java.lang.reflect.Method m = cls.getMethod("createWaveform", long[].class, int.class);
                Object effect = m.invoke(null, pattern, -1);
                Vibrator.class.getMethod("vibrate", cls).invoke(v, effect);
            } catch (Exception e) { v.vibrate(pattern, -1); }
        } else {
            v.vibrate(pattern, -1);
        }
    }

    private void loadSuggestions() {
        btnRefresh.setEnabled(false);
        SupabaseClient.fetchSuggestions(new SupabaseClient.FetchCallback() {
            public void onResult(final List<String> items, final String error) {
                uiHandler.post(new Runnable() {
                    public void run() {
                        btnRefresh.setEnabled(true);
                        suggestionsList.removeAllViews();
                        if (error != null) {
                            addRow("Error: " + error, 0xFFFF4444, false);
                            return;
                        }
                        if (items.isEmpty()) {
                            addRow("No suggestions yet.", 0xFF888888, false);
                            return;
                        }
                        for (String item : items) addRow(item, 0xFFFFFFFF, true);
                    }
                });
            }
        });
    }

    private void addRow(String text, int color, boolean showBullet) {
        TextView tv = new TextView(this);
        tv.setText(showBullet ? "• " + text : text);
        tv.setTextColor(color);
        tv.setTextSize(14);
        tv.setPadding(0, 10, 0, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        suggestionsList.addView(tv);

        // divider
        View divider = new View(this);
        divider.setBackgroundColor(0xFF222222);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divider.setLayoutParams(dp);
        suggestionsList.addView(divider);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(Locale.US);
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            if (ttsReady) { tts.setSpeechRate(0.85f); tts.setPitch(1.1f); }
        }
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) countDownTimer.cancel();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
