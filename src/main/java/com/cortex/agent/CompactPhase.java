package com.cortex.agent;

/**
 * 上下文压缩状态事件的阶段（兑现 spec F24a / F24b）。
 * Before 状态在压缩请求发出前投递，让 TUI 能立刻显示「压缩中」前缀，避免用户以为程序卡死。
 */
public enum CompactPhase {
    BEFORE_AUTO,
    AFTER_AUTO,
    BEFORE_EMERGENCY,
    AFTER_EMERGENCY
}
