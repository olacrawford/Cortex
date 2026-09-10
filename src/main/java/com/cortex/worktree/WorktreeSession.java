package com.cortex.worktree;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * 当前活跃的 Worktree 会话（F3）：进入 Worktree 时记录原状态，供退出还原与启动恢复。
 * 序列化到 {@code <repoRoot>/.cortex/worktree_session.json}（F30，原子写）。
 *
 * @param originalCwd        进入前的 JVM 当前目录（绝对路径字符串）
 * @param worktreePath       Worktree 绝对路径
 * @param worktreeName       原 slug
 * @param originalBranch     进入前主仓库分支
 * @param originalHeadCommit 进入前主仓库 HEAD SHA
 * @param sessionId          会话 UUID
 * @param hookBased          预留（hook 驱动的会话）
 */
public record WorktreeSession(
        @JsonProperty("original_cwd") String originalCwd,
        @JsonProperty("worktree_path") String worktreePath,
        @JsonProperty("worktree_name") String worktreeName,
        @JsonProperty("original_branch") String originalBranch,
        @JsonProperty("original_head_commit") String originalHeadCommit,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("hook_based") boolean hookBased) {

    public static WorktreeSession create(String originalCwd, String worktreePath, String worktreeName,
                                         String originalBranch, String originalHeadCommit) {
        return new WorktreeSession(originalCwd, worktreePath, worktreeName,
                originalBranch, originalHeadCommit, UUID.randomUUID().toString(), false);
    }
}
