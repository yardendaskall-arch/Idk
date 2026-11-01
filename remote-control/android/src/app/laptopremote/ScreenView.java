package app.laptopremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Shows the laptop screen.
 *
 *  tap ................ move the pointer there and left-click
 *  drag ............... move the pointer (no click)
 *  hold ............... right-click there
 *  pinch / two fingers  zoom and pan the picture
 */
public final class ScreenView extends View {

    public interface Listener {
        /** Position as a fraction (0..1) of the laptop screen. */
        void onPointer(float x, float y);

        void onClick(float x, float y, String button);
    }

    private static final float MAX_ZOOM = 6f;
    private static final long LONG_PRESS_MS = 550;

    private Listener listener;
    private volatile Bitmap bitmap;
    private float zoom = 1f, panX, panY;
    private final float density;
    private final float slop;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final ScaleGestureDetector scaleDetector;

    private float downX, downY, lastFx, lastFy;
    private long downTime;
    private boolean moved, multi, longPressed;

    private final Runnable longPress = new Runnable() {
        public void run() {
            if (!moved && !multi && listener != null) {
                float[] p = toLaptop(downX, downY);
                if (p != null) {
                    longPressed = true;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    listener.onClick(p[0], p[1], "right");
                }
            }
        }
    };

    public ScreenView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        hint.setColor(Ui.MUTED);
        hint.setTextSize(15 * density);
        hint.setTextAlign(Paint.Align.CENTER);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean hasFrame() {
        return bitmap != null;
    }

    public void clear() {
        bitmap = null;
        postInvalidate();
    }

    public void resetZoom() {
        zoom = 1f;
        panX = panY = 0;
        invalidate();
    }

    /** Decodes and shows a JPEG frame. Safe to call from any thread. */
    public void setFrame(byte[] jpeg) {
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (b != null) {
            bitmap = b;
            postInvalidate();
        }
    }

    // ---- geometry -------------------------------------------------------

    /** Computes where the picture is drawn into {@link #dst}; false if nothing to draw. */
    private boolean layoutImage() {
        Bitmap b = bitmap;
        if (b == null || b.getWidth() == 0 || b.getHeight() == 0 || getWidth() == 0 || getHeight() == 0) return false;
        float fit = Math.min((float) getWidth() / b.getWidth(), (float) getHeight() / b.getHeight());
        float w = b.getWidth() * fit * zoom, h = b.getHeight() * fit * zoom;
        float maxPanX = Math.max(0, (w - getWidth()) / 2f), maxPanY = Math.max(0, (h - getHeight()) / 2f);
        panX = Math.max(-maxPanX, Math.min(maxPanX, panX));
        panY = Math.max(-maxPanY, Math.min(maxPanY, panY));
        float left = (getWidth() - w) / 2f + panX, top = (getHeight() - h) / 2f + panY;
        dst.set(left, top, left + w, top + h);
        return true;
    }

    private void zoomAround(float factor, float fx, float fy) {
        if (!layoutImage()) return;
        float u = (fx - dst.left) / dst.width(), v = (fy - dst.top) / dst.height();
        float newZoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
        float ratio = newZoom / zoom;
        float newW = dst.width() * ratio, newH = dst.height() * ratio;
        zoom = newZoom;
        panX = fx - u * newW - (getWidth() - newW) / 2f;
        panY = fy - v * newH - (getHeight() - newH) / 2f;
        invalidate();
    }

    /** View coordinates -> fraction of the laptop screen, or null if outside the picture. */
    private float[] toLaptop(float x, float y) {
        if (!layoutImage()) return null;
        float u = (x - dst.left) / dst.width(), v = (y - dst.top) / dst.height();
        if (u < 0 || u > 1 || v < 0 || v > 1) return null;
        return new float[]{u, v};
    }

    // ---- drawing --------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF000000);
        Bitmap b = bitmap;
        if (b == null || !layoutImage()) {
            canvas.drawText("Waiting for the laptop screen...", getWidth() / 2f, getHeight() / 2f, hint);
            return;
        }
        canvas.drawBitmap(b, null, dst, paint);
    }

    // ---- touch ----------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = e.getEventTime();
                moved = multi = longPressed = false;
                postDelayed(longPress, LONG_PRESS_MS);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                removeCallbacks(longPress);
                lastFx = focusX(e);
                lastFy = focusY(e);
                break;

            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2) {
                    float fx = focusX(e), fy = focusY(e);
                    if (!scaleDetector.isInProgress()) {
                        panX += fx - lastFx;
                        panY += fy - lastFy;
                        invalidate();
                    }
                    lastFx = fx;
                    lastFy = fy;
                } else if (!multi) {
                    float x = e.getX(), y = e.getY();
                    if (!moved) {
                        if (Math.hypot(x - downX, y - downY) <= slop) break;
                        moved = true;
                        removeCallbacks(longPress);
                    }
                    float[] p = toLaptop(x, y);
                    if (p != null && listener != null) listener.onPointer(p[0], p[1]);
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                lastFx = focusX(e, e.getActionIndex());
                lastFy = focusY(e, e.getActionIndex());
                break;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (!multi && !moved && !longPressed && listener != null) {
                    float[] p = toLaptop(e.getX(), e.getY());
                    if (p != null) {
                        listener.onClick(p[0], p[1], "left");
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                break;

            default:
                break;
        }
        return true;
    }

    private static float focusX(MotionEvent e) {
        return focusX(e, -1);
    }

    private static float focusY(MotionEvent e) {
        return focusY(e, -1);
    }

    /** Average of all pointers except {@code skip} (the one being lifted). */
    private static float focusX(MotionEvent e, int skip) {
        float s = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            s += e.getX(i);
            n++;
        }
        return n == 0 ? 0 : s / n;
    }

    private static float focusY(MotionEvent e, int skip) {
        float s = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            s += e.getY(i);
            n++;
        }
        return n == 0 ? 0 : s / n;
    }
}
