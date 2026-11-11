package com.mario.assistant;

import com.mario.assistant.infrastructure.ui.Diag;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

/**
 * 仅限 adb（shell uid=2000）触发的调试手势桥：tap / swipe / back / arm / recover。
 * 本机调试通道；广播回调中校验调用方 uid，普通应用无法触发。
 * 用法：adb shell am broadcast -a com.mario.assistant.GESTURE
 *   --es type tap --ei x 600 --ei y 1200
 *   --es type swipe --ei x1 600 --ei y1 1800 --ei x2 600 --ei y2 900 --ei dur 350
 *   --es type back
 *   --es type arm | recover | collect
 */
public final class GestureBridge extends BroadcastReceiver {
    private static final String TAG = "MarioGesture";
    private static HandlerThread thread;
    private static Handler handler;

    @Override public void onReceive(Context context, Intent intent) {
        Diag.init(context);
        Diag.i(TAG, "onReceive action=" + (intent == null ? null : intent.getAction())
                + " uid=" + Binder.getCallingUid());
        if (intent == null || !"com.mario.assistant.GESTURE".equals(intent.getAction())) return;
        if (Binder.getCallingUid() != Process.SHELL_UID) return; // 仅 adb shell
        TaskService service = TaskService.connected();
        Diag.i(TAG, "service connected=" + (service != null));
        String type = intent.getStringExtra("type");
        if (service == null || type == null) {
            Diag.i(TAG, "bridge unavailable: service=" + (service != null) + " type=" + type);
            return;
        }
        synchronized (GestureBridge.class) {
            if (handler == null) {
                thread = new HandlerThread("mario-bridge");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
        }
        final Intent cmd = intent;
        handler.post(() -> dispatch(service, cmd));
    }

    private void dispatch(TaskService service, Intent cmd) {
        exec(service, cmd);
    }

    /** 广播不可用（HyperOS 后台广播管控）时经 MainActivity 的 am start 通道执行。 */
    static void queueGesture(Intent cmd, long delayMs) {
        synchronized (GestureBridge.class) {
            if (handler == null) {
                thread = new HandlerThread("mario-bridge");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
        }
        handler.postDelayed(() -> {
            TaskService service = TaskService.connected();
            Diag.i(TAG, "queued gesture service=" + (service != null));
            if (service != null) exec(service, cmd);
        }, delayMs);
    }

    private static void exec(TaskService service, Intent cmd) {
        String type = cmd.getStringExtra("type");
        try {
            if ("arm".equals(type)) {
                service.arm();
                Diag.i(TAG, "arm");
                return;
            }
            if ("recover".equals(type)) {
                boolean ok = service.debugForestRecover();
                Diag.i(TAG, "recover ok=" + ok);
                return;
            }
            if ("collect".equals(type)) {
                boolean ok = service.debugForestCollect();
                Diag.i(TAG, "collect ok=" + ok);
                return;
            }
            if ("back".equals(type)) {
                boolean ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                Diag.i(TAG, "back ok=" + ok);
                return;
            }
            Path path = new Path();
            long dur = 60;
            if ("tap".equals(type)) {
                float x = cmd.getIntExtra("x", 0);
                float y = cmd.getIntExtra("y", 0);
                path.moveTo(x, y);
            } else if ("swipe".equals(type)) {
                float x1 = cmd.getIntExtra("x1", 0);
                float y1 = cmd.getIntExtra("y1", 0);
                float x2 = cmd.getIntExtra("x2", 0);
                float y2 = cmd.getIntExtra("y2", 0);
                dur = cmd.getIntExtra("dur", 300);
                path.moveTo(x1, y1);
                path.lineTo(x2, y2);
            } else {
                Diag.i(TAG, "unknown type=" + type);
                return;
            }
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, dur)).build();
            boolean accepted = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    Diag.i(TAG, "gesture completed");
                }
                @Override public void onCancelled(GestureDescription g) {
                    Diag.i(TAG, "gesture cancelled");
                }
            }, handler);
            Diag.i(TAG, type + " accepted=" + accepted);
        } catch (RuntimeException e) {
            Diag.i(TAG, type + " failed: " + e);
        }
    }
}
