package com.unoq.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Draw on the UNO Q's 13 x 8 LED matrix: tap or drag to paint, tap a lit LED to erase. */
final class MatrixView extends View {
    static final int W = 13, H = 8;

    interface Listener { void changed(String frame); }

    private final int[] cells = new int[W * H];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int brush = 7;
    private int paintValue = -1;
    private Listener listener;

    MatrixView(Context c) { super(c); }

    void setListener(Listener l) { listener = l; }
    void setBrush(int b) { brush = Math.max(1, Math.min(7, b)); }

    String frame() {
        StringBuilder sb = new StringBuilder(W * H);
        for (int v : cells) sb.append((char) ('0' + v));
        return sb.toString();
    }

    /** Load a frame of '0'-'7' digits, or '#'/'.' art ('#' = current brightness). */
    void load(String f, boolean notify) {
        for (int i = 0; i < cells.length && i < f.length(); i++) {
            char ch = f.charAt(i);
            cells[i] = ch == '#' ? brush : (ch >= '0' && ch <= '7' ? ch - '0' : 0);
        }
        invalidate();
        if (notify && listener != null) listener.changed(frame());
    }

    void fill(int v) {
        for (int i = 0; i < cells.length; i++) cells[i] = v;
        invalidate();
        if (listener != null) listener.changed(frame());
    }

    void invert() {
        for (int i = 0; i < cells.length; i++) cells[i] = cells[i] == 0 ? brush : 0;
        invalidate();
        if (listener != null) listener.changed(frame());
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, w * H / W);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cell = getWidth() / (float) W;
        float r = cell * 0.36f;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = cells[y * W + x];
                if (v == 0) {
                    paint.setColor(0xFF2A323C);
                } else {
                    int alpha = 80 + v * 25;   // brighter LEDs look more solid
                    paint.setColor((alpha << 24) | 0x4FC3F7);
                }
                c.drawCircle(x * cell + cell / 2, y * cell + cell / 2, r, paint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float cell = getWidth() / (float) W;
        int x = (int) (e.getX() / cell), y = (int) (e.getY() / cell);
        boolean inside = x >= 0 && x < W && y >= 0 && y < H;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);  // don't scroll the page while drawing
                if (!inside) return true;
                paintValue = cells[y * W + x] == 0 ? brush : 0;
                set(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (inside && paintValue >= 0) set(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                paintValue = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void set(int x, int y) {
        int i = y * W + x;
        if (cells[i] == paintValue) return;
        cells[i] = paintValue;
        invalidate();
        if (listener != null) listener.changed(frame());
    }
}
