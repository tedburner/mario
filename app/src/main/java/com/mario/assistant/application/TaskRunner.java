package com.mario.assistant.application;

/** 单个任务的运行协议：各自持有状态，只通过 tick 推进；任务之间互不引用。 */
public interface TaskRunner {
    String id();
    long tickIntervalMs();
    long initialDelayMs();
    void onStart(RunHost host, RunToken token);
    void onTick(long now);
    void onStop();
    /** 运行器已自行收尾（记录结果并停止前台服务），协调器只需释放槽位。 */
    default boolean isFinished() { return false; }
}
