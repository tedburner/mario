package com.mario.assistant.application;

import java.util.function.IntSupplier;

/** 运行代际令牌：异步回调只有在令牌仍属于当前运行时才允许操作界面。 */
public final class RunToken {
    private final int generation;
    private final IntSupplier current;

    public RunToken(int generation, IntSupplier current) {
        this.generation = generation;
        this.current = current;
    }

    public boolean isCurrent() {
        return current.getAsInt() == generation;
    }
}
