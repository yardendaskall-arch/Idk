package app.laptopremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Connect screen: address, port, PIN, plus a Wi-Fi scan. */
public class MainActivity extends Activity {

    private Prefs prefs;
    private EditText hostEdit, portEdit, pinEdit;
    private Button connectBtn, scanBtn;
    private TextView status;
    private LinearLayout foundList;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        buildUi();
        hostEdit.setText(prefs.host());
        portEdit.setText(String.valueOf(prefs.port()));
        pinEdit.setText(prefs.pin());
    }

    @Override
    protected void onResume() {
        super.onResume();
        String why = Session.closeReason;
        if (why != null) {
            Session.closeReason = null;
            setStatus(why, true);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 28), pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.text(this, "Laptop Remote", 30, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(Ui.text(this, "Use your phone as a touchpad, keyboard and remote for your laptop.", 14, Ui.MUTED),
                Ui.stackGap(this, -1, -2, 4));

        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.box(this, Ui.SURFACE, 16));
        int cp = Ui.dp(this, 16);
        card.setPadding(cp, cp, cp, cp);
        root.addView(card, Ui.stackGap(this, -1, -2, 22));

        card.addView(Ui.text(this, "Laptop address", 13, Ui.MUTED));
        hostEdit = Ui.edit(this, "e.g. 192.168.1.23", Ui.TEXT_URI);
        hostEdit.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        card.addView(hostEdit, Ui.stackGap(this, -1, -2, 6));

        card.addView(Ui.text(this, "Port", 13, Ui.MUTED), Ui.stackGap(this, -1, -2, 14));
        portEdit = Ui.edit(this, "8765", android.text.InputType.TYPE_CLASS_NUMBER);
        portEdit.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        card.addView(portEdit, Ui.stackGap(this, -1, -2, 6));

        card.addView(Ui.text(this, "PIN (shown in the laptop's server window)", 13, Ui.MUTED), Ui.stackGap(this, -1, -2, 14));
        pinEdit = Ui.edit(this, "8 digits", android.text.InputType.TYPE_CLASS_NUMBER);
        pinEdit.setTransformationMethod(PasswordTransformationMethod.getInstance());
        pinEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pinEdit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    connect();
                    return true;
                }
                return false;
            }
        });
        card.addView(pinEdit, Ui.stackGap(this, -1, -2, 6));

        CheckBox show = new CheckBox(this);
        show.setText("Show PIN");
        show.setTextColor(Ui.MUTED);
        show.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                pinEdit.setTransformationMethod(checked
                        ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
                pinEdit.setSelection(pinEdit.length());
            }
        });
        card.addView(show, Ui.stackGap(this, -2, -2, 4));

        connectBtn = Ui.button(this, "Connect", Ui.ACCENT);
        connectBtn.setTextSize(17);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                connect();
            }
        });
        card.addView(connectBtn, Ui.stackGap(this, -1, Ui.dp(this, 54), 12));

        status = Ui.text(this, "", 14, Ui.MUTED);
        card.addView(status, Ui.stackGap(this, -1, -2, 12));

        scanBtn = Ui.button(this, "Find laptops on this Wi-Fi", Ui.SURFACE2);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        });
        root.addView(scanBtn, Ui.stackGap(this, -1, -2, 16));

        foundList = Ui.column(this);
        root.addView(foundList, Ui.stackGap(this, -1, -2, 4));

        TextView help = Ui.text(this,
                "First time? On your laptop, start the Laptop Remote server (see README). It prints the address, "
                        + "port and PIN to type in above. Your phone and laptop need to be on the same Wi-Fi, "
                        + "or connected through a VPN such as Tailscale.", 13, Ui.MUTED);
        help.setLineSpacing(0, 1.15f);
        root.addView(help, Ui.stackGap(this, -1, -2, 22));

        setContentView(scroll);
    }

    // ------------------------------------------------------------------

    private void setStatus(String msg, boolean error) {
        status.setText(msg);
        status.setTextColor(error ? 0xFFFF7B72 : Ui.MUTED);
    }

    private void setBusy(boolean b) {
        busy = b;
        connectBtn.setEnabled(!b);
        scanBtn.setEnabled(!b);
        connectBtn.setAlpha(b ? 0.5f : 1f);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void connect() {
        if (busy) return;
        final String host = hostEdit.getText().toString().trim();
        final String pin = pinEdit.getText().toString().replaceAll("[^0-9]", "");
        int p;
        try {
            p = Integer.parseInt(portEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            p = -1;
        }
        final int port = p;
        if (host.length() == 0) {
            setStatus("Enter the laptop's address (shown in the server window).", true);
            return;
        }
        if (port < 1 || port > 65535) {
            setStatus("The port must be a number between 1 and 65535.", true);
            return;
        }
        if (pin.length() < 4) {
            setStatus("Enter the PIN from the server window.", true);
            return;
        }
        hideKeyboard();
        setBusy(true);
        setStatus("Connecting to " + host + "...", false);

        final String pinned = prefs.fingerprint(host, port);
        final RemoteClient.TrustPrompt prompt = new RemoteClient.TrustPrompt() {
            public boolean confirm(String fingerprint) throws InterruptedException {
                return askToTrust(fingerprint);
            }
        };

        new Thread(new Runnable() {
            public void run() {
                try {
                    final RemoteClient c = RemoteClient.connect(host, port, pin, pinned, prompt,
                            Session.FORWARDER, Build.MODEL);
                    prefs.saveConnection(host, port, pin);
                    prefs.setFingerprint(host, port, c.info().fingerprint);
                    Session.client = c;
                    Session.info = c.info();
                    Session.closeReason = null;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            setBusy(false);
                            setStatus("", false);
                            startActivity(new Intent(MainActivity.this, ControlActivity.class));
                        }
                    });
                } catch (final RemoteClient.CertChangedException e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            setBusy(false);
                            setStatus("Connection stopped: the laptop's security certificate changed.", true);
                            showCertChanged(host, port);
                        }
                    });
                } catch (final RemoteClient.ConnectException e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            setBusy(false);
                            setStatus(describe(e), true);
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            setBusy(false);
                            setStatus(describe(e), true);
                        }
                    });
                } catch (InterruptedException e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            setBusy(false);
                        }
                    });
                }
            }
        }, "connect").start();
    }

    private static String describe(IOException e) {
        if (e instanceof RemoteClient.ConnectException) {
            String code = ((RemoteClient.ConnectException) e).code;
            if ("auth".equals(code)) return "Wrong PIN. Check the PIN in the laptop's server window.";
            if ("untrusted".equals(code)) return "Cancelled.";
            return e.getMessage();
        }
        String m = String.valueOf(e.getMessage());
        String lower = m.toLowerCase(java.util.Locale.US);
        if (lower.contains("refused")) {
            return "The laptop refused the connection. Is the server running, and is the port right?";
        }
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("unreachable")
                || lower.contains("did not answer")) {
            return "Could not reach the laptop. Are both on the same Wi-Fi? Is the laptop's firewall "
                    + "allowing the server?";
        }
        if (lower.contains("unable to resolve") || lower.contains("unknown host")) {
            return "That address was not found. Use the numeric address from the server window.";
        }
        if (lower.contains("ssl") || lower.contains("tls") || lower.contains("handshake")
                || lower.contains("unrecognized")) {
            return "That address and port are not a Laptop Remote server.";
        }
        return "Could not connect: " + m;
    }

    /** Called on the connect thread: shows the fingerprint dialog and waits for the answer. */
    private boolean askToTrust(final String fingerprint) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] answer = new boolean[1];
        runOnUiThread(new Runnable() {
            public void run() {
                if (isFinishing()) {
                    latch.countDown();
                    return;
                }
                TextView fp = Ui.mono(MainActivity.this, RemoteClient.formatFingerprint(fingerprint), 15);
                fp.setPadding(Ui.dp(MainActivity.this, 24), Ui.dp(MainActivity.this, 8),
                        Ui.dp(MainActivity.this, 24), 0);
                fp.setTextIsSelectable(true);
                LinearLayout box = Ui.column(MainActivity.this);
                TextView explain = Ui.text(MainActivity.this,
                        "This phone has not connected to this laptop before.\n\n"
                                + "Look at the server window on your laptop: the \"Fingerprint\" it shows must be "
                                + "the same as the code below. If it is, tap \"Trust\". If it is different, "
                                + "someone else may be pretending to be your laptop - tap Cancel.",
                        14, Ui.TEXT);
                explain.setPadding(Ui.dp(MainActivity.this, 24), Ui.dp(MainActivity.this, 8),
                        Ui.dp(MainActivity.this, 24), 0);
                box.addView(explain);
                box.addView(fp);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Is this your laptop?")
                        .setView(box)
                        .setCancelable(false)
                        .setPositiveButton("They match - Trust", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                answer[0] = true;
                                latch.countDown();
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                latch.countDown();
                            }
                        })
                        .show();
            }
        });
        if (!latch.await(180, TimeUnit.SECONDS)) return false;
        return answer[0];
    }

    private void showCertChanged(final String host, final int port) {
        new AlertDialog.Builder(this)
                .setTitle("Laptop's certificate changed")
                .setMessage("This laptop now identifies itself differently than when you first connected.\n\n"
                        + "That is expected if you reset the server (--new-cert) or set it up again. "
                        + "If you did not, someone on your network may be intercepting the connection.\n\n"
                        + "Forget the old certificate and check the new one?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget and re-check", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.clearFingerprint(host, port);
                        setStatus("Tap Connect to verify the laptop's new fingerprint.", false);
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------

    private void scan() {
        if (busy) return;
        setBusy(true);
        setStatus("Looking for laptops...", false);
        foundList.removeAllViews();
        new Thread(new Runnable() {
            public void run() {
                List<Discovery.Found> found = null;
                String error = null;
                try {
                    found = Discovery.scan(2000);
                } catch (IOException e) {
                    error = "Scan failed: " + e.getMessage();
                }
                final List<Discovery.Found> result = found;
                final String err = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        setBusy(false);
                        if (err != null) {
                            setStatus(err, true);
                        } else if (result.isEmpty()) {
                            setStatus("No laptops found. Make sure the server is running, then enter the address "
                                    + "by hand (some Wi-Fi networks block scanning).", true);
                        } else {
                            setStatus("Tap your laptop below, then enter its PIN.", false);
                            for (final Discovery.Found f : result) {
                                Button b = Ui.button(MainActivity.this,
                                        f.name + "   (" + f.host + ")", Ui.SURFACE2);
                                b.setOnClickListener(new View.OnClickListener() {
                                    public void onClick(View v) {
                                        hostEdit.setText(f.host);
                                        portEdit.setText(String.valueOf(f.port));
                                        pinEdit.requestFocus();
                                    }
                                });
                                foundList.addView(b, Ui.stackGap(MainActivity.this, -1, -2, 8));
                            }
                        }
                    }
                });
            }
        }, "scan").start();
    }
}
