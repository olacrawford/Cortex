package com.cortex.worktree;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 单个 Worktree 的元信息（F2）。
 *
 * @param name       原始 slug
 * @param path       绝对路径
 * @param branch     分支名（worktree-&lt;flatSlug&gt;）
 * @param basedOn    创建时的 base 引用（HEAD 或具体 commit）
 * @param headCommit 创建/恢复时的 HEAD SHA（快速恢复读不到时为空串）
 * @param created    创建时间（快速恢复为发现时间）
 * @param manual     是否用户手动创建（true = autoCleanup 跳过）
 */
public record Worktree(
        String name,
        Path path,
        String branch,
        String basedOn,
        String headCommit,
        Instant created,
        boolean manual) {}
