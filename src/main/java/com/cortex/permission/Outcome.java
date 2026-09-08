package com.cortex.permission;

/**
 * 人在回路三选一的结果（F8）。
 */
public enum Outcome {
    /** 拒绝本次（回灌让模型调整）。 */
    DENY_ONCE,
    /** 允许本次（不留规则）。 */
    ALLOW_ONCE,
    /** 永久允许（精确规则写入本地层配置，跨会话生效）。 */
    ALLOW_FOREVER
}
