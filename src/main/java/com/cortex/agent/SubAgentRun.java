package com.cortex.agent;

/**
 * 前台子 Agent 运行句柄（F17/F15）：Agent 工具前台同步等待用，
 * 超时后任务继续在后台跑、工具立即返回 timed_out_to_background。
 * 由 {@code task.BackgroundTask} 实现。
 */
public interface SubAgentRun {

    /** 任务 ID（task_ 前缀）。 */
    String id();

    /** 等待跑完；true=已结束（读 status/result），false=超时（任务转后台继续）。 */
    boolean awaitCompletion(long timeoutMs) throws InterruptedException;

    /** 跑完后的最终文本（仅 awaitCompletion=true 且未失败时有意义）。 */
    String resultText();

    /** 是否以 FAILED 终态结束。 */
    boolean failed();

    /** 失败原因（failed 时非空）。 */
    String errorMessage();
}
