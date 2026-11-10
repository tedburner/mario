package com.mario.assistant.domain.forest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 森林奖励页的任务台账：按标题记录「已完成 / 未确认 / 领取失败次数」。
 *
 * <p>替代原来用全局点击次数当进展代理的做法——一条浏览任务天然消耗「浏览 + 领取」两次点击，
 * 用总次数设上限会把「还没做完」误判成「做完了」。这里只回答两个问题：
 * 这条任务是否已经有结论、领取要不要再试一次。</p>
 */
public final class ForestTaskProgress {
    /** 领取点击后行仍可点，最多再试这么多次，超限按「未确认」收尾，不再空转。 */
    public static final int MAX_CLAIM_FAILURES = 2;
    private static final int SUMMARY_TITLES = 3;

    private final Set<String> done = new LinkedHashSet<>();
    private final Set<String> stuck = new LinkedHashSet<>();
    private final Map<String, Integer> claimFailures = new HashMap<>();

    public void markDone(String title) {
        if (isBlank(title)) return;
        stuck.remove(title);
        done.add(title);
    }

    public void markStuck(String title) {
        if (isBlank(title)) return;
        done.remove(title);
        stuck.add(title);
    }

    public boolean isDone(String title) {
        return title != null && done.contains(title);
    }

    public boolean isStuck(String title) {
        return title != null && stuck.contains(title);
    }

    /** 行后来变成了「立即领取 / 去抽奖」：解除未确认，重新纳入执行。 */
    public void clearStuck(String title) {
        if (isBlank(title)) return;
        stuck.remove(title);
    }

    /**
     * 行变成「立即领取 / 去抽奖」时解除一切结论。
     *
     * <p>「浏览完行消失」会被记成已完成，但奖励这时往往还没到手——过一会儿行会重新出现，
     * 按钮变成「立即领取」。如果只认 done 状态，这一行就再也不会被点，表现为
     * 「任务都完成了，奖励一直没领」。</p>
     */
    public void clearSettled(String title) {
        if (isBlank(title)) return;
        stuck.remove(title);
        done.remove(title);
    }

    /** 已有结论的任务不再进入，避免在同一行上反复点击。 */
    public boolean isSettled(String title) {
        return isDone(title) || isStuck(title);
    }

    /** 记一次「点击领取后行仍可点」，返回累计失败次数。 */
    public int recordClaimFailure(String title) {
        if (isBlank(title)) return 0;
        int next = claimFailures.getOrDefault(title, 0) + 1;
        claimFailures.put(title, next);
        return next;
    }

    public int claimFailures(String title) {
        return title == null ? 0 : claimFailures.getOrDefault(title, 0);
    }

    public boolean canRetryClaim(String title) {
        return claimFailures(title) < MAX_CLAIM_FAILURES;
    }

    public int doneCount() {
        return done.size();
    }

    public int stuckCount() {
        return stuck.size();
    }

    public List<String> doneTitles() {
        return new ArrayList<>(done);
    }

    public List<String> stuckTitles() {
        return new ArrayList<>(stuck);
    }

    /** 收尾文案：「完成 3 项；未确认 2 项：A、B」。 */
    public String summary() {
        StringBuilder sb = new StringBuilder("完成 ").append(done.size()).append(" 项");
        if (stuck.isEmpty()) return sb.toString();
        sb.append("；未确认 ").append(stuck.size()).append(" 项：");
        int index = 0;
        for (String title : stuck) {
            if (index >= SUMMARY_TITLES) {
                sb.append(" 等 ").append(stuck.size()).append(" 项");
                break;
            }
            if (index > 0) sb.append('、');
            sb.append(title);
            index++;
        }
        return sb.toString();
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
