package com.mario.assistant.application;

import com.mario.assistant.domain.safety.ActionGuard;
import com.mario.assistant.infrastructure.ui.RunLog;

/** 把安全判定与审计落盘绑在一起：允许/拒绝都记录，拒绝时流程必须跳过该动作。 */
public final class ActionGate {
    private final RunLog log;
    private final RunToken token;

    public ActionGate(RunLog log, RunToken token) {
        this.log = log;
        this.token = token;
    }

    public boolean allow(ActionGuard.Surface surface, String label) {
        if (token != null && !token.isCurrent()) return false;
        ActionGuard.Decision decision = ActionGuard.evaluate(surface, label);
        String text = label == null ? "" : label;
        log.save("安全：" + (decision.allowed ? "允许" : "拒绝") + " " + surface
                + "「" + text + "」" + (decision.allowed ? "" : "：" + decision.reason));
        return decision.allowed;
    }
}
