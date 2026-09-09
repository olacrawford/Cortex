/**
 * Worktree 隔离（阶段13）：以 Git Worktree 为 SubAgent / 手动场景提供空间维度的
 * 文件系统隔离——同一仓库同时挂多个工作目录、共享版本库、各自一个分支。
 * 目录统一落在 {@code <repoRoot>/.cortex/worktrees/<flatSlug>/}；
 * 工作目录通过 explicit cwd（ToolContext）传递，不做进程级 chdir。
 */
package com.cortex.worktree;
