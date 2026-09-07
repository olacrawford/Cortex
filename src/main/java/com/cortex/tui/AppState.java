package com.cortex.tui;

/**
 * TUI 状态机的顶层状态。
 */
public enum AppState {
    /** 多 provider：启动后需用方向键选择。 */
    PROVIDER_SELECT,
    /** 对话中。 */
    CHAT
}
