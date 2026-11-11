package com.mario.assistant.application;

import com.mario.assistant.infrastructure.ui.Diag;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

/** 同一时刻只允许一个任务运行；切换时先失效旧代际，再停止旧任务并启动新任务。 */
public final class RunCoordinator {
    /** 正常 tick 最多 4 秒；超过该空窗通常意味着进程被冻结或线程停摆。 */
    private static final long GAP_WARN_MS = 15000;
    private final RunHost host;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile TaskRunner active;
    private long lastTickAt;
    /** 一键自动化的待执行队列：当前任务自行收尾后按序接续；停止/异常/手动切换都会清空。 */
    private final ArrayDeque<TaskRunner> pending = new ArrayDeque<>();
    private final Runnable loop = new Runnable() {
        @Override public void run() {
            TaskRunner runner = active;
            if (runner == null) return;
            long now = host.now();
            if (lastTickAt > 0 && now - lastTickAt > GAP_WARN_MS) {
                Diag.i("MarioAdDiag", "run gap id=" + runner.id() + " gap=" + (now - lastTickAt) + "ms");
            }
            lastTickAt = now;
            try {
                runner.onTick(now);
            } catch (RuntimeException e) {
                Diag.i("MarioAdDiag", "runner error " + runner.id() + " " + e);
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
        pending.clear();
        activate(runner);
    }

    /** 一键自动化：首个任务收尾后按序接续后续任务，前台服务保持到链尾。 */
    public void startChain(TaskRunner first, TaskRunner... rest) {
        pending.clear();
        for (TaskRunner runner : rest) pending.add(runner);
        activate(first);
    }

    private void activate(TaskRunner runner) {
        if (active != null) detach();
        host.startForeground();
        int next = generation.incrementAndGet();
        active = runner;
        lastTickAt = 0;
        Diag.i("MarioAdDiag", "run start id=" + runner.id() + " tick=" + runner.tickIntervalMs()
                + " pending=" + pending.size());
        runner.onStart(host, new RunToken(next, generation::get));
        host.handler().postDelayed(loop, runner.initialDelayMs());
    }

    public void stop(String reason) {
        Diag.i("MarioAdDiag", "run stop active=" + (active == null ? "none" : active.id())
                + " reason=" + reason);
        pending.clear();
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
        TaskRunner next = pending.poll();
        Diag.i("MarioAdDiag", "run finish id=" + runner.id() + " next="
                + (next == null ? "none" : next.id()));
        if (next != null) activate(next);
    }
}
