package com.mario.assistant.infrastructure.a11y;

import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import java.util.concurrent.Executor;

/** 截图适配：调用方在组合根注入 AccessibilityService.takeScreenshot。 */
public final class A11yScreenshot {
    public interface Host {
        void take(Executor executor, TakeScreenshotCallback callback);
    }

    public interface Callback {
        void onBitmap(Bitmap bitmap);
        void onFailure(int errorCode);
    }

    private final Host host;
    private final Handler handler;

    public A11yScreenshot(Host host, Handler handler) {
        this.host = host;
        this.handler = handler;
    }

    public void capture(Callback callback) {
        host.take(handler::post, new TakeScreenshotCallback() {
            @Override public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap bitmap = null;
                try {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware != null) bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                } finally {
                    buffer.close();
                }
                if (bitmap == null) callback.onFailure(-1);
                else callback.onBitmap(bitmap);
            }

            @Override public void onFailure(int errorCode) {
                callback.onFailure(errorCode);
            }
        });
    }
}
