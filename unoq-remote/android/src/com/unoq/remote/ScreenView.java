package com.unoq.remote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shows the board's desktop and turns touches into mouse actions:
 * tap = click, long press = right click, drag = click and drag, two-finger drag = scroll.
 */
final class ScreenView extends View {

    interface Input {
        void move(int x, int y);
        void button(String type, String button, int x, int y);   // type: click / down / up
        void scroll(int dy, int x, int y);
    }

    private Bitmap bmp;
    private int screenW, screenH;
    private String message = "Connecting…";
    private Input input;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float downX, downY, lastScrollY;
    private boolean dragging, longPressed, scrolling;
    private final float slop;

    ScreenView(Context c) {
        super(c);
        slop = 12 * getResources().getDisplayMetrics().density;
        text.setColor(0xFFE8EDF2);
        text.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
        text.setTextAlign(Paint.Align.CENTER);
        setBackgroundColor(0xFF000000);
    }

    void setInput(Input i) { input = i; }

    void showFrame(Bitmap b, int sw, int sh) {
        Bitmap old = bmp;
        bmp = b;
        screenW = sw > 0 ? sw : b.getWidth();
        screenH = sh > 0 ? sh : b.getHeight();
        message = null;
        invalidate();
        if (old != null && old != b) old.recycle();
    }

    void showMessage(String m) {
        message = m;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp != null && !bmp.isRecycled()) {
            float s = Math.min(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
            float w = bmp.getWidth() * s, h = bmp.getHeight() * s;
            dst.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            c.drawBitmap(bmp, null, dst, paint);
        }
        if (message != null) {
            float y = getHeight() / 2f;
            for (String line : wrap(message, getWidth() - 40)) {
                c.drawText(line, getWidth() / 2f, y, text);
                y += text.getTextSize() * 1.4f;
            }
        }
    }

    private java.util.List<String> wrap(String s, float max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() > 0 && text.measureText(line + " " + word) > max) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        out.add(line.toString());
        return out;
    }

    private int sx(float x) { return Math.round((x - dst.left) / dst.width() * screenW); }
    private int sy(float y) { return Math.round((y - dst.top) / dst.height() * screenH); }

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (dragging || scrolling || input == null) return;
            longPressed = true;
            performHapticFeedback(0);
            input.button("click", "right", sx(downX), sy(downY));
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (input == null || bmp == null || dst.width() <= 0) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = e.getX();
                downY = e.getY();
                dragging = longPressed = scrolling = false;
                input.move(sx(downX), sy(downY));
                handler.postDelayed(longPress, 550);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                handler.removeCallbacks(longPress);
                if (dragging) { input.button("up", "left", sx(e.getX(0)), sy(e.getY(0))); dragging = false; }
                scrolling = true;
                lastScrollY = avgY(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scrolling) {
                    float y = avgY(e);
                    int steps = (int) ((lastScrollY - y) / (30 * getResources().getDisplayMetrics().density));
                    if (steps != 0) {
                        lastScrollY -= steps * 30 * getResources().getDisplayMetrics().density;
                        input.scroll(steps, sx(downX), sy(downY));
                    }
                    return true;
                }
                if (longPressed) return true;
                if (!dragging && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
                    handler.removeCallbacks(longPress);
                    dragging = true;
                    input.button("down", "left", sx(downX), sy(downY));
                }
                if (dragging) input.move(sx(e.getX()), sy(e.getY()));
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (dragging) input.button("up", "left", sx(e.getX()), sy(e.getY()));
                else if (!longPressed && !scrolling) input.button("click", "left", sx(downX), sy(downY));
                dragging = scrolling = longPressed = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (dragging) input.button("up", "left", sx(downX), sy(downY));
                dragging = scrolling = longPressed = false;
                return true;
        }
        return true;
    }

    private static float avgY(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
        return s / e.getPointerCount();
    }
}
