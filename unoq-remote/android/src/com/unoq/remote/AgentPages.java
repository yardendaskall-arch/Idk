package com.unoq.remote;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The System and Terminal tabs. They talk to the host agent (port 7001) with a PIN. */
final class AgentPages {
    private static final int TERM_BG = 0xFF0B0E11;
    private static final int TERM_CMD = 0xFF7FDBCA;
    private static final int INFO_MS = 3000;

    private final MainActivity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService sysExec = Executors.newSingleThreadExecutor();
    private final ExecutorService termExec = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;

    private String pin;
    private boolean unlocked;
    private int tab;
    private boolean infoBusy;

    // system views
    private LinearLayout sysLock, sysBody;
    private TextView sysLockMsg, overview, wifiNow, tempText;
    private EditText sysPin;
    private ProgressBar cpuBar, memBar, diskBar;
    private TextView cpuVal, memVal, diskVal;
    private LinearLayout netList, procList;

    // terminal views
    private LinearLayout termLock, termBody;
    private TextView termLockMsg, termOut;
    private EditText termPin, termIn;
    private ScrollView termScroll;
    private Button runBtn;
    private String cwd = "", prompt = "board";
    private final List<String> history = new ArrayList<>();
    private int histPos;

    AgentPages(MainActivity activity) {
        a = activity;
        prefs = a.getSharedPreferences("unoq", MainActivity.MODE_PRIVATE);
        pin = prefs.getString("pin", "");
        cwd = prefs.getString("cwd", "");
    }

    // ------------------------------------------------------------------ lifecycle

    void onTabShown(int i) {
        tab = i;
        ui.removeCallbacks(infoPoller);
        if (i == 0) return;
        if (!unlocked && !pin.isEmpty()) tryUnlock(pin, null);
        if (i == 1 && unlocked) { ui.post(infoPoller); loadProcesses(); }
        if (i == 2 && unlocked) termIn.requestFocus();
    }

    void stop() { ui.removeCallbacks(infoPoller); }

    void shutdown() {
        stop();
        sysExec.shutdownNow();
        termExec.shutdownNow();
    }

    private final Runnable infoPoller = new Runnable() {
        @Override public void run() {
            if (tab != 1 || !unlocked) return;
            ui.postDelayed(this, INFO_MS);
            loadInfo();
        }
    };

    // ------------------------------------------------------------------ PIN

