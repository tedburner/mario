package com.mario.assistant.infrastructure.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 常驻诊断日志：同时写 logcat 与外部私有目录，USB 连接后可直接 adb pull 复盘。
 *
 * <p>落盘位置 {@code /sdcard/Android/data/<包名>/files/logs/mario.log}，单文件 2MB，
 * 滚动保留 8 份（mario.log + mario.1.log … mario.7.log，约 16MB）。行格式
 * {@code MM-dd HH:mm:ss.SSS I/TAG: msg}，每次进程启动写一行 session header（版本/机型/
 * 系统/分辨率），未捕获异常在进程退出前写入 FATAL 栈。</p>
 */
public final class Diag {
    private static final String TAG = "MarioAdDiag";
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    /** 含当前文件在内的保留份数。 */
    private static final int KEEP_FILES = 8;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File file;
    private static boolean headerWritten;
    private static boolean handlerInstalled;
    private static boolean initFailed;
    /** 连续重复折叠：同 level+tag 同文本只落首条，跳出的第一条前补记重复数（复盘时 OCR 全屏
     * dump 曾同一画面连打 22 帧、scan 心跳每 tick 一条，价值密度极低）。审计语义不丢：条数保留。 */
    private static String foldedKey;
    private static String foldedMsg;
    private static int foldedCount;

    private Diag() {}

    /** 幂等初始化；私有外部目录不可用时退回内部 files 目录，保证仍能取证。 */
    public static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (file != null || initFailed) return;
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            File dir = app.getExternalFilesDir("logs");
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                // 外部私有目录不可用时退回内部目录（debug 包可用 run-as 取出）。
                File fallback = new File(app.getFilesDir(), "logs");
                dir = fallback.exists() || fallback.mkdirs() ? fallback : null;
            }
            if (dir == null) {
                initFailed = true;
                Log.i(TAG, "diag unavailable, logcat only");
                return;
            }
            file = new File(dir, "mario.log");
            rotateIfNeeded(dir);
            if (!headerWritten) {
                headerWritten = true;
                appendLocked("I", TAG, header(app));
            }
            installCrashHandler();
        }
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        append("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        append("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        append("E", tag, msg);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        append("D", tag, msg);
    }

    private static void append(String level, String tag, String msg) {
        if (file == null) return;
        synchronized (LOCK) {
            if (file == null) return;
            if (file.length() >= MAX_BYTES) {
                rotateIfNeeded(file.getParentFile());
            }
            String key = level + "/" + tag;
            if (key.equals(foldedKey) && msg != null && msg.equals(foldedMsg)) {
                foldedCount++;
                // 心跳：长时间卡在同一画面时保留时长感知（否则结尾的重复段不会落盘）。
                if (foldedCount % 20 == 0) {
                    appendLocked(level, tag, "┈ 与上一条相同（已连续 " + foldedCount + " 条，略）");
                }
                return;
            }
            flushFoldedLocked();
            foldedKey = key;
            foldedMsg = msg;
            foldedCount = 1;
            appendLocked(level, tag, msg);
        }
    }

    /** 折叠系列结束（来了不同消息/滚动文件）：补一条重复计数，让「省略了多少条」可审计。 */
    private static void flushFoldedLocked() {
        if (foldedCount > 1 && foldedKey != null) {
            int slash = foldedKey.indexOf('/');
            appendLocked(foldedKey.substring(0, slash), foldedKey.substring(slash + 1),
                    "┈ 上一条重复 " + (foldedCount - 1) + " 次，略");
        }
        foldedKey = null;
        foldedMsg = null;
        foldedCount = 0;
    }

    private static void appendLocked(String level, String tag, String msg) {
        String tagName = tag == null ? TAG : tag;
        String text = msg == null ? "null" : msg;
        String line = STAMP.format(new Date()) + " " + level + "/" + tagName + ": " + text + "\n";
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 落盘失败不能影响自动化流程，logcat 仍有原始记录。
        }
    }

    /** mario.log → mario.1.log → … → mario.7.log；最旧一份丢弃。 */
    private static void rotateIfNeeded(File dir) {
        if (file == null || dir == null) return;
        if (!file.exists() || file.length() < MAX_BYTES) return;
        flushFoldedLocked(); // 新文件从干净状态开始，避免开头出现悬空的重复计数
        int last = KEEP_FILES - 1;
        new File(dir, "mario." + last + ".log").delete();
        for (int i = last - 1; i >= 1; i--) {
            File from = new File(dir, "mario." + i + ".log");
            if (from.exists()) from.renameTo(new File(dir, "mario." + (i + 1) + ".log"));
        }
        if (!file.renameTo(new File(dir, "mario.1.log"))) {
            // 滚动失败时宁可丢弃旧文件，也不让磁盘占用无限增长。
            file.delete();
        }
    }

    private static String header(Context app) {
        String version = "?";
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            version = info.versionName + "(" + info.versionCode + ")";
        } catch (Exception ignored) {
        }
        DisplayMetrics metrics = app.getResources().getDisplayMetrics();
        return "===== Mario 启动 v" + version + " " + Build.MANUFACTURER + " " + Build.MODEL
                + " Android " + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT
                + " " + metrics.widthPixels + "x" + metrics.heightPixels + " =====";
    }

    private static void installCrashHandler() {
        if (handlerInstalled) return;
        handlerInstalled = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            append("E", TAG, "FATAL in " + thread.getName() + " " + throwable + "\n" + writer);
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }
}
