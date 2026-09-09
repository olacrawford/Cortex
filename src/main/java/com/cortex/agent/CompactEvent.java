package com.cortex.agent;

/**
 * 上下文压缩状态事件（兑现 spec F24a / F24b）。
 * Before 状态 before/after 置 0；After 状态携带压缩前后 token 与可能非 null 的 error。
 */
public record CompactEvent(
        CompactPhase phase,
        long before,
        long after,
        Throwable error) implements AgentEvent {}
