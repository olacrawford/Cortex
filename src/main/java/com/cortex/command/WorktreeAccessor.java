package com.cortex.command;

import java.util.List;

/**
 * /worktree 命令对 Worktree 管理能力的窄接口（F24-F28，T13）：定义在 command 包，
 * 由 tui 包适配 {@code WorktreeManager} 实现——command 包不依赖 worktree 包（无循环）。
 * 实现方需对管理器未启用做防御（返回 null 的 accessor 由调用方报错兜底）。
 */
public interface WorktreeAccessor {

    /** 创建手动 Worktree（manual=true，不走自动清理）。 */
    WorktreeSummary create(String slug) throws Exception;

    /** 全部活跃 Worktree。 */
    List<WorktreeSummary> list();

    /** 进入 Worktree：之后主 Agent 工具调用以该目录为 explicit cwd。 */
    WorktreeSummary enter(String slug) throws Exception;

    /** 退出当前会话；remove=true 时删除目录（discard=true 跳过变更保护）。 */
    ExitResult exitCurrent(boolean remove, boolean discard) throws Exception;

    /** 直接删除指定 Worktree（允许非当前会话）。 */
    void remove(String slug, boolean discard) throws Exception;

    /**
     * 退出/删除结果。
     *
     * @param removed 是否已删除目录与分支
     * @param path    Worktree 路径字符串
     * @param branch  分支名
     */
    record ExitResult(boolean removed, String path, String branch) {}
}
