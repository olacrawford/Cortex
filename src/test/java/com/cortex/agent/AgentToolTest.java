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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Agent 工具（F1-F3/F17/F18/N6/AC5/AC9/AC18）：参数校验、分流、嵌套阻断、超时切后台。 */
class AgentToolTest {

    @TempDir
    Path root;

    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> script;
        private int index = 0;

        FakeClient(List<List<StreamEvent>> script) { this.script = script; }

        @Override
        public LinkedBlockingQueue<StreamEvent> stream(Request req) {
            List<StreamEvent> events = index < script.size() ? script.get(index) : List.of(new StreamEvent.StreamEnd("stop", 0, 0));
            index++;
            return new LinkedBlockingQueue<>(events);
        }
    }

    /** 项目级自定义子 Agent（systemPrompt 注入验证用）。 */
    private static final String CUSTOM_AGENT = """
            ---
            name: worker
            description: 测试 worker
            maxTurns: 5
            ---

            你是 worker 子 Agent。
            """;

    private AgentTool tool(Manager mgr, boolean bgEnabled) throws Exception {
        Path agents = root.resolve(".cortex/agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("worker.md"), CUSTOM_AGENT);
        Catalog catalog = Catalog.load(root);
        return new AgentTool(catalog, mgr, bgEnabled);
    }

    /** 主 Agent + 注册了 Agent 工具的 registry；BYPASS 模式免主循环审批。 */
    private Agent parentAgent(LlmClient client, ToolRegistry registry) {
        return new Agent(client, registry, "test", PermissionEngine.create(root));
    }

    private static ToolRegistry registryWith(AgentTool agentTool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(agentTool);
        return registry;
    }

    // ── 参数校验（AC3 前置）──

    @Test
    void 缺prompt返回错误() throws Exception {
        AgentTool t = tool(new Manager(), true);
        Result r = t.execute("{\"description\":\"d\"}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("prompt"));

        Result r2 = t.execute("{\"prompt\":\"p\"}");
        assertTrue(r2.isError());
        assertTrue(r2.content().contains("description"));
    }

    @Test
    void 未初始化parent返回错误() throws Exception {
        AgentTool t = tool(new Manager(), true);
        Result r = t.execute("{\"prompt\":\"p\",\"description\":\"d\",\"subagent_type\":\"worker\"}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("未就绪"));
    }

    @Test
    void 未知subagent_type返回结构化错误() throws Exception {
        Manager mgr = new Manager();
        AgentTool t = tool(mgr, true);
        FakeClient client = new FakeClient(List.of());
        Agent parent = parentAgent(client, registryWith(t));
        t.setParent(parent);

        Result r = t.execute("{\"prompt\":\"p\",\"description\":\"d\",\"subagent_type\":\"ghost\"}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("未知 subagent_type: ghost"));
        // 错误文案列出可用角色
        assertTrue(r.content().contains("worker"));
    }

    // ── 定义式前台（AC2）──

    @Test
    void 定义式前台_子Agent最终文本作为tool_result() throws Exception {
        Manager mgr = new Manager();
        AgentTool t = tool(mgr, true);
        FakeClient client = new FakeClient(List.of(
                // 主 Agent 第 1 轮：调 Agent 工具
                List.of(new StreamEvent.ToolCallComplete("c1", "Agent",
                        "{\"prompt\":\"干点活\",\"description\":\"子任务\",\"subagent_type\":\"worker\"}"),
                        new StreamEvent.StreamEnd("tool_use", 0, 0)),
                // 子 Agent：纯文本完成
                List.of(new StreamEvent.TextDelta("worker 的最终报告"),
                        new StreamEvent.StreamEnd("stop", 0, 0)),
                // 主 Agent 第 2 轮：收尾
                List.of(new StreamEvent.TextDelta("收到。"),
                        new StreamEvent.StreamEnd("stop", 0, 0))));
        ToolRegistry registry = registryWith(t);
        Agent parent = parentAgent(client, registry);
        t.setParent(parent);

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("委派任务");
        drain(parent.run(conv, Mode.BYPASS, new CancelToken()));

        // tool_result = 子 Agent 最终文本（AC2）
        String toolResult = conv.getMessages().get(2).getToolResults().get(0).content();
        assertEquals("worker 的最终报告", toolResult);
        assertFalse(conv.getMessages().get(2).getToolResults().get(0).isError());
    }

    // ── 显式后台（AC9）──

    @Test
    void run_in_background立即返回async_launched() throws Exception {
        Manager mgr = new Manager();
        AgentTool t = tool(mgr, true);
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.TextDelta("后台子 Agent 的结果"),
                        new StreamEvent.StreamEnd("stop", 0, 0))));
        Agent parent = parentAgent(client, registryWith(t));
        t.setParent(parent);

        Result r = t.execute("{\"prompt\":\"后台干\",\"description\":\"d\",\"subagent_type\":\"worker\",\"run_in_background\":true}");
        assertFalse(r.isError());
        assertTrue(r.content().contains("\"status\":\"async_launched\""), r.content());
        String id = r.content().replaceAll(".*\"task_id\":\"([^\"]+)\".*", "$1");
        assertEquals(id, mgr.doneQueue().poll(10, TimeUnit.SECONDS), "完成通知进 done 队列（F16）");
        assertEquals("后台子 Agent 的结果", mgr.get(id).orElseThrow().result());
    }

    // ── 超时自动切后台（F17-②/AC10）──

    @Test
    void 前台超时自动转后台() throws Exception {
        Manager mgr = new Manager();
        AgentTool t = tool(mgr, true);
        // 只读慢工具：阻塞期间前台等待超时
        com.cortex.tool.Tool slow = new com.cortex.tool.Tool() {
            @Override public String name() { return "read_file"; }
            @Override public String description() { return "慢读"; }
            @Override public java.util.Map<String, Object> inputSchema() { return java.util.Map.of(); }
            @Override public boolean readOnly() { return true; }
            @Override public Result execute(String argsJson) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.ok("slow-done");
            }
        };
        ToolRegistry registry = registryWith(t);
        registry.register(slow);

        FakeClient client = new FakeClient(List.of(
                // 子 Agent：先调慢工具（期间前台超时），再文本收尾
                List.of(new StreamEvent.ToolCallComplete("s1", "read_file", "{\"path\":\"a.txt\"}"),
                        new StreamEvent.StreamEnd("tool_use", 0, 0)),
                List.of(new StreamEvent.TextDelta("慢任务终于完成"),
                        new StreamEvent.StreamEnd("stop", 0, 0))));
        Agent parent = parentAgent(client, registry);
        t.setParent(parent);

        System.setProperty("cortex.subagent.autoBackgroundMs", "300");
        try {
            Result r = t.execute("{\"prompt\":\"慢活\",\"description\":\"d\",\"subagent_type\":\"worker\"}");
            assertFalse(r.isError(), r.content());
            assertTrue(r.content().contains("\"status\":\"timed_out_to_background\""), r.content());
            String id = r.content().replaceAll(".*\"task_id\":\"([^\"]+)\".*", "$1");
            // 后台继续跑完 → done 通知
            assertEquals(id, mgr.doneQueue().poll(15, TimeUnit.SECONDS));
            assertEquals(com.cortex.task.Status.COMPLETED, mgr.get(id).orElseThrow().status());
        } finally {
            System.clearProperty("cortex.subagent.autoBackgroundMs");
        }
    }

    // ── 嵌套阻断（F24/AC5/AC19）：Fork 子 Agent 调 Agent 工具被拦截 ──

    @Test
    void fork子Agent再调Agent工具被QuerySource拦截() throws Exception {
        Manager mgr = new Manager();
        // 子 Agent 审批经 Manager 转发器自动放行（工具调用是 EXEC → Ask）
        mgr.setApprovalForwarder(req -> {
            req.respond().offer(Outcome.ALLOW_ONCE);
            return Optional.of(Outcome.ALLOW_ONCE);
        });
        AgentTool t = tool(mgr, true);
        // 主/子对话并发跑同一 client：按「消息中是否含 fork boilerplate」路由各自的脚本（确定性）
        java.util.concurrent.atomic.AtomicInteger parentCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger subCalls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient router = req -> {
            boolean isSub = req.messages().stream()
                    .anyMatch(m -> m.getContent() != null && m.getContent().contains(Fork.FORK_BOILERPLATE_TAG));
            LinkedBlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            if (!isSub) {
                if (parentCalls.getAndIncrement() == 0) {
                    // 主 Agent：Fork（不传 subagent_type）
                    q.add(new StreamEvent.ToolCallComplete("c1", "Agent",
                            "{\"prompt\":\"再 fork 一个读 README\",\"description\":\"fork 任务\"}"));
                } else {
                    q.add(new StreamEvent.TextDelta("fork 完成。"));
                }
                q.add(new StreamEvent.StreamEnd("stop", 0, 0));
            } else {
                if (subCalls.getAndIncrement() == 0) {
                    // Fork 子 Agent：又调 Agent 工具（应被拦截回灌）
                    q.add(new StreamEvent.ToolCallComplete("f1", "Agent",
                            "{\"prompt\":\"嵌套\",\"description\":\"d\"}"));
                } else {
                    q.add(new StreamEvent.TextDelta("Scope: 无法嵌套，结束。"));
                }
                q.add(new StreamEvent.StreamEnd("stop", 0, 0));
            }
            return q;
        };
        ToolRegistry registry = registryWith(t);
        Agent parent = parentAgent(router, registry);
        t.setParent(parent);

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("fork 一个子任务");
        drain(parent.run(conv, Mode.BYPASS, new CancelToken()));

        // Fork 强制后台：主 Agent 第 1 轮后拿到的 tool_result 是 async_launched
        String launchResult = conv.getMessages().get(2).getToolResults().get(0).content();
        assertTrue(launchResult.contains("\"status\":\"async_launched\""), launchResult);
        String id = launchResult.replaceAll(".*\"task_id\":\"([^\"]+)\".*", "$1");
        assertEquals(id, mgr.doneQueue().poll(20, TimeUnit.SECONDS));
        com.cortex.task.BackgroundTask bt = mgr.get(id).orElseThrow();
        assertEquals(com.cortex.task.Status.COMPLETED, bt.status());
        assertEquals("Scope: 无法嵌套，结束。", bt.result());

        // Fork 子对话首条 user 以 boilerplate 起头（AC4）+ 拦截错误已回灌
        var msgs = bt.conversation().getMessages();
        assertTrue(Fork.isForkContext(msgs));
        boolean blockedFedBack = msgs.stream()
                .anyMatch(m -> m.getToolResults().stream()
                        .anyMatch(tr -> tr.content().contains("Fork 子 Agent 不能再启动 Agent")));
        assertTrue(blockedFedBack, "嵌套调用应被拦截并回灌错误");
    }

    // ── N6 后台禁用 ──

    @Test
    void 后台禁用时fork报错_显式后台退化为前台() throws Exception {
        Manager mgr = new Manager();
        AgentTool t = tool(mgr, false);
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.TextDelta("worker 同步结果"),
                        new StreamEvent.StreamEnd("stop", 0, 0))));
        Agent parent = parentAgent(client, registryWith(t));
        t.setParent(parent);

        // Fork 路径：报错（AC18）
        Result fork = t.execute("{\"prompt\":\"f\",\"description\":\"d\"}");
        assertTrue(fork.isError());
        assertTrue(fork.content().contains("后台禁用"));

        // run_in_background=true：强制前台同步（N6），返回最终文本
        Result sync = t.execute("{\"prompt\":\"p\",\"description\":\"d\",\"subagent_type\":\"worker\",\"run_in_background\":true}");
        assertFalse(sync.isError());
        assertEquals("worker 同步结果", sync.content());
    }

    // ── schema / description（AC1）──

    @Test
    void 工具元信息与schema字段() throws Exception {
        AgentTool t = tool(new Manager(), true);
        assertEquals("Agent", t.name());
        assertFalse(t.readOnly());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> schema = (java.util.Map<String, Object>) t.inputSchema();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> props = (java.util.Map<String, Object>) schema.get("properties");
        for (String key : List.of("prompt", "description", "subagent_type", "model", "run_in_background", "name")) {
            assertTrue(props.containsKey(key), "schema 缺字段 " + key);
        }
        assertTrue(t.description().contains("worker"), "description 列出可用 subagent_type");
        assertTrue(t.description().contains("Explore"), "内置角色也在列");
    }

    // ── 辅助：逐事件消费主 Agent 队列直到 Done ──

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
