package app.laptopremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Laptop-style touchpad. Draws the pad and turns touch events into the plain numbers that
 * {@link TouchpadGestures} (the actual gesture logic) works with.
 */
public final class TouchpadView extends View {

    public interface Listener {
        void onMove(int dx, int dy);

        /** Wheel notches; positive dy = scroll down. */
        void onScroll(int dx, int dy);

        void onClick(String button);

        void onDrag(boolean down);
    }

    private final float density;
    private final TouchpadGestures gestures;
    private Listener listener;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final Runnable longPress = new Runnable() {
        public void run() {
            gestures.onLongPress();
        }
    };

    public TouchpadView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        float slop = ViewConfiguration.get(c).getScaledTouchSlop();
        gestures = new TouchpadGestures(new TouchpadGestures.Listener() {
            public void onMove(int dx, int dy) {
                if (listener != null) listener.onMove(dx, dy);
            }

            public void onScroll(int dx, int dy) {
                if (listener != null) listener.onScroll(dx, dy);
            }

            public void onClick(String button) {
                if (listener != null) listener.onClick(button);
            }

            public void onDrag(boolean down) {
                if (listener != null) listener.onDrag(down);
            }
        }, new TouchpadGestures.Host() {
            public void scheduleLongPress(long delayMs) {
                postDelayed(longPress, delayMs);
            }

            public void cancelLongPress() {
                removeCallbacks(longPress);
            }

            public void haptic(int kind) {
                performHapticFeedback(kind == 1 ? HapticFeedbackConstants.LONG_PRESS
                        : HapticFeedbackConstants.VIRTUAL_KEY);
            }

            public void redraw() {
                invalidate();
            }
        }, density, slop);

        fill.setColor(Ui.SURFACE);
        border.setColor(Ui.SURFACE2);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * density);
        hint.setColor(Ui.MUTED);
        hint.setTextSize(14 * density);
        hint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** 0.4 (slow) .. 3.0 (fast). */
    public void setSpeed(float s) {
        gestures.setSpeed(s);
    }

    public void setNaturalScroll(boolean n) {
        gestures.setNatural(n);
    }

    /** Ends any press-and-drag in progress (call when the screen is left). */
    public void release() {
        gestures.onCancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = 18 * density;
        rect.set(2 * density, 2 * density, getWidth() - 2 * density, getHeight() - 2 * density);
        boolean dragging = gestures.isDragging();
        fill.setColor(dragging ? Ui.lighten(Ui.SURFACE, 0.12f) : Ui.SURFACE);
        canvas.drawRoundRect(rect, r, r, fill);
        canvas.drawRoundRect(rect, r, r, border);
        float cx = getWidth() / 2f;
        float y = getHeight() / 2f - 30 * density;
        String[] lines = dragging
                ? new String[]{"Dragging...", "Lift your finger to drop"}
                : new String[]{"Drag to move the pointer", "Tap = click  -  Two fingers = scroll",
                "Two-finger tap = right click", "Hold, then drag = drag & drop"};
        for (String s : lines) {
            canvas.drawText(s, cx, y, hint);
            y += 22 * density;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestures.onDown(e.getX(), e.getY(), e.getEventTime());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                gestures.onPointerDown(e.getPointerCount(), centroidX(e), centroidY(e), e.getEventTime());
                break;
            case MotionEvent.ACTION_MOVE:
                gestures.onMove(e.getPointerCount(), centroidX(e), centroidY(e), e.getEventTime());
                break;
            case MotionEvent.ACTION_UP:
                gestures.onUp(e.getEventTime());
                break;
            case MotionEvent.ACTION_CANCEL:
                gestures.onCancel();
                break;
            default:
                break;
        }
        return true;
    }

    private static float centroidX(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
        return s / e.getPointerCount();
    }

    private static float centroidY(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
        return s / e.getPointerCount();
    }
}
