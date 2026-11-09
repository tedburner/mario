package com.mario.assistant.infrastructure.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import com.mario.assistant.domain.model.Bounds;

/** 手势派发：始终在后台线程调用 dispatchGesture，回调回到扫描线程。 */
public final class Gestures {
    public interface Host {
        boolean dispatch(GestureDescription gesture,
                         AccessibilityService.GestureResultCallback callback, Handler handler);
    }

    public interface Callback {
        void onCompleted();
        void onCancelled();
        void onRejected();
    }

    private final Host host;
    private final Handler handler;

    public Gestures(Host host, Handler handler) {
        this.host = host;
        this.handler = handler;
    }

    public void tap(Bounds bounds, long durationMs, Callback callback) {
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        dispatch(path, durationMs, callback);
    }

    public void swipe(int x1, int y1, int x2, int y2, long durationMs, Callback callback) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        dispatch(path, durationMs, callback);
    }

    private void dispatch(Path path, long durationMs, Callback callback) {
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build();
        Thread thread = new Thread(() -> {
            boolean accepted;
            try {
                accepted = host.dispatch(gesture, new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        handler.post(callback::onCompleted);
                    }
                    @Override public void onCancelled(GestureDescription g) {
                        handler.post(callback::onCancelled);
                    }
                }, handler);
            } catch (RuntimeException e) {
                accepted = false;
            }
            boolean result = accepted;
            handler.post(() -> {
                if (!result) callback.onRejected();
            });
        }, "mario-gesture");
        thread.setDaemon(true);
        thread.start();
    }
}
