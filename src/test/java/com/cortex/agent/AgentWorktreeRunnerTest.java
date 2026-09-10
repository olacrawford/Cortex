package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.permission.PermissionEngine;
import com.cortex.tool.Result;
import com.cortex.tool.Tool;
import com.cortex.tool.ToolContext;
import com.cortex.tool.ToolRegistry;
import com.cortex.worktree.WorktreeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** AgentWorktreeRunner（T11/F21/F22/AC15）：create、cwd 注入、autoCleanup、notice 模板。 */
class AgentWorktreeRunnerTest {

    @TempDir
    Path tmp;

    private static final class FakeClient implements LlmClient {
        private int round = 0;

        @Override
        public LinkedBlockingQueue<StreamEvent> stream(Request req) {
            LinkedBlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            if (round++ == 0) {
                // 第 1 轮：调用探针工具（read_file），ctx.cwd 由 runner 注入
                q.add(new StreamEvent.ToolCallComplete("c1", "read_file", "{\"path\":\"a.txt\"}"));
            } else {
                q.add(new StreamEvent.TextDelta("子 Agent 完成"));
            }
            q.add(new StreamEvent.StreamEnd("tool_use", 0, 0));
            return q;
        }
    }

    /** 探针工具：记录执行现场的 ctx cwd，并写入一个文件制造变更。 */
    private static final class ProbeTool implements Tool {
        final AtomicReference<Path> seenCwd = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean ctxVariantRan = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean legacyRan = new java.util.concurrent.atomic.AtomicBoolean();
        final boolean makeChanges;

        ProbeTool(boolean makeChanges) {
            this.makeChanges = makeChanges;
        }

        @Override public String name() { return "read_file"; }
        @Override public String description() { return "探针"; }
        @Override public boolean readOnly() { return !makeChanges; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }

        @Override
        public Result execute(String argsJson) {
            legacyRan.set(true);
            return execute(ToolContext.EMPTY, argsJson);
        }

        @Override
        public Result execute(ToolContext ctx, String argsJson) {
            ctxVariantRan.set(true);
            seenCwd.set(ctx.cwd().orElse(null));
            if (makeChanges) {
                try {
                    Files.writeString(ctx.resolvePath("out.txt"), "written");
                } catch (Exception e) {
                    return Result.error(e.getMessage());
                }
            }
            return Result.ok("probe");
        }
    }

    private Agent newSub(ToolRegistry registry, PermissionEngine engine) {
        return Agent.builder(new FakeClient(), registry, "test", engine, SessionRuntime.empty(200000))
                .permissionMode(com.cortex.permission.Mode.BYPASS) // 免审批
                .build();
    }

    @Test
    void 无变更时自动清理_worktree被删() throws Exception {
        Path repo = com.cortex.worktree.GitTestSupport.initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = new WorktreeManager(repo);
        ProbeTool probe = new ProbeTool(false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(probe);

        String text = new AgentWorktreeRunner(mgr).executeWithWorktree(
                newSub(registry, PermissionEngine.create(repo)), new ConversationManager(), "只读任务");

        System.out.println("DBG-TEXT[" + text + "]");
        System.out.println("DBG-CWD[" + probe.seenCwd.get() + "]");
        System.out.println("DBG-ACTIVE" + mgr.list());
        System.out.println("DBG-REPO-OUT " + Files.exists(repo.resolve("out.txt")));
        assertEquals("子 Agent 完成", text);
        assertTrue(probe.seenCwd.get().toString().contains(".cortex/worktrees" + java.io.File.separator + "agent-a"),
                "工具调用应收到 worktree cwd: " + probe.seenCwd.get());
        assertTrue(mgr.list().stream().noneMatch(w -> w.name().startsWith("agent-a")),
                "无变更应被 autoCleanup 删除");
    }

    @Test
    void 有变更时保留并追加提示_且不污染主目录() throws Exception {
        Path repo = com.cortex.worktree.GitTestSupport.initRepo(tmp.resolve("repo2"));
        WorktreeManager mgr = new WorktreeManager(repo);
        ProbeTool probe = new ProbeTool(true);
        ToolRegistry registry = new ToolRegistry();
        registry.register(probe);

        String text = new AgentWorktreeRunner(mgr).executeWithWorktree(
                newSub(registry, PermissionEngine.create(repo)), new ConversationManager(), "写点东西");

        assertTrue(text.contains("[Worktree 保留: "), "保留提示应追加到结果文本: " + text);
        assertTrue(Files.exists(probe.seenCwd.get().resolve("out.txt")), "写入发生在 worktree 内");
        assertFalse(Files.exists(repo.resolve("out.txt")), "主目录不受影响（AC16 前置）");
    }

    @Test
    void notice模板含标签与两个路径() {
        String notice = AgentWorktreeRunner.buildWorktreeNotice(
                Path.of("/parent"), Path.of("/repo/.cortex/worktrees/agent-a1234567"));
        assertTrue(notice.contains("<worktree-context>"));
        assertTrue(notice.contains("</worktree-context>"));
        assertTrue(notice.contains("/parent"));
        assertTrue(notice.contains("agent-a1234567"));
    }
}
