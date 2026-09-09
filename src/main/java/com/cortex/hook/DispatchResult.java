package com.cortex.hook;

import java.util.List;

/**
 * 一次事件分派的结果：拦截判定 + 待注入 prompt 集合（F31/F32）。
 */
public record DispatchResult(
        boolean blocked,
        String reason,
        String blockingHookName,
        List<String> injectedPrompts) {

    public static DispatchResult empty() {
        return new DispatchResult(false, null, null, List.of());
    }
}
