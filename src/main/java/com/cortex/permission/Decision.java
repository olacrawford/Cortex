package com.cortex.permission;

/**
 * 权限判定中间值：流水线各层给出 ALLOW/DENY 即短路；
 * 模式兜底层的值域严格为 {ALLOW, ASK}——DENY 只可能来自黑名单、沙箱、deny 规则、人在回路。
 */
public enum Decision {
    ALLOW,
    DENY,
    ASK
}
