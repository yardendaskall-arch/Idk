package app.laptopremote;

import java.util.ArrayList;
import java.util.List;

/** Unit tests for the touchpad gesture logic (plain JVM, no Android). Run via tests/test_gestures.py. */
public class GestureTest {

    static int failures = 0;

    static void check(boolean cond, String what) {
        if (cond) {
            System.out.println("ok    " + what);
        } else {
            failures++;
            System.out.println("FAIL  " + what);
        }
    }

    /** Records everything the engine does. */
    static final class Rig implements TouchpadGestures.Listener, TouchpadGestures.Host {
        final TouchpadGestures g;
        final List<String> events = new ArrayList<String>();
        int moveX, moveY, scrollX, scrollY, moves, scrolls;
        boolean longPressPending;
        int cancels;
        long t = 1000;

        Rig(float density) {
            g = new TouchpadGestures(this, this, density, 8f * density);
        }

        public void onMove(int dx, int dy) {
            moveX += dx;
            moveY += dy;
            moves++;
        }

        public void onScroll(int dx, int dy) {
            scrollX += dx;
            scrollY += dy;
            scrolls++;
        }

        public void onClick(String b) {
            events.add("click " + b);
        }

        public void onDrag(boolean down) {
            events.add(down ? "drag-start" : "drag-end");
        }

        public void scheduleLongPress(long ms) {
            longPressPending = true;
        }

        public void cancelLongPress() {
            if (longPressPending) cancels++;
            longPressPending = false;
        }

        public void haptic(int kind) {
        }

        public void redraw() {
        }

        /** Fires the long-press timer if it is still scheduled, like the real timer would. */
        void timerFires() {
            if (longPressPending) {
                longPressPending = false;
                g.onLongPress();
            }
        }

        void reset() {
            events.clear();
            moveX = moveY = scrollX = scrollY = moves = scrolls = 0;
        }
    }

