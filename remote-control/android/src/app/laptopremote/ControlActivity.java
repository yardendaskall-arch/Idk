package app.laptopremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The remote itself: Touchpad / Keys / Media / Screen / Power tabs. */
public class ControlActivity extends Activity implements RemoteClient.Listener {

    private static final int TAB_PAD = 0, TAB_KEYS = 1, TAB_MEDIA = 2, TAB_SCREEN = 3, TAB_POWER = 4;
    private static final String[] TAB_NAMES = {"Touchpad", "Keys", "Media", "Screen", "Power"};
    private static final String[] MOD_KEYS = {"ctrl", "alt", "shift", "win"};
    /** width, JPEG quality, frames per second */
    private static final int[][] QUALITY = {{800, 40, 4}, {1280, 55, 6}, {1920, 70, 8}};
    private static final String SENTINEL = "​";   // invisible character the live-typing box always holds
    private static final String[] BUTTON_NAMES = {"left", "right", "middle"};

    private RemoteClient client;
    private RemoteClient.ServerInfo info;
    private Prefs prefs;

    private final View[] panels = new View[5];
    private final TextView[] tabs = new TextView[5];
    private int current = TAB_PAD;

    private TouchpadView touchpad;
    private ScreenView screenView;
    private EditText liveInput;
    private boolean ignoreWatcher;
    private final boolean[] modOn = new boolean[4];
    private final Button[] modButtons = new Button[4];
    private final Button[] qualityButtons = new Button[3];
    private final boolean[] held = new boolean[3];
    private long lastNoticeAt;
    private String lastNotice = "";

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        client = Session.client;
        info = Session.info;
        if (client == null || info == null || !client.isOpen()) {
            client = null;
            finish();
            return;
        }
        prefs = new Prefs(this);
        Session.target = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        setContentView(buildUi());
        showTab(TAB_PAD);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (client != null && current == TAB_SCREEN) startScreen();
    }

    @Override
    protected void onStop() {
        if (client != null) {
            if (current == TAB_SCREEN) stopScreen();
            touchpad.release();
            for (int i = 0; i < held.length; i++) {
                if (held[i]) {
                    held[i] = false;
                    client.button(BUTTON_NAMES[i], false);
                }
            }
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (client != null) Session.end();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        confirm("Disconnect?", "Stop controlling " + info.name + "?", "Disconnect", new Ui.Action() {
            public void run() {
                finish();
            }
        });
    }

    // ------------------------------------------------------------------
    // RemoteClient.Listener (background threads)
    // ------------------------------------------------------------------

    public void onFrame(byte[] jpeg) {
        ScreenView v = screenView;
        if (v != null && current == TAB_SCREEN) v.setFrame(jpeg);
    }

    public void onNotice(final String kind, final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                long now = System.currentTimeMillis();
                if (message.equals(lastNotice) && now - lastNoticeAt < 3000) return;
                lastNotice = message;
                lastNoticeAt = now;
                Toast.makeText(ControlActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void onClosed(String reason) {
        runOnUiThread(new Runnable() {
            public void run() {
                finish();
            }
        });
    }

    // ------------------------------------------------------------------
    // layout
    // ------------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        // header
        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 4));
        SpannableStringBuilder sb = new SpannableStringBuilder("● ");
        sb.setSpan(new ForegroundColorSpan(Ui.OK), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(info.name);
        TextView title = Ui.text(this, "", 15, Ui.TEXT);
        title.setText(sb);
        title.setSingleLine(true);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button disconnect = Ui.button(this, "Disconnect", Ui.SURFACE2);
        disconnect.setTextSize(13);
        disconnect.setMinHeight(Ui.dp(this, 38));
        disconnect.setMinimumHeight(Ui.dp(this, 38));
        disconnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBackPressed();
            }
        });
        header.addView(disconnect);
        root.addView(header);

        // content
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        panels[TAB_PAD] = buildTouchpad();
        panels[TAB_KEYS] = buildKeys();
        panels[TAB_MEDIA] = buildMedia();
        panels[TAB_SCREEN] = buildScreen();
        panels[TAB_POWER] = buildPower();
        for (View p : panels) {
            content.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // tab bar
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.SURFACE);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            TextView t = Ui.text(this, TAB_NAMES[i], 13, Ui.MUTED);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showTab(index);
                }
            });
            tabs[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, Ui.dp(this, 54), 1f));
        }
        root.addView(bar);
        return root;
    }

    private void showTab(int index) {
        if (current == TAB_SCREEN && index != TAB_SCREEN) stopScreen();
        current = index;
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == index ? Ui.TEXT : Ui.MUTED);
            tabs[i].setTypeface(i == index ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabs[i].setBackground(i == index ? Ui.box(this, Ui.SURFACE2, 0) : null);
        }
        if (index != TAB_KEYS) hideKeyboard();
        if (index != TAB_PAD) touchpad.release();
        if (index == TAB_SCREEN) startScreen();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void confirm(String title, String message, String yes, final Ui.Action action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        action.run();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------
    // Touchpad tab
    // ------------------------------------------------------------------

    private View buildTouchpad() {
        LinearLayout p = Ui.column(this);
        int m = Ui.dp(this, 10);
        p.setPadding(m, m, m, m);

        touchpad = new TouchpadView(this);
        touchpad.setSpeed(prefs.speed());
        touchpad.setNaturalScroll(prefs.naturalScroll());
        touchpad.setListener(new TouchpadView.Listener() {
            public void onMove(int dx, int dy) {
                client.move(dx, dy);
            }

            public void onScroll(int dx, int dy) {
                client.scroll(dx, dy);
            }

            public void onClick(String button) {
                client.click(button, 1);
            }

            public void onDrag(boolean down) {
                client.button("left", down);
            }
        });
        p.addView(touchpad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = Ui.row(this);
        String[] labels = {"Left", "Middle", "Right"};
        int[] order = {0, 2, 1};            // left, middle, right
        for (int k = 0; k < 3; k++) {
            Button b = Ui.button(this, labels[k], Ui.SURFACE2);
            b.setOnTouchListener(holdButton(order[k]));
            buttons.addView(b, Ui.share(this, Ui.dp(this, 52), 3));
        }
        p.addView(buttons, Ui.stackGap(this, -1, -2, 6));

        LinearLayout opts = Ui.row(this);
        opts.setGravity(Gravity.CENTER_VERTICAL);
        opts.addView(Ui.text(this, "Speed", 13, Ui.MUTED));
        SeekBar speed = new SeekBar(this);
        speed.setMax(100);
        speed.setProgress(Math.round((prefs.speed() - 0.4f) / 2.6f * 100f));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                touchpad.setSpeed(0.4f + progress / 100f * 2.6f);
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                prefs.setSpeed(0.4f + s.getProgress() / 100f * 2.6f);
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = Ui.dp(this, 8);
        opts.addView(speed, sp);
        CheckBox natural = new CheckBox(this);
        natural.setText("Natural scroll");
        natural.setTextColor(Ui.MUTED);
        natural.setTextSize(13);
        natural.setChecked(prefs.naturalScroll());
        natural.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setNaturalScroll(checked);
                touchpad.setNaturalScroll(checked);
            }
        });
        opts.addView(natural);
        p.addView(opts, Ui.stackGap(this, -1, -2, 4));
        return p;
    }

    /** Mouse button that stays pressed while held (so you can drag with one thumb on it, one on the pad). */
    private View.OnTouchListener holdButton(final int index) {
        return new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        held[index] = true;
                        client.button(BUTTON_NAMES[index], true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        held[index] = false;
                        client.button(BUTTON_NAMES[index], false);
                        break;
                    default:
                        break;
                }
                return false;
            }
        };
    }

    // ------------------------------------------------------------------
    // Keys tab
    // ------------------------------------------------------------------

    private View buildKeys() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout p = Ui.column(this);
        int m = Ui.dp(this, 12);
        p.setPadding(m, m, m, m);
        scroll.addView(p, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        p.addView(Ui.text(this, "Tap the box and type - the text appears on your laptop.", 13, Ui.MUTED));
        liveInput = new EditText(this);
        liveInput.setTextColor(Ui.TEXT);
        liveInput.setTextSize(18);
        liveInput.setBackground(Ui.box(this, Ui.SURFACE2, 10));
        liveInput.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        // Visible-password style keeps the keyboard from composing/auto-correcting words,
        // so every key press arrives as a plain character change.
        liveInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        liveInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE);
        resetLiveInput();
        liveInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (ignoreWatcher) return;
                if (count > 0) {
                    typed(s.subSequence(start, start + count).toString().replace(SENTINEL, ""));
                } else if (before > 0) {
                    sendKey("backspace");
                }
            }

            public void afterTextChanged(Editable s) {
                if (ignoreWatcher) return;
                if (s.length() == 1 && SENTINEL.equals(s.toString())) return;
                ignoreWatcher = true;
                s.replace(0, s.length(), SENTINEL);
                ignoreWatcher = false;
            }
        });
        liveInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                sendKey("enter");
                return true;                      // keep the keyboard open
            }
        });
        liveInput.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                String name = keyNameFor(keyCode);
                if (name == null) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) sendKey(name);
                return true;
            }
        });
        p.addView(liveInput, Ui.stackGap(this, -1, -2, 6));

        // modifiers
        p.addView(Ui.text(this, "Modifiers (tap, then press a key):", 13, Ui.MUTED), Ui.stackGap(this, -1, -2, 12));
        LinearLayout mods = Ui.row(this);
        boolean mac = "mac".equals(info.os);
        String[] modLabels = {"Ctrl", mac ? "Option" : "Alt", "Shift", mac ? "Cmd" : "Win"};
        for (int i = 0; i < 4; i++) {
            final int index = i;
            Button b = Ui.button(this, modLabels[i], Ui.SURFACE2);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    modOn[index] = !modOn[index];
                    refreshMods();
                }
            });
            modButtons[i] = b;
            mods.addView(b, Ui.share(this, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
        }
        p.addView(mods);

        // special keys
        p.addView(Ui.text(this, "Keys", 13, Ui.MUTED), Ui.stackGap(this, -1, -2, 12));
        addKeyRow(p, new String[]{"Esc", "Tab", "Enter", "Backspace", "Del"},
                new String[]{"esc", "tab", "enter", "backspace", "delete"},
                new boolean[]{false, false, false, true, true});
        addKeyRow(p, new String[]{"Home", "End", "PgUp", "PgDn"},
                new String[]{"home", "end", "page_up", "page_down"},
                new boolean[]{false, false, true, true});
        addKeyRow(p, new String[]{"←", "↑", "↓", "→"},
                new String[]{"left", "up", "down", "right"},
                new boolean[]{true, true, true, true});

        HorizontalScrollView fScroll = new HorizontalScrollView(this);
        fScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout fRow = Ui.row(this);
        for (int i = 1; i <= 12; i++) {
            final String key = "f" + i;
            Button b = Ui.button(this, "F" + i, Ui.SURFACE2);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    sendKey(key);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 56), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
            fRow.addView(b, lp);
        }
        fScroll.addView(fRow);
        p.addView(fScroll, Ui.stackGap(this, -1, -2, 2));

        // shortcuts
        p.addView(Ui.text(this, "Shortcuts", 13, Ui.MUTED), Ui.stackGap(this, -1, -2, 12));
        List<String[]> sc = new ArrayList<String[]>();
        sc.add(new String[]{"Copy", "c", "primary"});
        sc.add(new String[]{"Paste", "v", "primary"});
        sc.add(new String[]{"Cut", "x", "primary"});
        sc.add(new String[]{"Select all", "a", "primary"});
        sc.add(new String[]{"Undo", "z", "primary"});
        sc.add(new String[]{"Redo", "z", "primary", "shift"});
        sc.add(new String[]{"Save", "s", "primary"});
        sc.add(new String[]{"Find", "f", "primary"});
        sc.add(new String[]{"Refresh", "r", "primary"});
        sc.add(new String[]{"New tab", "t", "primary"});
        sc.add(new String[]{"Close tab", "w", "primary"});
        sc.add(new String[]{"Reopen tab", "t", "primary", "shift"});
        sc.add(new String[]{"Switch app", "tab", "switch"});
        sc.add(mac ? new String[]{"Show desktop", "f11"} : new String[]{"Show desktop", "d", "win"});
        sc.add(mac ? new String[]{"Spotlight", "space", "win"} : new String[]{"Start menu", "win"});
        sc.add(new String[]{"Full screen", mac ? "f" : "f11", mac ? "primary" : null, mac ? "ctrl" : null});
        LinearLayout rowLayout = null;
        for (int i = 0; i < sc.size(); i++) {
            if (i % 3 == 0) {
                rowLayout = Ui.row(this);
                p.addView(rowLayout);
            }
            final String[] spec = clean(sc.get(i));
            Button b = Ui.button(this, spec[0], Ui.SURFACE2);
            b.setTextSize(13);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    client.key(spec[1], Arrays.copyOfRange(spec, 2, spec.length));
                }
            });
            rowLayout.addView(b, Ui.share(this, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
        }
        refreshMods();
        return scroll;
    }

    private static String[] clean(String[] in) {
        List<String> out = new ArrayList<String>();
        for (String s : in) if (s != null) out.add(s);
        return out.toArray(new String[0]);
    }

    private void addKeyRow(LinearLayout parent, String[] labels, final String[] keys, boolean[] repeat) {
        LinearLayout r = Ui.row(this);
        for (int i = 0; i < labels.length; i++) {
            final String key = keys[i];
            Button b = Ui.button(this, labels[i], Ui.SURFACE2);
            b.setTextSize(14);
            Ui.onPress(b, new Ui.Action() {
                public void run() {
                    sendKey(key);
                }
            }, repeat[i]);
            r.addView(b, Ui.share(this, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
        }
        parent.addView(r);
    }

    private void resetLiveInput() {
        ignoreWatcher = true;
        liveInput.setText(SENTINEL);
        liveInput.setSelection(liveInput.length());
        ignoreWatcher = false;
    }

    private void refreshMods() {
        for (int i = 0; i < 4; i++) {
            modButtons[i].setBackground(Ui.pressable(this, modOn[i] ? Ui.ACCENT : Ui.SURFACE2, 12));
        }
    }

    private String[] activeMods() {
        List<String> l = new ArrayList<String>();
        for (int i = 0; i < 4; i++) if (modOn[i]) l.add(MOD_KEYS[i]);
        return l.toArray(new String[0]);
    }

    private void clearMods() {
        Arrays.fill(modOn, false);
        refreshMods();
    }

    /** A named key, using any modifiers armed on screen (which then reset). */
    private void sendKey(String key) {
        String[] m = activeMods();
        if (m.length > 0) {
            client.key(key, m);
            clearMods();
        } else {
            client.key(key);
        }
    }

    /** Text from the live-typing box. A single character with a modifier armed becomes a shortcut. */
    private void typed(String s) {
        if (s.length() == 0) return;
        String[] m = activeMods();
        if (m.length > 0 && s.codePointCount(0, s.length()) == 1) {
            String key = "\n".equals(s) ? "enter" : "\t".equals(s) ? "tab" : s;
            client.key(key, m);
            clearMods();
        } else {
            client.text(s);
        }
    }

    private static String keyNameFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return "enter";
            case KeyEvent.KEYCODE_DEL:
                return "backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return "delete";
            case KeyEvent.KEYCODE_TAB:
                return "tab";
            case KeyEvent.KEYCODE_ESCAPE:
                return "esc";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "right";
            case KeyEvent.KEYCODE_DPAD_UP:
                return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "down";
            case KeyEvent.KEYCODE_MOVE_HOME:
                return "home";
            case KeyEvent.KEYCODE_MOVE_END:
                return "end";
            case KeyEvent.KEYCODE_PAGE_UP:
                return "page_up";
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return "page_down";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // Media tab
    // ------------------------------------------------------------------

    private View buildMedia() {
        LinearLayout p = Ui.column(this);
        int m = Ui.dp(this, 14);
        p.setPadding(m, m, m, m);
        p.setGravity(Gravity.CENTER_VERTICAL);
        addMediaRow(p, new String[]{"Previous", "Play / Pause", "Next"},
                new String[]{"prev", "play_pause", "next"}, new boolean[]{false, false, false});
        addMediaRow(p, new String[]{"Volume -", "Mute", "Volume +"},
                new String[]{"vol_down", "mute", "vol_up"}, new boolean[]{true, false, true});
        TextView hint = Ui.text(this, "Controls whatever is playing on the laptop. Hold the volume buttons to keep changing it.",
                13, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        p.addView(hint, Ui.stackGap(this, -1, -2, 16));
        return p;
    }

    private void addMediaRow(LinearLayout parent, String[] labels, final String[] actions, boolean[] repeat) {
        LinearLayout r = Ui.row(this);
        for (int i = 0; i < labels.length; i++) {
            final String action = actions[i];
            Button b = Ui.button(this, labels[i], Ui.SURFACE2);
            b.setTextSize(16);
            Ui.onPress(b, new Ui.Action() {
                public void run() {
                    client.media(action);
                }
            }, repeat[i]);
            r.addView(b, Ui.share(this, Ui.dp(this, 96), 5));
        }
        parent.addView(r);
    }

    // ------------------------------------------------------------------
    // Screen tab
    // ------------------------------------------------------------------

    private View buildScreen() {
        LinearLayout p = Ui.column(this);
        if (!info.screen) {
            TextView t = Ui.text(this, "Screen view is not available on this laptop.\n\n"
                    + "Install the 'mss' and 'Pillow' packages for the server (pip install -r requirements.txt) "
                    + "and restart it.", 15, Ui.MUTED);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
            p.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return p;
        }
        screenView = new ScreenView(this);
        screenView.setListener(new ScreenView.Listener() {
            public void onPointer(float x, float y) {
                client.moveTo(x, y);
            }

            public void onClick(float x, float y, String button) {
                client.moveTo(x, y);
                client.click(button, 1);
            }
        });
        p.addView(screenView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        bar.addView(Ui.text(this, "Quality", 13, Ui.MUTED));
        String[] names = {"Low", "Medium", "High"};
        for (int i = 0; i < 3; i++) {
            final int level = i;
            Button b = Ui.button(this, names[i], Ui.SURFACE2);
            b.setTextSize(13);
            b.setMinHeight(Ui.dp(this, 40));
            b.setMinimumHeight(Ui.dp(this, 40));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prefs.setScreenQuality(level);
                    refreshQuality();
                    startScreen();
                }
            });
            qualityButtons[i] = b;
            bar.addView(b, Ui.share(this, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        }
        Button fit = Ui.button(this, "Reset zoom", Ui.SURFACE2);
        fit.setTextSize(13);
        fit.setMinHeight(Ui.dp(this, 40));
        fit.setMinimumHeight(Ui.dp(this, 40));
        fit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                screenView.resetZoom();
            }
        });
        bar.addView(fit, Ui.share(this, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        p.addView(bar);
        refreshQuality();
        return p;
    }

    private void refreshQuality() {
        int sel = prefs.screenQuality();
        for (int i = 0; i < 3; i++) {
            qualityButtons[i].setBackground(Ui.pressable(this, i == sel ? Ui.ACCENT : Ui.SURFACE2, 12));
        }
    }

    private void startScreen() {
        if (!info.screen || client == null) return;
        int[] q = QUALITY[Math.max(0, Math.min(2, prefs.screenQuality()))];
        client.screen(true, q[0], q[1], q[2]);
    }

    private void stopScreen() {
        if (!info.screen || client == null) return;
        client.screen(false, 0, 0, 0);
        if (screenView != null) screenView.clear();
    }

    // ------------------------------------------------------------------
    // Power tab
    // ------------------------------------------------------------------

    private View buildPower() {
        LinearLayout p = Ui.column(this);
        int m = Ui.dp(this, 16);
        p.setPadding(m, m, m, m);
        p.setGravity(Gravity.CENTER_VERTICAL);
        if (!info.power) {
            TextView t = Ui.text(this, "Power actions are turned off on this laptop (the server was started with --no-power).",
                    15, Ui.MUTED);
            t.setGravity(Gravity.CENTER);
            p.addView(t);
            return p;
        }
        addPowerButton(p, "Lock screen", "lock", Ui.SURFACE2, null);
        addPowerButton(p, "Sleep", "sleep", Ui.SURFACE2,
                "Put the laptop to sleep? You may not be able to wake it from your phone.");
        addPowerButton(p, "Restart", "restart", Ui.SURFACE2,
                "Restart the laptop now? Unsaved work will be lost, and the connection will drop.");
        addPowerButton(p, "Shut down", "shutdown", Ui.DANGER,
                "Shut the laptop down now? Unsaved work will be lost, and you cannot turn it back on from the phone.");
        return p;
    }

    private void addPowerButton(LinearLayout parent, String label, final String action, int color, final String warning) {
        Button b = Ui.button(this, label, color);
        b.setTextSize(17);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (warning == null) {
                    client.power(action);
                } else {
                    confirm("Are you sure?", warning, "Yes, do it", new Ui.Action() {
                        public void run() {
                            client.power(action);
                        }
                    });
                }
            }
        });
        parent.addView(b, Ui.stackGap(this, -1, Ui.dp(this, 64), 10));
    }
}
