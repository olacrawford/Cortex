package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.worktree.AutoCleanupReport;
import com.cortex.worktree.Worktree;
import com.cortex.worktree.WorktreeManager;
import com.cortex.worktree.WorktreeNaming;

import java.nio.file.Path;

/**
 * SubAgent Worktree 隔离执行器（F21/G12）：isolation=worktree 的子 Agent 在独立的
 * Git Worktree 副本中工作——自动 create → 注入 worktree notice + explicit cwd →
 * 跑到底 → autoCleanup（无变更删除、有变更保留并回报路径与分支，G9）。
 * worktree 包不依赖 agent 包，本类单向引用无循环。
 */
public final class AgentWorktreeRunner {

    private final WorktreeManager wtMgr;

    public AgentWorktreeRunner(WorktreeManager wtMgr) {
        this.wtMgr = wtMgr;
    }

    /**
     * 在自动创建的临时 Worktree（{@code agent-a<7hex>}）中跑到完成，返回最终文本。
     * 跑完（含异常路径）执行 autoCleanup；保留时把路径与分支追加到结果文本。
     */
    public String executeWithWorktree(Agent subAgent, ConversationManager subConv, String prompt) throws Exception {
        String name = WorktreeNaming.randomAgentName();
        Worktree wt = wtMgr.create(name, "HEAD", false);
        try {
            // explicit cwd：子 Agent 的全部工具调用都在 Worktree 内解析相对路径（F18）
            subAgent.setToolContext(new com.cortex.tool.ToolContext(wt.path()));
            String taskText = buildWorktreeNotice(Path.of("").toAbsolutePath(), wt.path())
                    + "\n\n" + prompt;
            String finalText = subAgent.runToCompletion(new CancelToken(), subConv, taskText, null);
            AutoCleanupReport report = wtMgr.autoCleanup(name);
            if (report.kept()) {
                finalText = finalText + "\n[Worktree 保留: " + report.path()
                        + " ,分支 " + report.branch() + "]";
            }
            return finalText;
        } catch (Exception e) {
            // 异常收尾也尽力清理（无变更删除、有变更保留）
            try {
                wtMgr.autoCleanup(name);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /** worktree 上下文提醒（F22）：告知子 Agent 自身位置与路径翻译规则。 */
    public static String buildWorktreeNotice(Path parentCwd, Path wtPath) {
        return """
                <worktree-context>
                你当前在一个独立的 Git Worktree 副本中工作，与父 Agent 隔离。
                - 父目录: %s
                - 你的工作目录: %s
                - 父 Agent 提到的绝对路径基于父目录，你需要翻译成本地路径（替换前缀）再读写
                - 编辑文件前，必须先在本地 Worktree 重新 read_file 一次，避免使用过时内容
                </worktree-context>""".formatted(parentCwd, wtPath);
    }
}
