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

    /**
     * 串联运行时是否允许接续下一棒。
     * 运行器以「请手动处理」类结果收尾时（页面停在不确定状态，残页/弹窗还盖在前台），
     * 继续跑下一棒只会在错误起点上叠加操作——返回 false 让协调器丢弃剩余队列。
     */
    default boolean chainShouldContinue() { return true; }
}
