package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.permission.Mode;
import com.cortex.permission.Outcome;
import com.cortex.permission.PermissionEngine;
import com.cortex.subagent.Catalog;
import com.cortex.task.Manager;
import com.cortex.tool.Result;
import com.cortex.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** AgentTool × Worktree（T12/F21/F23/AC15/AC16）：isolation 分支、前台强制、wtMgr 缺失报错。 */
class AgentToolWorktreeTest {

    @TempDir
    Path tmp;

    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> script;
        private int index = 0;

        FakeClient(List<List<StreamEvent>> script) { this.script = script; }

        @Override
        public LinkedBlockingQueue<StreamEvent> stream(Request req) {
            List<List<StreamEvent>> s = script;
            List<StreamEvent> events = index < s.size() ? s.get(index) : List.of(new StreamEvent.StreamEnd("stop", 0, 0));
            index++;
            return new LinkedBlockingQueue<>(events);
        }
    }

    private Path repo() throws Exception {
        Path r = com.cortex.worktree.GitTestSupport.initRepo(tmp.resolve("repo-" + System.nanoTime()));
        Files.createDirectories(r.resolve(".cortex/agents"));
        Files.writeString(r.resolve(".cortex/agents/wt-writer.md"), """
                ---
                name: wt-writer
                description: 在 Worktree 内写文件的测试 Agent
                permissionMode: bypassPermissions
                maxTurns: 5
                isolation: worktree
                ---

                你是 wt-writer。
                """);
        return r;
    }

    private static final com.cortex.subagent.Definition defOf(Catalog c) {
        return c.resolve("wt-writer").orElseThrow();
    }

    @Test
    void isolation子Agent在worktree内写文件_主目录不受影响() throws Exception {
        Path repo = repo();
        com.cortex.worktree.WorktreeManager mgr = new com.cortex.worktree.WorktreeManager(repo);
        Catalog catalog = Catalog.load(repo);
        assertEquals("worktree", defOf(catalog).isolation());

        AgentTool agentTool = new AgentTool(catalog, new Manager(), true, mgr);
        ToolRegistry registry = new ToolRegistry();
        registry.register(agentTool);
        registry.register(new com.cortex.tool.WriteFileTool()); // 子 Agent 在 worktree 内写文件

        // 顺序脚本：父轮1 调 Agent 工具 → 子轮1 写文件 → 子轮2 文本收尾 → 父轮2 收尾
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("c1", "Agent",
                        "{\"prompt\":\"把 scratch.txt 覆盖为 SUBAGENT\",\"description\":\"隔离写入\","
                                + "\"subagent_type\":\"wt-writer\"}"),
                        new StreamEvent.StreamEnd("tool_use", 0, 0)),
                List.of(new StreamEvent.ToolCallComplete("s1", "write_file",
                        "{\"path\":\"scratch.txt\",\"content\":\"SUBAGENT\"}"),
                        new StreamEvent.StreamEnd("tool_use", 0, 0)),
                List.of(new StreamEvent.TextDelta("写完了"),
                        new StreamEvent.StreamEnd("stop", 0, 0)),
                List.of(new StreamEvent.TextDelta("主 Agent 收尾"),
                        new StreamEvent.StreamEnd("stop", 0, 0))));
        Agent parent = new Agent(client, registry, "test", PermissionEngine.create(repo));
        agentTool.setParent(parent);

        Files.writeString(repo.resolve("scratch.txt"), "MAIN"); // 主目录基线
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("委派隔离任务");
        drain(parent.run(conv, Mode.BYPASS, new CancelToken()));

        // tool_result 含子 Agent 最终文本 + 保留提示（有变更 → 保留）
        String toolResult = conv.getMessages().get(2).getToolResults().get(0).content();
        assertTrue(toolResult.contains("写完了"), toolResult);
        assertTrue(toolResult.contains("[Worktree 保留: "), "有变更应保留并提示: " + toolResult);

        // AC16：主目录未变、Worktree 副本已写
        assertEquals("MAIN", Files.readString(repo.resolve("scratch.txt")));
        String keptPath = toolResult.replaceAll("(?s).*\\[Worktree 保留: ([^,\\]]+),?.*\\].*", "$1");
        Path wtOut = Path.of(keptPath.trim()).resolve("scratch.txt");
        assertTrue(Files.readString(wtOut).equals("SUBAGENT"), "Worktree 副本应含 SUBAGENT");

        // 清理
        com.cortex.worktree.Worktree wt = mgr.list().stream()
                .filter(w -> w.name().startsWith("agent-a")).findFirst().orElseThrow();
        mgr.remove(wt.name(), com.cortex.worktree.ExitOptions.discard());
    }

    @Test
    void isolation加run_in_background强制前台() throws Exception {
        Path repo = repo();
        com.cortex.worktree.WorktreeManager mgr = new com.cortex.worktree.WorktreeManager(repo);
        Catalog catalog = Catalog.load(repo);
        AgentTool agentTool = new AgentTool(catalog, new Manager(), true, mgr);
        ToolRegistry registry = new ToolRegistry();
        registry.register(agentTool);

        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.TextDelta("同步完成"),
                        new StreamEvent.StreamEnd("stop", 0, 0))));
        Agent parent = new Agent(client, registry, "test", PermissionEngine.create(repo));
        agentTool.setParent(parent);

        Result r = agentTool.execute("{\"prompt\":\"p\",\"description\":\"d\","
                + "\"subagent_type\":\"wt-writer\",\"run_in_background\":true}");
        assertFalse(r.isError());
        assertTrue(r.content().contains("同步完成"), "F23：应同步前台跑完而非返回 async JSON: " + r.content());
    }

    @Test
    void wtMgr缺失时isolation请求返回错误() throws Exception {
        Path repo = repo();
        Catalog catalog = Catalog.load(repo);
        AgentTool agentTool = new AgentTool(catalog, new Manager(), true, null);
        agentTool.setParent(new Agent(new FakeClient(List.of()), new ToolRegistry(), "t",
                PermissionEngine.create(repo)));

        Result r = agentTool.execute("{\"prompt\":\"p\",\"description\":\"d\",\"subagent_type\":\"wt-writer\"}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("Worktree 管理器未启用"));
    }

    private static void drain(BlockingQueue<AgentEvent> queue) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent e = queue.poll(100, TimeUnit.MILLISECONDS);
            if (e instanceof AgentEvent.Approval a) {
                a.request().respond().offer(Outcome.ALLOW_ONCE);
            } else if (e instanceof AgentEvent.Done) {
                return;
            }
        }
        fail("主 Agent 事件流未在限时内结束");
    }
}
