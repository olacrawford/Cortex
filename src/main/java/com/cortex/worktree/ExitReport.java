package com.cortex.worktree;

/**
 * 退出结果（F12-6）：removed 指示是否已删除目录；path/branch 供上层还原与展示。
 *
 * @param removed 是否已删除 Worktree 目录与分支
 * @param path    Worktree 绝对路径字符串
 * @param branch  分支名
 */
public record ExitReport(boolean removed, String path, String branch) {}
