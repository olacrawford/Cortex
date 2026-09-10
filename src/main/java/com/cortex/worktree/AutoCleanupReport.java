package com.cortex.worktree;

/**
 * autoCleanup 结果（F14/G9）：无变更的临时 Worktree 已删；有变更的保留并回报路径与分支，
 * 由主 Agent review 后决定合并或丢弃。
 *
 * @param kept   true = 保留（有变更或 manual 创建）
 * @param path   Worktree 绝对路径字符串（kept 时有意义）
 * @param branch 分支名（kept 时有意义）
 */
public record AutoCleanupReport(boolean kept, String path, String branch) {

    public static AutoCleanupReport removed() {
        return new AutoCleanupReport(false, "", "");
    }
}
