package app.laptopremote;

/**
 * The touchpad's gesture logic, free of Android classes so it can be unit-tested on a plain JVM.
 * {@link TouchpadView} feeds it pointer counts, the centroid of the touching fingers and event times.
 *
 *  one finger drag ........ move the pointer (with speed-based acceleration)
 *  tap .................... left click
 *  hold, then drag ........ press-and-drag (drag & drop, select text)
 *  two finger drag ........ scroll
 *  two finger tap ......... right click
 *  three finger tap ....... middle click
 */
final class TouchpadGestures {

    interface Listener {
        void onMove(int dx, int dy);

        /** Wheel notches; positive dy = scroll down. */
        void onScroll(int dx, int dy);

        void onClick(String button);

        void onDrag(boolean down);
    }

    /** Things only the view can do. */
    interface Host {
        void scheduleLongPress(long delayMs);

        void cancelLongPress();

        /** 0 = a click happened, 1 = a drag started. */
        void haptic(int kind);

        void redraw();
    }

    static final float BASE_GAIN_DP = 4.0f;       // screen pixels per dp of finger travel
    static final float SCROLL_STEP_DP = 9f;       // finger travel per wheel notch
    static final long TAP_MAX_MS = 280;
    static final long LONG_PRESS_MS = 380;

    private final Listener listener;
    private final Host host;
    private final float density;
    private final float slop;
    private float speed = 1f;
    private boolean natural = true;

    private float downX, downY, lastX, lastY, startX, startY;
    private long downTime, lastTime;
    private boolean moved, dragging, scrolling, multiIgnore;
    private int maxPointers, lastCount;
    private float resX, resY, accX, accY;

    TouchpadGestures(Listener listener, Host host, float density, float slopPx) {
        this.listener = listener;
        this.host = host;
        this.density = density;
        this.slop = slopPx;
    }

    /** 0.2 (slow) .. 4.0 (fast). */
    void setSpeed(float s) {
        speed = Math.max(0.2f, Math.min(4f, s));
    }

    void setNatural(boolean n) {
        natural = n;
    }

    boolean isDragging() {
        return dragging;
    }

    // ------------------------------------------------------------------ input

    void onDown(float x, float y, long t) {
        downX = lastX = x;
        downY = lastY = y;
        downTime = lastTime = t;
        moved = false;
        dragging = false;
        scrolling = false;
        multiIgnore = false;
        maxPointers = 1;
        lastCount = 1;
        resX = resY = accX = accY = 0;
        host.scheduleLongPress(LONG_PRESS_MS);
    }

    /** A further finger touched down; {@code count} includes it. */
    void onPointerDown(int count, float cx, float cy, long t) {
        maxPointers = Math.max(maxPointers, count);
        host.cancelLongPress();
        lastCount = count;
        lastX = cx;
        lastY = cy;
        lastTime = t;
        if (dragging) return;                     // extra fingers during a drag are ignored
        if (count == 2) {
            scrolling = true;
            startX = cx;
            startY = cy;
            accX = accY = 0;
        } else {
            scrolling = false;
            multiIgnore = true;
        }
    }

    /** {@code count} fingers are down, centred on (cx, cy). */
    void onMove(int count, float cx, float cy, long t) {
        if (count != lastCount) {                 // a finger was lifted: do not let the centroid jump
            lastCount = count;
            lastX = cx;
            lastY = cy;
            lastTime = t;
            return;
        }
        if (dragging) {
            moveBy(cx, cy, t);
            return;
        }
        if (multiIgnore || count >= 3) return;

        if (scrolling) {
            if (count < 2) return;                // one finger left over after a scroll: ignore
            float ddx = cx - lastX, ddy = cy - lastY;
            lastX = cx;
            lastY = cy;
            if (!moved) {
                if (Math.hypot(cx - startX, cy - startY) <= slop * 1.5f) return;
                moved = true;
                host.cancelLongPress();
                return;
            }
            accX += natural ? -ddx : ddx;
            accY += natural ? -ddy : ddy;
            if (Math.abs(accY) >= Math.abs(accX)) accX = 0; else accY = 0;   // lock to one axis
            float step = SCROLL_STEP_DP * density;
            int tx = (int) (accX / step);
            int ty = (int) (accY / step);
            accX -= tx * step;
            accY -= ty * step;
            if (tx != 0 || ty != 0) listener.onScroll(tx, ty);
            return;
        }

        if (count == 1) {
            if (!moved) {
                if (Math.hypot(cx - downX, cy - downY) <= slop) return;
                moved = true;
                host.cancelLongPress();
            }
            moveBy(cx, cy, t);
        }
    }

    void onUp(long t) {
        host.cancelLongPress();
        if (dragging) {
            dragging = false;
            listener.onDrag(false);
            host.redraw();
        } else if (!moved && t - downTime <= TAP_MAX_MS) {
            if (maxPointers == 1) {
                listener.onClick("left");
                host.haptic(0);
            } else if (maxPointers == 2) {
                listener.onClick("right");
            } else {
                listener.onClick("middle");
            }
        }
    }

    /** Touch sequence aborted (or the view went away): drop anything held down. */
    void onCancel() {
        host.cancelLongPress();
        if (dragging) {
            dragging = false;
            listener.onDrag(false);
            host.redraw();
        }
    }

    /** The long-press timer ran out. */
    void onLongPress() {
        if (!moved && maxPointers == 1 && !scrolling) {
            dragging = true;
            listener.onDrag(true);
            host.haptic(1);
            host.redraw();
        }
    }

    // ------------------------------------------------------------------ pointer movement

    private void moveBy(float x, float y, long t) {
        float dx = x - lastX, dy = y - lastY;
        float dt = Math.max(1f, t - lastTime);
        lastX = x;
        lastY = y;
        lastTime = t;
        float speedDpPerMs = (float) Math.hypot(dx, dy) / density / dt;
        float gain = (BASE_GAIN_DP / density) * speed * (1f + Math.min(speedDpPerMs * 1.1f, 2.2f));
        float mx = dx * gain + resX, my = dy * gain + resY;
        int ix = (int) mx, iy = (int) my;
        resX = mx - ix;           // keep the fraction so slow, small movements are not lost
        resY = my - iy;
        if (ix != 0 || iy != 0) listener.onMove(ix, iy);
    }
}
