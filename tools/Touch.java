import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.lang.reflect.Method;

public class Touch {
    public static void main(String[] a) throws Exception {
        long ms = Long.parseLong(a[0]), hold = Long.parseLong(a[1]);
        int n = (a.length - 2) / 4;
        float[][] p = new float[n][4];
        for (int i = 0; i < n; i++) for (int j = 0; j < 4; j++) p[i][j] = Float.parseFloat(a[2 + i * 4 + j]);
        Class<?> im = Class.forName("android.hardware.input.InputManagerGlobal");
        Object mgr;
        Method inject;
        try {
            mgr = im.getMethod("getInstance").invoke(null);
            inject = im.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
        } catch (Throwable t) {
            im = Class.forName("android.hardware.input.InputManager");
            mgr = im.getMethod("getInstance").invoke(null);
            inject = im.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
        }
        long down = SystemClock.uptimeMillis();
        send(mgr, inject, down, MotionEvent.ACTION_DOWN, p, 1, 0);
        if (n > 1) send(mgr, inject, down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), p, 2, 0);
        long steps = Math.max(1, ms / 8);
        for (long s = 1; s <= steps; s++) {
            Thread.sleep(8);
            send(mgr, inject, down, MotionEvent.ACTION_MOVE, p, n, (float) s / steps);
        }
        Thread.sleep(hold);
        if (n > 1) send(mgr, inject, down, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), p, 2, 1);
        send(mgr, inject, down, MotionEvent.ACTION_UP, p, 1, 1);
    }

    static void send(Object mgr, Method inject, long down, int action, float[][] p, int count, float f) throws Exception {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = p[i][0] + (p[i][2] - p[i][0]) * f;
            coords[i].y = p[i][1] + (p[i][3] - p[i][1]) * f;
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        inject.invoke(mgr, e, 2 );
        e.recycle();
    }
}
