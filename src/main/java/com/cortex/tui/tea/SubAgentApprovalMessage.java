package com.cortex.tui.tea;

/**
 * 子 Agent 审批唤醒消息（阶段12 F13）：后台/前台子 Agent 的审批请求被转发到主 TUI 时，
 * 由 Manager 转发线程投递，唤醒 UI 事件循环重绘审批弹窗（pending 已由转发器设置）。
 */
public record SubAgentApprovalMessage() implements com.cortex.tui.tea.Message {}
