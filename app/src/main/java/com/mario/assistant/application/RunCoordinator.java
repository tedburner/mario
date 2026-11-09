package com.mario.assistant.application;

import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

/** 同一时刻只允许一个任务运行；切换时先失效旧代际，再停止旧任务并启动新任务。 */
public final class RunCoordinator {
    private final RunHost host;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile TaskRunner active;
    private final Runnable loop = new Runnable() {
        @Override public void run() {
            TaskRunner runner = active;
            if (runner == null) return;
            try {
                runner.onTick(host.now());
            } catch (RuntimeException e) {
                Log.i("MarioAdDiag", "runner error " + runner.id() + " " + e);
                stop("任务异常停止：" + e.getMessage());
                return;
            }
            if (runner.isFinished()) {
                release(runner);
                return;
            }
            if (active == runner) {
                host.handler().postDelayed(this, runner.tickIntervalMs());
            }
        }
    };

    public RunCoordinator(RunHost host) {
        this.host = host;
    }

    public void start(TaskRunner runner) {
        if (active != null) stop(null);
        host.startForeground();
        int next = generation.incrementAndGet();
        active = runner;
        runner.onStart(host, new RunToken(next, generation::get));
        host.handler().postDelayed(loop, runner.initialDelayMs());
    }

    public void stop(String reason) {
        detach();
        host.stopForeground();
        if (reason != null) host.log().save(reason);
    }

    /** 停止当前任务但保留前台服务，供另一个任务接续使用。 */
    public void detach() {
        TaskRunner runner = active;
        active = null;
        generation.incrementAndGet();
        host.handler().removeCallbacks(loop);
        if (runner != null) runner.onStop();
    }

    public boolean busy() {
        return active != null;
    }

    private void release(TaskRunner runner) {
        if (active != runner) return;
        active = null;
        generation.incrementAndGet();
        host.handler().removeCallbacks(loop);
    }
}
