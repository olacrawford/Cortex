package com.cortex.hook;

/**
 * 单条 hook 动作的执行结果：拦截信号 / 注入 prompt / 失败原因三选一（F19-F26）。
 */
public record ExecutionResult(boolean blocked, String reason, String prompt, Throwable error) {

    public static ExecutionResult empty() {
        return new ExecutionResult(false, null, null, null);
    }

    static ExecutionResult blocked(String reason) {
        return new ExecutionResult(true, reason, null, null);
    }

    static ExecutionResult prompt(String text) {
        return new ExecutionResult(false, null, text, null);
    }

    static ExecutionResult failed(Throwable error) {
        return new ExecutionResult(false, null, null, error);
    }
}
