package com.cortex.command;

/**
 * Worktree 概要（/worktree list 输出行，F25）。
 *
 * @param name   原始 slug
 * @param path   绝对路径字符串
 * @param branch 分支名
 * @param manual 是否用户手动创建（true 时 autoCleanup 跳过）
 * @param active 是否为当前 enter 的会话目录
 */
public record WorktreeSummary(String name, String path, String branch, boolean manual, boolean active) {}
