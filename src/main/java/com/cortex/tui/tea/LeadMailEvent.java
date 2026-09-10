package com.cortex.tui.tea;

/**
 * Lead 邮箱唤醒事件（F41b）：LeadMailWaiter 收到信号后投递；
 * Lead 空闲时自动开轮处理队员消息，非空闲则 reminder 已在队列自然取出。
 */
public record LeadMailEvent() implements com.cortex.tui.tea.Message {}