    private LinearLayout lockCard(final boolean forSystem) {
        LinearLayout c = a.card("Unlock system controls");
        TextView t = a.text("Enter the PIN shown when you installed the UNO Q Remote agent on the board. "
                + "It uses the board address from the Pins tab, port 7001.", 14, MainActivity.MUTED);
        c.addView(t);
        LinearLayout row = a.horizontal();
        row.setPadding(0, a.dp(10), 0, 0);
        final EditText field = a.input("", "PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        row.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button b = a.button("Unlock", MainActivity.ACCENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(100), a.dp(46));
        lp.leftMargin = a.dp(8);
        row.addView(b, lp);
        c.addView(row);
        final TextView msg = a.text("", 14, MainActivity.MUTED);
        msg.setPadding(0, a.dp(8), 0, 0);
        c.addView(msg);
        if (forSystem) { sysPin = field; sysLockMsg = msg; } else { termPin = field; termLockMsg = msg; }
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.hideKeyboard();
                tryUnlock(field.getText().toString().trim(), msg);
            }
        });
        return c;
    }

    private void tryUnlock(final String candidate, final TextView msg) {
        if (msg != null) { msg.setText("Checking…"); msg.setTextColor(MainActivity.MUTED); }
        final String host = a.boardHost();
        sysExec.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    Api.agent(host, "/api/auth", candidate, null, 6000);
                } catch (Api.WrongPin e) {
                    err = "Wrong PIN.";
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fErr = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (fErr == null) {
                            pin = candidate;
                            prefs.edit().putString("pin", pin).apply();
                            setUnlocked(true);
                            onTabShown(tab);
                        } else if (msg != null) {
                            msg.setText(fErr);
                            msg.setTextColor(MainActivity.BAD);
                        }
                    }
                });
            }
        });
    }

    private void setUnlocked(boolean on) {
        unlocked = on;
        sysLock.setVisibility(on ? View.GONE : View.VISIBLE);
        sysBody.setVisibility(on ? View.VISIBLE : View.GONE);
        termLock.setVisibility(on ? View.GONE : View.VISIBLE);
        termBody.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) ui.removeCallbacks(infoPoller);
    }

    /** Runs an agent call on the system thread; shows errors as a short message. */
    private interface Call { JSONObject call(String host) throws Exception; }
    private interface Done { void done(JSONObject r); }

    private void sys(final String what, final Call call, final Done done) {
        final String host = a.boardHost();
        sysExec.execute(new Runnable() {
            @Override public void run() {
                try {
                    final JSONObject r = call.call(host);
                    ui.post(new Runnable() { @Override public void run() { done.done(r); } });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (e instanceof Api.WrongPin) { setUnlocked(false); return; }
                            if (what.equals("System info")) { overview.setText("Can't load system info: " + e.getMessage()); return; }
                            toast(what + ": " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void toast(String s) {
        android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ System page

    View buildSystemPage() {
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(MainActivity.BG);
        LinearLayout root = a.vertical();
        root.setPadding(a.dp(16), a.dp(20), a.dp(16), a.dp(24));
        scroll.addView(root);
        TextView title = a.text("System", 26, MainActivity.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = a.text("The UNO Q's Linux side", 14, MainActivity.MUTED);
        sub.setPadding(0, a.dp(2), 0, a.dp(4));
        root.addView(sub);

        sysLock = lockCard(true);
        root.addView(sysLock);
        sysBody = a.vertical();
        root.addView(sysBody);

        LinearLayout ov = a.card("Overview");
        overview = a.text("…", 14, MainActivity.TEXT);
        overview.setLineSpacing(0, 1.25f);
        ov.addView(overview);
        sysBody.addView(ov);

        LinearLayout usage = a.card("Usage");
        cpuBar = bar(); cpuVal = val();
        memBar = bar(); memVal = val();
        diskBar = bar(); diskVal = val();
        usage.addView(meterRow("CPU", cpuBar, cpuVal));
        usage.addView(meterRow("RAM", memBar, memVal));
        usage.addView(meterRow("Disk", diskBar, diskVal));
        tempText = a.text("Temperature: —", 14, MainActivity.TEXT);
        tempText.setPadding(0, a.dp(6), 0, 0);
        usage.addView(tempText);
        sysBody.addView(usage);

        LinearLayout wifi = a.card("Wi-Fi");
        LinearLayout wrow = a.horizontal();
        wrow.setGravity(Gravity.CENTER_VERTICAL);
        wifiNow = a.text("—", 14, MainActivity.MUTED);
        wrow.addView(wifiNow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button scan = a.button("Scan", MainActivity.CARD_2);
        wrow.addView(scan, new LinearLayout.LayoutParams(a.dp(90), a.dp(42)));
        wifi.addView(wrow);
        netList = a.vertical();
        wifi.addView(netList);
        sysBody.addView(wifi);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scanWifi(); }
        });

        LinearLayout procs = a.card("Processes");
        Button refresh = a.button("Refresh", MainActivity.CARD_2);
        procs.addView(refresh, new LinearLayout.LayoutParams(a.dp(110), a.dp(40)));
        procList = a.vertical();
        procList.setPadding(0, a.dp(6), 0, 0);
        procs.addView(procList);
        sysBody.addView(procs);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadProcesses(); }
        });

        LinearLayout power = a.card("Power");
        LinearLayout prow = a.horizontal();
        Button reboot = a.button("Reboot", MainActivity.CARD_2);
        Button off = a.button("Shut down", 0xFF8E2B22);
        prow.addView(reboot, new LinearLayout.LayoutParams(0, a.dp(46), 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(46), 1f);
        lp.leftMargin = a.dp(8);
        prow.addView(off, lp);
        power.addView(prow);
        Button lock = a.button("Forget PIN", MainActivity.CARD_2);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(42));
        ll.topMargin = a.dp(8);
        power.addView(lock, ll);
        sysBody.addView(power);
        reboot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmPower("reboot", "Reboot the UNO Q?"); }
        });
        off.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmPower("shutdown", "Shut down the UNO Q? You'll need to unplug and replug it to start it again.");
            }
        });
        lock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pin = "";
                prefs.edit().remove("pin").apply();
                setUnlocked(false);
            }
        });

        setUnlockedViewsInitial();
        return scroll;
    }

    private void setUnlockedViewsInitial() {
        if (sysLock != null) { sysLock.setVisibility(View.VISIBLE); sysBody.setVisibility(View.GONE); }
    }

    private ProgressBar bar() {
        ProgressBar b = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        b.setMax(1000);
        b.setProgressTintList(ColorStateList.valueOf(MainActivity.ACCENT));
        b.setProgressBackgroundTintList(ColorStateList.valueOf(MainActivity.CARD_2));
        return b;
    }

    private TextView val() {
        TextView t = a.text("—", 14, MainActivity.TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.END);
        return t;
    }

    private View meterRow(String name, ProgressBar bar, TextView val) {
        LinearLayout row = a.horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, a.dp(4), 0, a.dp(4));
        TextView n = a.text(name, 15, MainActivity.TEXT);
        n.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(n, new LinearLayout.LayoutParams(a.dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(bar, new LinearLayout.LayoutParams(0, a.dp(12), 1f));
        row.addView(val, new LinearLayout.LayoutParams(a.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static String bytes(long b) {
        return b > 1e9 ? String.format(Locale.US, "%.1f GB", b / 1e9) : String.format(Locale.US, "%.0f MB", b / 1e6);
    }

    private void loadInfo() {
        if (infoBusy) return;
        infoBusy = true;
        sys("System info", new Call() {
            @Override public JSONObject call(String host) throws Exception {
                try { return Api.agent(host, "/api/os/info", pin, null, 8000); } finally { infoBusy = false; }
            }
        }, new Done() {
            @Override public void done(JSONObject i) {
                long up = i.optLong("uptime");
                StringBuilder ips = new StringBuilder();
                JSONArray arr = i.optJSONArray("ips");
                for (int k = 0; arr != null && k < arr.length(); k++) {
                    JSONObject o = arr.optJSONObject(k);
                    ips.append(k == 0 ? "" : ", ").append(o.optString("ip")).append(" (").append(o.optString("iface")).append(')');
                }
                JSONArray load = i.optJSONArray("load");
                overview.setText("Hostname:  " + i.optString("hostname")
                        + "\nSystem:  " + i.optString("os")
                        + "\nKernel:  " + i.optString("kernel")
                        + "\nUptime:  " + (up / 86400 > 0 ? up / 86400 + "d " : "") + (up % 86400) / 3600 + "h " + (up % 3600) / 60 + "m"
                        + "\nCPUs:  " + i.optInt("cpus") + (load != null ? String.format(Locale.US, "  (load %.2f)", load.optDouble(0)) : "")
                        + "\nAddresses:  " + (ips.length() == 0 ? "—" : ips)
                        + "\nAgent runs as:  " + i.optString("user"));
                double cpu = i.optDouble("cpu", 0);
                cpuBar.setProgress((int) (cpu * 10));
                cpuVal.setText(String.format(Locale.US, "%.1f%%", cpu));
                JSONObject mem = i.optJSONObject("mem"), disk = i.optJSONObject("disk");
                if (mem != null && mem.optLong("total") > 0) {
                    memBar.setProgress((int) (1000 * mem.optLong("used") / mem.optLong("total")));
                    memVal.setText(bytes(mem.optLong("used")));
                }
                if (disk != null && disk.optLong("total") > 0) {
                    diskBar.setProgress((int) (1000 * disk.optLong("used") / disk.optLong("total")));
                    diskVal.setText(bytes(disk.optLong("used")));
                }
                tempText.setText("Temperature: " + (i.isNull("temp") ? "—" : i.optDouble("temp") + " °C"));
                wifiNow.setText(i.isNull("wifi") ? "Not connected to Wi-Fi" : "Connected to " + i.optString("wifi"));
            }
        });
    }

    private void scanWifi() {
        netList.removeAllViews();
        netList.addView(a.text("Scanning…", 14, MainActivity.MUTED));
        sys("Wi-Fi scan", new Call() {
            @Override public JSONObject call(String host) throws Exception {
                return Api.agent(host, "/api/os/wifi/scan", pin, null, 40000);
            }
        }, new Done() {
            @Override public void done(JSONObject r) {
                netList.removeAllViews();
                JSONArray nets = r.optJSONArray("networks");
                if (nets == null || nets.length() == 0) {
                    netList.addView(a.text("No networks found.", 14, MainActivity.MUTED));
                    return;
                }
                for (int k = 0; k < nets.length(); k++) {
                    final JSONObject n = nets.optJSONObject(k);
                    LinearLayout row = a.horizontal();
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, a.dp(10), 0, a.dp(10));
                    TextView name = a.text((n.optBoolean("active") ? "✔ " : "") + n.optString("ssid")
                            + (n.optBoolean("secure") ? "  🔒" : ""), 15, MainActivity.TEXT);
                    row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(a.text(n.optInt("signal") + "%", 14, MainActivity.MUTED));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { askWifi(n.optString("ssid"), n.optBoolean("secure")); }
                    });
                    netList.addView(row);
                }
            }
        });
    }

    private void askWifi(final String ssid, boolean secure) {
        if (!secure) { connectWifi(ssid, ""); return; }
        final EditText pw = new EditText(a);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setHint("Password");
        LinearLayout box = a.vertical();
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        box.addView(pw);
        new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Connect to " + ssid)
                .setView(box)
                .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { connectWifi(ssid, pw.getText().toString()); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connectWifi(final String ssid, final String password) {
        toast("Connecting the board to " + ssid + "…");
        sys("Wi-Fi", new Call() {
            @Override public JSONObject call(String host) throws Exception {
                JSONObject body = new JSONObject();
                body.put("ssid", ssid);
                body.put("password", password);
                return Api.agent(host, "/api/os/wifi/connect", pin, body, 70000);
            }
        }, new Done() {
            @Override public void done(JSONObject r) {
                toast("Board connected to " + ssid + ". If its address changed, update it on the Pins tab.");
                loadInfo();
            }
        });
    }

    private void loadProcesses() {
        sys("Processes", new Call() {
            @Override public JSONObject call(String host) throws Exception {
                return Api.agent(host, "/api/os/processes", pin, null, 10000);
            }
        }, new Done() {
            @Override public void done(JSONObject r) {
                procList.removeAllViews();
                JSONArray ps = r.optJSONArray("processes");
                for (int k = 0; ps != null && k < Math.min(25, ps.length()); k++) {
                    final JSONObject p = ps.optJSONObject(k);
                    LinearLayout row = a.horizontal();
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, a.dp(4), 0, a.dp(4));
                    LinearLayout col = a.vertical();
                    col.addView(a.text(p.optString("name"), 15, MainActivity.TEXT));
                    TextView meta = a.text("PID " + p.optInt("pid") + " · " + p.optString("user")
                            + " · CPU " + p.optDouble("cpu") + "% · MEM " + p.optDouble("mem") + "%", 12, MainActivity.MUTED);
                    col.addView(meta);
                    row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    Button stop = a.button("Stop", MainActivity.CARD_2);
                    stop.setTextSize(13);
                    row.addView(stop, new LinearLayout.LayoutParams(a.dp(70), a.dp(38)));
                    stop.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { confirmKill(p.optInt("pid"), p.optString("name")); }
                    });
                    procList.addView(row);
                }
            }
        });
    }

    private void confirmKill(final int pid, final String name) {
        new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Stop " + name + "?")
                .setMessage("PID " + pid + ". \"Force\" kills it immediately.")
                .setPositiveButton("Stop", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { kill(pid, false); }
                })
                .setNeutralButton("Force", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { kill(pid, true); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void kill(final int pid, final boolean force) {
        sys("Stop", new Call() {
            @Override public JSONObject call(String host) throws Exception {
                JSONObject body = new JSONObject();
                body.put("pid", pid);
                body.put("force", force);
                return Api.agent(host, "/api/os/kill", pin, body, 10000);
            }
        }, new Done() {
            @Override public void done(JSONObject r) {
                toast("Stopped " + pid);
                ui.postDelayed(new Runnable() { @Override public void run() { loadProcesses(); } }, 600);
            }
        });
    }

    private void confirmPower(final String action, String question) {
        new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(question)
                .setPositiveButton(action.equals("reboot") ? "Reboot" : "Shut down", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sys("Power", new Call() {
                            @Override public JSONObject call(String host) throws Exception {
                                JSONObject body = new JSONObject();
                                body.put("action", action);
                                return Api.agent(host, "/api/os/power", pin, body, 10000);
                            }
                        }, new Done() {
                            @Override public void done(JSONObject r) {
                                toast(action.equals("reboot") ? "Rebooting… reconnect in about a minute." : "Shutting down…");
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ Terminal page

    View buildTerminalPage() {
        LinearLayout root = a.vertical();
        root.setBackgroundColor(MainActivity.BG);
        root.setPadding(a.dp(12), a.dp(16), a.dp(12), a.dp(8));
        TextView title = a.text("Terminal", 26, MainActivity.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(a.dp(4), 0, 0, a.dp(4));
        root.addView(title);

        termLock = lockCard(false);
        ScrollView lockScroll = new ScrollView(a);
        lockScroll.addView(termLock);
        root.addView(lockScroll);

        termBody = a.vertical();
        root.addView(termBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        termScroll = new ScrollView(a);
        termScroll.setBackground(MainActivity.round(TERM_BG, a.dp(12)));
        termScroll.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
        termOut = new TextView(a);
        termOut.setTypeface(Typeface.MONOSPACE);
        termOut.setTextSize(12.5f);
        termOut.setTextColor(MainActivity.TEXT);
        termOut.setTextIsSelectable(true);
        termOut.setText("Commands run on the UNO Q's Linux system.\n"
                + "Interactive programs (nano, top, password prompts) aren't supported. Use e.g. \"top -bn1\".\n\n");
        termScroll.addView(termOut);
        termBody.addView(termScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = a.horizontal();
        row.setPadding(0, a.dp(8), 0, 0);
        termIn = a.input("", "command", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        termIn.setTypeface(Typeface.MONOSPACE);
        termIn.setImeOptions(EditorInfo.IME_ACTION_SEND);
        row.addView(termIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        runBtn = a.button("Run", MainActivity.ACCENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(70), a.dp(46));
        lp.leftMargin = a.dp(6);
        row.addView(runBtn, lp);
        termBody.addView(row);

        LinearLayout keys = a.horizontal();
        keys.setPadding(0, a.dp(6), 0, 0);
        String[] labels = {"↑", "↓", "|", "~", "/", "-", "&&", "Clear"};
        for (final String k : labels) {
            Button b = a.button(k, MainActivity.CARD_2);
            b.setTextSize(13);
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, a.dp(38), k.equals("Clear") ? 1.6f : 1f);
            kl.rightMargin = a.dp(4);
            keys.addView(b, kl);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { key(k); }
            });
        }
        termBody.addView(keys);

        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runCommand(); }
        });
        termIn.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (id == EditorInfo.IME_ACTION_SEND || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && e.getAction() == KeyEvent.ACTION_DOWN)) {
                    runCommand();
                    return true;
                }
                return false;
            }
        });

        termLock.setVisibility(View.VISIBLE);
        termBody.setVisibility(View.GONE);
        return root;
    }

    private void key(String k) {
        switch (k) {
            case "↑":
                if (histPos > 0) { termIn.setText(history.get(--histPos)); termIn.setSelection(termIn.length()); }
                break;
            case "↓":
                histPos = Math.min(history.size(), histPos + 1);
                termIn.setText(histPos < history.size() ? history.get(histPos) : "");
                termIn.setSelection(termIn.length());
                break;
            case "Clear":
                termOut.setText("");
                break;
            default:
                insert(k);
        }
    }

    private void insert(String s) {
        int st = Math.max(termIn.getSelectionStart(), 0);
        termIn.getText().insert(st, s);
    }

    private void append(String s, int color) {
        android.text.SpannableString span = new android.text.SpannableString(s);
        span.setSpan(new android.text.style.ForegroundColorSpan(color), 0, s.length(), 0);
        termOut.append(span);
        termScroll.post(new Runnable() {
            @Override public void run() { termScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void runCommand() {
        final String c = termIn.getText().toString();
        if (c.trim().isEmpty() || !runBtn.isEnabled()) return;
        if (c.trim().equals("clear")) { termOut.setText(""); termIn.setText(""); return; }
        history.add(c);
        histPos = history.size();
        termIn.setText("");
        append(prompt + ":" + (cwd.isEmpty() ? "~" : cwd) + "$ " + c + "\n", TERM_CMD);
        runBtn.setEnabled(false);
        runBtn.setText("…");
        final String host = a.boardHost();
        final String curCwd = cwd;
        termExec.execute(new Runnable() {
            @Override public void run() {
                String out;
                int color = MainActivity.TEXT;
                try {
                    JSONObject body = new JSONObject();
                    body.put("cmd", c);
                    body.put("cwd", curCwd);
                    body.put("timeout", 60);
                    final JSONObject r = Api.agent(host, "/api/term/exec", pin, body, 75000);
                    out = r.optString("output");
                    if (r.optInt("code") != 0) color = 0xFFFF8A80;
                    final String nc = r.optString("cwd", curCwd), np = r.optString("prompt", prompt);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            cwd = nc;
                            prompt = np;
                            prefs.edit().putString("cwd", cwd).apply();
                        }
                    });
                } catch (Api.WrongPin e) {
                    out = "wrong PIN";
                    color = MainActivity.BAD;
                    ui.post(new Runnable() { @Override public void run() { setUnlocked(false); } });
                } catch (Exception e) {
                    out = e.getMessage();
                    color = MainActivity.BAD;
                }
                final String fOut = out == null ? "" : out;
                final int fColor = color;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!fOut.isEmpty()) append(fOut + "\n", fColor);
                        runBtn.setEnabled(true);
                        runBtn.setText("Run");
                    }
                });
            }
        });
    }
}
