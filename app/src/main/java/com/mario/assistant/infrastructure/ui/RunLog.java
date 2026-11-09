package com.mario.assistant.infrastructure.ui;

import android.content.Context;
import android.util.Log;
import java.util.function.BooleanSupplier;

/** 运行结果：logcat + 主界面持久化 + 前台通知实时刷新。 */
public final class RunLog {
    private final Context context;
    private final StatusNotifier notifier;
    private final BooleanSupplier foregroundActive;

    public RunLog(Context context, StatusNotifier notifier, BooleanSupplier foregroundActive) {
        this.context = context;
        this.notifier = notifier;
        this.foregroundActive = foregroundActive;
    }

    public void save(String result) {
        Log.i("MarioAdDiag", "save: " + result);
        context.getSharedPreferences("mario", Context.MODE_PRIVATE).edit()
                .putString("last_result", result).apply();
        if (foregroundActive.getAsBoolean()) notifier.update(result);
    }
}
