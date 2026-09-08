package com.cortex.agent;

import com.cortex.permission.Outcome;

import java.util.concurrent.BlockingQueue;

/**
 * 人在回路的待批准请求（F8）：agent 在权限兜底判为 Ask 时发出，
 * 阻塞等待 TUI 把用户选择放进 {@code respond}（容量 1）。
 * 取消路径下 TUI 兜底 offer DENY_ONCE 解阻塞。
 */
public record ApprovalRequest(
        String name,    // 工具内部名（● name(args) 展示用）
        String args,    // 参数预览
        String reason,  // 触发 Ask 的原因（模式 + 类别）
        BlockingQueue<Outcome> respond) {}