    public static void main(String[] args) {
        Rig r;

        // ---- tap
        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.g.onUp(r.t + 120);
        check(r.events.equals(java.util.Arrays.asList("click left")) && r.moves == 0, "quick tap = left click, no movement");

        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.g.onUp(r.t + 400);
        check(r.events.isEmpty(), "slow press-and-release is not a click");

        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.g.onMove(1, 103, 102, r.t + 20);          // finger jitter below the slop
        r.g.onUp(r.t + 100);
        check(r.events.equals(java.util.Arrays.asList("click left")) && r.moves == 0, "jitter under the slop still counts as a tap");

        // ---- drag moves, never clicks, and direction is right
        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        for (int i = 1; i <= 10; i++) r.g.onMove(1, 100 + i * 10, 100 - i * 4, r.t + i * 16);
        r.g.onUp(r.t + 200);
        check(r.events.isEmpty(), "a drag is not a click");
        check(r.moveX > 0 && r.moveY < 0, "drag right+up moves right+up (" + r.moveX + "," + r.moveY + ")");
        check(r.cancels >= 1, "long-press timer cancelled once the finger moves");

        // ---- acceleration: same distance, faster swipe -> farther
        Rig slow = new Rig(2f), fast = new Rig(2f);
        slow.g.onDown(0, 0, 0);
        for (int i = 1; i <= 20; i++) slow.g.onMove(1, i * 5, 0, i * 40);      // 100px in 800ms
        fast.g.onDown(0, 0, 0);
        for (int i = 1; i <= 5; i++) fast.g.onMove(1, i * 20, 0, i * 8);       // 100px in 40ms
        check(fast.moveX > slow.moveX * 1.5, "fast swipe travels farther than slow (" + fast.moveX + " vs " + slow.moveX + ")");

        // ---- speed setting
        Rig s1 = new Rig(2f), s2 = new Rig(2f);
        s2.g.setSpeed(2f);
        for (Rig q : new Rig[]{s1, s2}) {
            q.g.onDown(0, 0, 0);
            for (int i = 1; i <= 10; i++) q.g.onMove(1, i * 10, 0, i * 30);
        }
        check(Math.abs(s2.moveX - 2.0 * s1.moveX) <= 3, "speed 2x doubles the distance (" + s1.moveX + " -> " + s2.moveX + ")");

        // ---- tiny movements are not lost (fractional remainder is carried)
        r = new Rig(3f);                                      // high density: 1px is a small step
        r.g.onDown(0, 0, 0);
        float x = 0;
        for (int i = 1; i <= 400; i++) {
            x += 0.5f;                                        // very slow: 0.5px per 30 ms
            r.g.onMove(1, x, 0, i * 30L);
        }
        float expected = (x - 8f * 3f) * (TouchpadGestures.BASE_GAIN_DP / 3f) * 1.0f;   // base gain, no acceleration to speak of
        check(r.moveX > 0 && Math.abs(r.moveX - expected * 1.01f) < expected * 0.15f,
                "slow sub-pixel movement is accumulated (" + r.moveX + " ~ " + (int) expected + ")");

        // ---- hold, then drag
        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.timerFires();
        check(r.g.isDragging() && r.events.equals(java.util.Arrays.asList("drag-start")), "holding still starts a drag");
        r.g.onMove(1, 160, 140, r.t + 600);
        r.g.onMove(1, 220, 180, r.t + 620);
        check(r.moveX > 0 && r.moveY > 0, "moving while dragging moves the pointer");
        r.g.onUp(r.t + 700);
        check(r.events.equals(java.util.Arrays.asList("drag-start", "drag-end")) && !r.g.isDragging(), "release drops: no click");

        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.g.onMove(1, 140, 100, r.t + 50);                     // moved before the timer: not a drag
        r.timerFires();
        check(!r.g.isDragging() && r.events.isEmpty(), "long-press does nothing once the finger has moved");

        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.g.onPointerDown(2, 130, 100, r.t + 100);
        r.timerFires();
        check(!r.g.isDragging(), "no drag when a second finger is down");

        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.timerFires();
        r.g.onCancel();
        check(r.events.equals(java.util.Arrays.asList("drag-start", "drag-end")), "cancel during a drag releases the button exactly once");
        r.g.onCancel();
        check(r.events.size() == 2, "a second cancel does nothing");

        // ---- extra finger lands during a drag: no jump, still dragging
        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.timerFires();
        r.g.onMove(1, 110, 100, r.t + 500);
        r.reset();
        r.g.onPointerDown(2, 160, 100, r.t + 520);             // centroid jumps by 50px
        r.g.onMove(2, 162, 100, r.t + 540);
        check(Math.abs(r.moveX) < 40 && r.g.isDragging(), "second finger during a drag does not make the pointer jump (" + r.moveX + ")");

        // ---- two-finger tap = right click; three fingers = middle
        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.g.onPointerDown(2, 130, 100, r.t + 20);
        r.g.onUp(r.t + 100);
        check(r.events.equals(java.util.Arrays.asList("click right")), "two-finger tap = right click");
        check(r.cancels >= 1, "second finger cancels the long-press timer");

        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.g.onPointerDown(2, 130, 100, r.t + 20);
        r.g.onPointerDown(3, 140, 100, r.t + 40);
        r.g.onUp(r.t + 120);
        check(r.events.equals(java.util.Arrays.asList("click middle")), "three-finger tap = middle click");

        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.g.onPointerDown(2, 130, 100, r.t + 20);
        r.g.onUp(r.t + 500);
        check(r.events.isEmpty(), "slow two-finger press is not a click");

        // ---- two-finger scroll (density 2 -> one notch per 18px)
        r = new Rig(2f);
        r.g.onDown(100, 300, r.t);
        r.g.onPointerDown(2, 130, 300, r.t + 10);
        for (int i = 1; i <= 20; i++) r.g.onMove(2, 130, 300 - i * 9, r.t + 10 + i * 16);   // fingers move up 180px
        r.g.onUp(r.t + 400);
        check(r.moves == 0, "two-finger drag never moves the pointer");
        check(r.scrollY > 0 && r.scrollX == 0, "fingers up scroll down with natural scrolling (" + r.scrollY + " notches)");
        check(r.scrollY >= 7 && r.scrollY <= 10, "about one notch per 18px of travel after the slop (" + r.scrollY + ")");
        check(r.events.isEmpty(), "scrolling is not a click");

        r = new Rig(2f);
        r.g.setNatural(false);
        r.g.onDown(100, 300, r.t);
        r.g.onPointerDown(2, 130, 300, r.t + 10);
        for (int i = 1; i <= 20; i++) r.g.onMove(2, 130, 300 - i * 9, r.t + 10 + i * 16);
        check(r.scrollY < 0, "classic scrolling reverses the direction (" + r.scrollY + ")");

        r = new Rig(2f);
        r.g.onDown(100, 300, r.t);
        r.g.onPointerDown(2, 130, 300, r.t + 10);
        for (int i = 1; i <= 20; i++) r.g.onMove(2, 130 + i * 9, 300 + (i % 2), r.t + 10 + i * 16);   // slightly wobbly sideways swipe
        check(r.scrollX < 0 && r.scrollY == 0, "sideways swipe scrolls horizontally only (" + r.scrollX + "," + r.scrollY + ")");

        // ---- lifting one finger after scrolling: no pointer jump, no click
        r = new Rig(2f);
        r.g.onDown(100, 300, r.t);
        r.g.onPointerDown(2, 130, 300, r.t + 10);
        for (int i = 1; i <= 10; i++) r.g.onMove(2, 130, 300 - i * 12, r.t + 10 + i * 16);
        r.reset();
        r.g.onMove(1, 100, 180, r.t + 300);                    // the other finger is now alone
        r.g.onMove(1, 100, 150, r.t + 320);
        r.g.onMove(1, 140, 100, r.t + 340);
        check(r.moves == 0 && r.scrolls == 0, "leftover finger after a scroll is ignored");
        r.g.onUp(r.t + 400);
        check(r.events.isEmpty(), "ending a scroll does not click");

        // ---- state is clean for the next gesture
        r = new Rig(2f);
        r.g.onDown(100, 100, r.t);
        r.timerFires();
        r.g.onUp(r.t + 600);
        r.reset();
        r.g.onDown(50, 50, r.t + 1000);
        r.g.onUp(r.t + 1100);
        check(r.events.equals(java.util.Arrays.asList("click left")), "a tap right after a drag-and-drop works");

        // ---- scroll step scales with density
        Rig d1 = new Rig(1f), d3 = new Rig(3f);
        for (Rig q : new Rig[]{d1, d3}) {
            q.g.onDown(0, 300, 0);
            q.g.onPointerDown(2, 30, 300, 5);
        }
        for (int i = 1; i <= 30; i++) {
            d1.g.onMove(2, 30, 300 - i * 6, 5 + i * 16);       // 180px at density 1 = 180dp
            d3.g.onMove(2, 30, 300 - i * 6, 5 + i * 16);       // 180px at density 3 = 60dp
        }
        check(d1.scrollY > d3.scrollY * 2, "scroll distance is measured in dp, not pixels (" + d1.scrollY + " vs " + d3.scrollY + ")");

        System.out.println(failures == 0 ? "ALL GESTURE CHECKS PASSED" : failures + " GESTURE CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
