package com.cortex.worktree;

/** 变更保护（G8/F12）：REMOVE 且未显式 discard 时，检测到未提交修改或新增 commit 抛出。 */
public class WorktreeHasChangesException extends java.io.IOException {

    public WorktreeHasChangesException(String path) {
        super("worktree 有未提交修改或新增 commit，拒绝删除: " + path + "（可加 --discard 强制删除）");
    }
}
