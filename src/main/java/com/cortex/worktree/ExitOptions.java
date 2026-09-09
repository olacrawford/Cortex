package com.cortex.worktree;

/**
 * 退出/删除选项（F12）。
 *
 * @param discardChanges true = 跳过变更保护，强制删除
 */
public record ExitOptions(boolean discardChanges) {

    public static ExitOptions normal() {
        return new ExitOptions(false);
    }

    public static ExitOptions discard() {
        return new ExitOptions(true);
    }
}
