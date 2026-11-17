package com.unoq.remote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    // ---- palette ----
    static final int BG = 0xFF101418;
    static final int CARD = 0xFF1B2128;
    static final int CARD_2 = 0xFF242C35;
    static final int TEXT = 0xFFE8EDF2;
    static final int MUTED = 0xFF8A96A3;
    static final int ACCENT = 0xFF00979D;   // Arduino teal
    static final int OK = 0xFF2ECC71;
    static final int BAD = 0xFFE74C3C;

    private static final String[] DIGITAL = {"D0", "D1", "D2", "D3", "D4", "D5", "D6",
            "D7", "D8", "D9", "D10", "D11", "D12", "D13"};
    private static final String[] ANALOG = {"A0", "A1", "A2", "A3", "A4", "A5"};
    private static final String[] PWM = {"D3", "D5", "D6", "D9", "D10", "D11"};
    private static final String[] MODES = {"input", "output", "input_pullup"};
    private static final String[] MODE_LABELS = {"IN", "OUT", "PULL-UP"};
    private static final int POLL_MS = 600;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService cmd = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pollBusy = new AtomicBoolean(false);
    private SharedPreferences prefs;

    private String host;
    private int port;
    private boolean connected;
    private boolean syncing;   // suppress listeners while applying board state
    private boolean live = true;
    private volatile boolean scanning;

    // views
    private EditText hostField, portField;
    private TextView statusText;
    private View statusDot;
    private Button connectBtn, scanBtn;
    private LinearLayout controls;
    private final Map<String, Button> modeButtons = new HashMap<>();
    private final Map<String, Switch> outSwitches = new HashMap<>();
    private final Map<String, TextView> inValues = new HashMap<>();
    private final Map<String, String> pinModes = new HashMap<>();
    private final Map<String, SeekBar> pwmBars = new HashMap<>();
    private final Map<String, TextView> pwmValues = new HashMap<>();
    private final Map<String, Long> pwmLastSent = new HashMap<>();
    private final Map<String, ProgressBar> analogBars = new HashMap<>();
    private final Map<String, TextView> analogValues = new HashMap<>();
    private final int[][] ledState = new int[5][3];
    private final Button[][] ledButtons = new Button[5][3];

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("unoq", MODE_PRIVATE);
        for (String p : DIGITAL) pinModes.put(p, "input");
        setContentView(buildUi());
        setControlsEnabled(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(poller);
        ui.postDelayed(poller, POLL_MS);
        agent.onTabShown(currentTab);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poller);
        agent.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanning = false;
        cmd.shutdownNow();
        agent.shutdown();
    }

    // ------------------------------------------------------------------ UI building

    private AgentPages agent;
    private final View[] pages = new View[3];
    private final Button[] tabs = new Button[3];
    private int currentTab;

    /** Host to use for the agent: the connected board, or whatever is typed in. */
    String boardHost() {
        if (host != null) return host;
        String h = hostField.getText().toString().trim();
        return h.isEmpty() ? null : h;
    }

    private View buildUi() {
        LinearLayout outer = vertical();
        outer.setBackgroundColor(BG);
        FrameLayout content = new FrameLayout(this);
        outer.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        agent = new AgentPages(this);
        pages[0] = buildPinsPage();
        pages[1] = agent.buildSystemPage();
        pages[2] = agent.buildTerminalPage();
        for (View v : pages) content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = horizontal();
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        String[] names = {"Pins", "System", "Terminal"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = button(names[i], CARD);
            tabs[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            lp.leftMargin = lp.rightMargin = dp(4);
            bar.addView(b, lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(idx); }
            });
        }
        outer.addView(bar);
        showTab(0);
        return outer;
    }

    private void showTab(int i) {
        hideKeyboard();
        currentTab = i;
        for (int k = 0; k < 3; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabs[k].setBackground(round(k == i ? ACCENT : CARD, dp(8)));
            tabs[k].setTextColor(k == i ? Color.WHITE : MUTED);
        }
        agent.onTabShown(i);
    }

    @Override
    public void onBackPressed() {
        if (currentTab != 0) showTab(0); else super.onBackPressed();
    }

    private View buildPinsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = vertical();
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root);

        TextView title = text("UNO Q Remote", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = text("Control your Arduino UNO Q over Wi-Fi", 14, MUTED);
        sub.setPadding(0, dp(2), 0, dp(12));
        root.addView(sub);

        root.addView(buildConnectionCard());

        controls = vertical();
        root.addView(controls);
        controls.addView(buildLedCard());
        controls.addView(buildDigitalCard());
        controls.addView(buildPwmCard());
        controls.addView(buildAnalogCard());
        return scroll;
    }

    private View buildConnectionCard() {
        LinearLayout card = card("Board");

        LinearLayout row = horizontal();
        hostField = input(prefs.getString("host", ""), "Board IP, e.g. 192.168.1.42", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        portField = input(String.valueOf(prefs.getInt("port", 7000)), "Port", InputType.TYPE_CLASS_NUMBER);
        row.addView(hostField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pl.leftMargin = dp(8);
        row.addView(portField, pl);
        card.addView(row);

        LinearLayout buttons = horizontal();
        buttons.setPadding(0, dp(10), 0, 0);
        connectBtn = button("Connect", ACCENT);
        scanBtn = button("Find board", CARD_2);
        buttons.addView(connectBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        sl.leftMargin = dp(8);
        buttons.addView(scanBtn, sl);
        card.addView(buttons);

        LinearLayout status = horizontal();
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(0, dp(12), 0, 0);
        statusDot = new View(this);
        statusDot.setBackground(round(MUTED, dp(5)));
        status.addView(statusDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        statusText = text("Not connected", 14, MUTED);
        statusText.setPadding(dp(8), 0, 0, 0);
        status.addView(statusText);
        card.addView(status);

        LinearLayout liveRow = horizontal();
        liveRow.setGravity(Gravity.CENTER_VERTICAL);
        liveRow.setPadding(0, dp(8), 0, 0);
        TextView liveLabel = text("Live update inputs", 14, TEXT);
        liveRow.addView(liveLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch liveSwitch = styledSwitch();
        liveSwitch.setChecked(true);
        liveSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { live = on; }
        });
        liveRow.addView(liveSwitch);
        card.addView(liveRow);

        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideKeyboard(); connect(); }
        });
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideKeyboard(); scan(); }
        });
        return card;
    }

    private View buildLedCard() {
        LinearLayout card = card("Built-in RGB LEDs");
        for (int led = 3; led <= 4; led++) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView name = text("LED" + led, 15, TEXT);
            row.addView(name, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            String[] names = {"R", "G", "B"};
            for (int c = 0; c < 3; c++) {
                final int fLed = led, fC = c;
                Button b = button(names[c], CARD_2);
                ledButtons[led][c] = b;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                lp.leftMargin = dp(6);
                row.addView(b, lp);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ledState[fLed][fC] ^= 1;
                        paintLed(fLed);
                        sendLed(fLed);
                    }
                });
            }
            Button off = button("Off", CARD_2);
            final int fLed = led;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(44));
            lp.leftMargin = dp(6);
            row.addView(off, lp);
            off.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ledState[fLed][0] = ledState[fLed][1] = ledState[fLed][2] = 0;
                    paintLed(fLed);
                    sendLed(fLed);
                }
            });
            card.addView(row);
        }
        return card;
    }

    private View buildDigitalCard() {
        LinearLayout card = card("Digital pins");
        TextView hint = text("Tap the mode button to switch between output, input and input with pull-up.", 13, MUTED);
        hint.setPadding(0, 0, 0, dp(6));
        card.addView(hint);
        for (final String pin : DIGITAL) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));

            TextView name = text(pin, 15, TEXT);
            name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            row.addView(name, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));

            Button mode = button("IN", CARD_2);
            mode.setTextSize(13);
            modeButtons.put(pin, mode);
            row.addView(mode, new LinearLayout.LayoutParams(dp(96), dp(40)));

            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            TextView value = text("—", 14, MUTED);
            value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            value.setGravity(Gravity.CENTER);
            inValues.put(pin, value);
            row.addView(value, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

            Switch sw = styledSwitch();
            outSwitches.put(pin, sw);
            row.addView(sw);

            mode.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String cur = pinModes.get(pin);
                    int i = 0;
                    for (int k = 0; k < MODES.length; k++) if (MODES[k].equals(cur)) i = k;
                    final String next = MODES[(i + 1) % MODES.length];
                    applyMode(pin, next);
                    run("Set " + pin + " to " + next, new Task() {
                        @Override public void exec() throws Exception {
                            Api.get(host, port, "/api/mode", 2500, "pin", pin, "mode", next);
                        }
                    });
                }
            });
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, final boolean on) {
                    if (syncing) return;
                    inValues.get(pin).setText(on ? "HIGH" : "LOW");
                    inValues.get(pin).setTextColor(on ? OK : MUTED);
                    run(pin + " " + (on ? "HIGH" : "LOW"), new Task() {
                        @Override public void exec() throws Exception {
                            Api.get(host, port, "/api/digital/write", 2500, "pin", pin, "value", on ? "1" : "0");
                        }
                    });
                }
            });
            applyMode(pin, "input");
            card.addView(row);
        }
        return card;
    }

    private View buildPwmCard() {
        LinearLayout card = card("PWM outputs (0–255)");
        for (final String pin : PWM) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView name = text(pin, 15, TEXT);
            name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            row.addView(name, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            SeekBar bar = new SeekBar(this);
            bar.setMax(255);
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
            pwmBars.put(pin, bar);
            row.addView(bar, new LinearLayout.LayoutParams(0, dp(40), 1f));
            TextView val = text("0", 14, TEXT);
            val.setTypeface(Typeface.MONOSPACE);
            val.setGravity(Gravity.END);
            pwmValues.put(pin, val);
            row.addView(val, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    pwmValues.get(pin).setText(String.valueOf(p));
                    if (!fromUser || syncing) return;
                    long now = System.currentTimeMillis();
                    Long last = pwmLastSent.get(pin);
                    if (last == null || now - last > 120) {
                        pwmLastSent.put(pin, now);
                        sendPwm(pin, p);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) { sendPwm(pin, s.getProgress()); }
            });
            card.addView(row);
        }
        return card;
    }

    private View buildAnalogCard() {
        LinearLayout card = card("Analog inputs");
        for (String pin : ANALOG) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView name = text(pin, 15, TEXT);
            name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            row.addView(name, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1023);
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD_2));
            analogBars.put(pin, bar);
            row.addView(bar, new LinearLayout.LayoutParams(0, dp(12), 1f));
            TextView val = text("—", 14, TEXT);
            val.setTypeface(Typeface.MONOSPACE);
            val.setGravity(Gravity.END);
            analogValues.put(pin, val);
            row.addView(val, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(row);
        }
        return card;
    }

    // ------------------------------------------------------------------ board actions

    private interface Task { void exec() throws Exception; }

    /** Run a command on the serial command thread and report failures in the status line. */
    private void run(final String what, final Task task) {
        if (!connected) { toastStatus("Connect to the board first", false); return; }
        cmd.execute(new Runnable() {
            @Override public void run() {
                try {
                    task.exec();
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { toastStatus(what + " failed: " + e.getMessage(), false); }
                    });
                }
            }
        });
    }

    private void connect() {
        final String h = hostField.getText().toString().trim();
        int p;
        try { p = Integer.parseInt(portField.getText().toString().trim()); } catch (NumberFormatException e) { p = 7000; }
        if (h.isEmpty()) { toastStatus("Enter the board's IP address (or tap Find board)", false); return; }
        final int fp = p;
        setStatus("Connecting to " + h + ":" + fp + "…", MUTED);
        connectBtn.setEnabled(false);
        cmd.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject info = Api.get(h, fp, "/api/info", 3000);
                    if (!"unoq-remote".equals(info.optString("app"))) throw new Exception("not a UNO Q Remote app");
                    final JSONObject st = Api.get(h, fp, "/api/status", 3000).getJSONObject("state");
                    ui.post(new Runnable() {
                        @Override public void run() {
                            host = h; port = fp; connected = true;
                            prefs.edit().putString("host", h).putInt("port", fp).apply();
                            applyState(st);
                            setControlsEnabled(true);
                            connectBtn.setEnabled(true);
                            connectBtn.setText("Reconnect");
                            setStatus("Connected to " + h + ":" + fp, OK);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            connected = false;
                            setControlsEnabled(false);
                            connectBtn.setEnabled(true);
                            setStatus("Can't reach board: " + e.getMessage(), BAD);
                        }
                    });
                }
            }
        });
    }

    private void applyState(JSONObject st) {
        syncing = true;
        try {
            JSONObject modes = st.optJSONObject("modes");
            JSONObject out = st.optJSONObject("out");
            for (String pin : DIGITAL) {
                if (modes != null) applyMode(pin, modes.optString(pin, "input"));
                if (out != null) outSwitches.get(pin).setChecked(out.optInt(pin) == 1);
                if ("output".equals(pinModes.get(pin))) {
                    boolean on = outSwitches.get(pin).isChecked();
                    inValues.get(pin).setText(on ? "HIGH" : "LOW");
                    inValues.get(pin).setTextColor(on ? OK : MUTED);
                }
            }
            JSONObject pwm = st.optJSONObject("pwm");
            if (pwm != null) for (String pin : PWM) pwmBars.get(pin).setProgress(pwm.optInt(pin));
            for (int led = 3; led <= 4; led++) {
                JSONArray a = st.optJSONArray("led" + led);
                if (a != null) for (int c = 0; c < 3; c++) ledState[led][c] = a.optInt(c);
                paintLed(led);
            }
        } finally {
            syncing = false;
        }
    }

    private void applyMode(String pin, String mode) {
        pinModes.put(pin, mode);
        int i = 0;
        for (int k = 0; k < MODES.length; k++) if (MODES[k].equals(mode)) i = k;
        Button b = modeButtons.get(pin);
        b.setText(MODE_LABELS[i]);
        b.setBackground(round("output".equals(mode) ? ACCENT : CARD_2, dp(8)));
        boolean isOut = "output".equals(mode);
        Switch sw = outSwitches.get(pin);
        sw.setVisibility(isOut ? View.VISIBLE : View.INVISIBLE);
        TextView v = inValues.get(pin);
        if (isOut) {
            v.setText(sw.isChecked() ? "HIGH" : "LOW");
            v.setTextColor(sw.isChecked() ? OK : MUTED);
        } else {
            v.setText("—");
            v.setTextColor(MUTED);
        }
    }

    private void sendLed(final int led) {
        final int r = ledState[led][0], g = ledState[led][1], b = ledState[led][2];
        run("LED" + led, new Task() {
            @Override public void exec() throws Exception {
                Api.get(host, port, "/api/led", 2500, "led", String.valueOf(led),
                        "r", String.valueOf(r), "g", String.valueOf(g), "b", String.valueOf(b));
            }
        });
    }

    private void sendPwm(final String pin, final int value) {
        if (!"output".equals(pinModes.get(pin))) applyMode(pin, "output");
        run("PWM " + pin, new Task() {
            @Override public void exec() throws Exception {
                Api.get(host, port, "/api/pwm", 2500, "pin", pin, "value", String.valueOf(value));
            }
        });
    }

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, POLL_MS);
            if (!connected || !live || currentTab != 0 || !pollBusy.compareAndSet(false, true)) return;
            final List<String> inputs = new ArrayList<>();
            for (String p : DIGITAL) if (!"output".equals(pinModes.get(p))) inputs.add(p);
            cmd.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final JSONObject a = Api.get(host, port, "/api/analog/all", 2500).getJSONObject("values");
                        final JSONObject d = inputs.isEmpty() ? null
                                : Api.get(host, port, "/api/digital/all", 2500).getJSONObject("values");
                        ui.post(new Runnable() {
                            @Override public void run() { showReadings(a, d); }
                        });
                    } catch (final Exception e) {
                        ui.post(new Runnable() {
                            @Override public void run() { setStatus("Lost contact: " + e.getMessage(), BAD); }
                        });
                    } finally {
                        pollBusy.set(false);
                    }
                }
            });
        }
    };

    private void showReadings(JSONObject analog, JSONObject digital) {
        if (connected) setStatus("Connected to " + host + ":" + port, OK);
        for (String pin : ANALOG) {
            int v = analog.optInt(pin, -1);
            if (v < 0) continue;
            ProgressBar bar = analogBars.get(pin);
            if (v > bar.getMax()) bar.setMax(v > 4095 ? 65535 : 4095); // adapt to ADC resolution
            bar.setProgress(v);
            analogValues.get(pin).setText(String.valueOf(v));
        }
        if (digital == null) return;
        for (String pin : DIGITAL) {
            if ("output".equals(pinModes.get(pin))) continue;
            int v = digital.optInt(pin, -1);
            TextView t = inValues.get(pin);
            t.setText(v == 1 ? "HIGH" : v == 0 ? "LOW" : "—");
            t.setTextColor(v == 1 ? OK : MUTED);
        }
    }

    /** Scan the phone's /24 Wi-Fi subnet for a board answering /api/info on the given port. */
    private void scan() {
        if (scanning) { scanning = false; return; }
        final List<String> prefixes = localPrefixes();
        if (prefixes.isEmpty()) { setStatus("Connect your phone to Wi-Fi or turn on its hotspot first", BAD); return; }
        int p;
        try { p = Integer.parseInt(portField.getText().toString().trim()); } catch (NumberFormatException e) { p = 7000; }
        final int fp = p;
        scanning = true;
        scanBtn.setText("Stop");
        setStatus("Searching " + prefixes.size() + " network(s)…", MUTED);
        final int total = prefixes.size() * 254;
        new Thread(new Runnable() {
            @Override public void run() {
                final ExecutorService pool = Executors.newFixedThreadPool(48);
                final AtomicInteger done = new AtomicInteger();
                final String[] found = new String[1];
                for (String prefix : prefixes) for (int i = 1; i < 255; i++) {
                    final String ip = prefix + i;
                    pool.execute(new Runnable() {
                        @Override public void run() {
                            if (!scanning || found[0] != null) return;
                            try {
                                JSONObject info = Api.get(ip, fp, "/api/info", 700);
                                if ("unoq-remote".equals(info.optString("app"))) {
                                    synchronized (found) { if (found[0] == null) found[0] = ip; }
                                    scanning = false;
                                }
                            } catch (Exception ignored) {
                            } finally {
                                final int n = done.incrementAndGet();
                                if (n % 32 == 0 && scanning) ui.post(new Runnable() {
                                    @Override public void run() { setStatus("Searching… " + (n * 100 / total) + "%", MUTED); }
                                });
                            }
                        }
                    });
                }
                pool.shutdown();
                try { pool.awaitTermination(120, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                ui.post(new Runnable() {
                    @Override public void run() {
                        scanning = false;
                        scanBtn.setText("Find board");
                        if (found[0] != null) {
                            hostField.setText(found[0]);
                            connect();
                        } else {
                            setStatus("No board found. Is the UNO Q Remote app running on the board?", BAD);
                        }
                    }
                });
            }
        }).start();
    }

    private static List<String> localPrefixes() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String n = nif.getName();
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                        if (!out.contains(prefix)) out.add(prefix);
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ------------------------------------------------------------------ small helpers

    private void paintLed(int led) {
        int[] colors = {0xFFE53935, 0xFF43A047, 0xFF1E88E5};
        for (int c = 0; c < 3; c++) {
            boolean on = ledState[led][c] == 1;
            ledButtons[led][c].setBackground(round(on ? colors[c] : CARD_2, dp(8)));
            ledButtons[led][c].setTextColor(on ? Color.WHITE : MUTED);
        }
    }

    private void setControlsEnabled(boolean enabled) {
        controls.setAlpha(enabled ? 1f : 0.45f);
        setEnabledDeep(controls, enabled);
    }

    private static void setEnabledDeep(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), enabled);
        }
    }

    private void setStatus(String msg, int color) {
        statusText.setText(msg);
        statusText.setTextColor(color == MUTED ? MUTED : TEXT);
        statusDot.setBackground(round(color, dp(5)));
    }

    private void toastStatus(String msg, boolean good) {
        setStatus(msg, good ? OK : BAD);
    }

    void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    LinearLayout card(String title) {
        LinearLayout c = vertical();
        c.setBackground(round(CARD, dp(14)));
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        TextView t = text(title, 17, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t);
        return c;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    Button button(String label, int bg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(15);
        b.setBackground(round(bg, dp(8)));
        b.setStateListAnimator(null);
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    EditText input(String value, String hint, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setBackground(round(CARD_2, dp(8)));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private Switch styledSwitch() {
        Switch s = new Switch(this);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, 0xFFB0BAC4}));
        s.setTrackTintList(new ColorStateList(states, new int[]{0x8800979D, 0x55B0BAC4}));
        return s;
    }

    static GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
