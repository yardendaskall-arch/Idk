package com.pennymogul.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Thin native shell: one full-screen WebView that loads the game from assets.
 * The game itself lives in assets/www (see /web in the repo).
 */
public class MainActivity extends Activity {
    private static final String HOME = "file:///android_asset/www/index.html";
    private static final int BG = 0xFF0B0F1A;

    private WebView web;
    private Vibrator vibrator;

    /** Methods the page can call as window.PMNative.* */
    private final class Bridge {
        @JavascriptInterface
        public void vibrate(int ms) {
            if (vibrator != null && ms > 0 && ms < 500) {
                vibrator.vibrate((long) ms);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setLongClickable(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true; // no text-selection / context menu
            }
        });
        web.setHapticFeedbackEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // localStorage = the save file
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100); // keep the layout stable when system font scale changes
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "PMNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // The game is fully offline; never navigate away from it.
                return url == null || !url.startsWith("file:///android_asset/");
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // All game state lives in localStorage, so a cold load is always safe.
        web.loadUrl(HOME);
    }

    @Override
    protected void onPause() {
        // Let the game flush its save before we freeze the page.
        web.evaluateJavascript("window.__pmPause && window.__pmPause()", null);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.evaluateJavascript("window.__pmResume && window.__pmResume()", null);
    }

    @Override
    public void onBackPressed() {
        // Give the game first dibs (close sheet / go back a screen).
        web.evaluateJavascript("window.__pmBack ? window.__pmBack() : false",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String handled) {
                        if (!"true".equals(handled)) {
                            moveTaskToBack(true);
                        }
                    }
                });
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
