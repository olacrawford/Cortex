package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.conversation.Message;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.permission.Mode;
import com.cortex.permission.Outcome;
import com.cortex.permission.PermissionEngine;
import com.cortex.tool.Result;
import com.cortex.tool.Tool;
import com.cortex.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** runToCompletion（F9/F10/G5）：跑到底循环、dontAsk、升级回调、maxTurns、事件转发。 */
class RunToCompletionTest {

    @TempDir
    Path root;

    /** 脚本化假客户端（同 AgentTest 模式）。 */
    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> script = new ArrayList<>();
        private List<StreamEvent> defaultResponse = List.of(new StreamEvent.StreamEnd("stop", 0, 0));
        private int index = 0;
        final List<Request> reqs = new ArrayList<>();

        void enqueue(List<StreamEvent> events) { script.add(events); }
        void setDefault(List<StreamEvent> events) { defaultResponse = events; }
        int streamCalls() { return index; }

        @Override
        public BlockingQueue<StreamEvent> stream(Request req) {
            reqs.add(req);
            List<StreamEvent> events = index < script.size() ? script.get(index) : defaultResponse;
            index++;
            return new LinkedBlockingQueue<>(events);
        }
    }

    /** 计数桩工具（EXEC 类：未知工具名走 Ask 判定路径）。 */
    private static final class StubTool implements Tool {
        final String toolName;
        final boolean ro;
        final Result stubResult;
        int executions = 0;

        StubTool(String toolName, boolean ro, Result stubResult) {
            this.toolName = toolName;
            this.ro = ro;
            this.stubResult = stubResult;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return "测试桩工具"; }
        @Override public boolean readOnly() { return ro; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }

        @Override
        public synchronized Result execute(String argsJson) {
            executions++;
            return stubResult;
        }
    }

    private PermissionEngine engine() {
        return PermissionEngine.create(root);
    }

    private Agent agent(FakeClient client, ToolRegistry registry) {
        return new Agent(client, registry, "test", engine());
    }

    @Test
    void 纯文本单轮_返回最终文本() throws Exception {
        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("子 Agent 完成了。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        String result = agent(client, new ToolRegistry()).runToCompletion(
                new CancelToken(), conv, "随便做点事", null);

        assertEquals("子 Agent 完成了。", result);
        assertEquals(2, conv.size()); // user(task) + assistant(最终文本)
        assertEquals(Message.Role.USER, conv.getMessages().get(0).getRole());
        assertEquals("随便做点事", conv.getMessages().get(0).getContent());
    }

    @Test
    void 工具调用后下一轮文本_工具被执行() throws Exception {
        StubTool stub = new StubTool("read_file", true, Result.ok("文件内容"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c1", "read_file", "{\"path\":\"a.txt\"}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("读到了。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        String result = agent(client, registry).runToCompletion(new CancelToken(), conv, "读 a.txt", null);
        assertEquals("读到了。", result);
        assertEquals(1, stub.executions);
        assertEquals(4, conv.size()); // user → assistant(toolCalls) → tool → assistant
    }

    @Test
    void 空task不追加user消息_Fork预装填场景() throws Exception {
        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("ok"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("预装填的任务");

        agent(client, new ToolRegistry()).runToCompletion(new CancelToken(), conv, "", null);
        assertEquals(2, conv.size(), "task 空串不应追加新 user 消息");
    }

    @Test
    void 触达maxTurns抛MaxTurnsReachedException() throws Exception {
        StubTool stub = new StubTool("read_file", true, Result.ok("x"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.setDefault(List.of(
                new StreamEvent.ToolCallComplete("c", "read_file", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        ConversationManager conv = new ConversationManager();

        Agent sub = Agent.builder(client, registry, "test", engine(), SessionRuntime.empty(200000))
                .maxTurns(3)
                .build();
        Agent.MaxTurnsReachedException e = assertThrows(Agent.MaxTurnsReachedException.class,
                () -> sub.runToCompletion(new CancelToken(), conv, "循环调工具", null));
        assertEquals(3, client.streamCalls(), "恰好 maxTurns 轮后停止");
        assertNotNull(e.lastAssistantText());
    }

    @Test
    void dontAsk模式Ask级工具自动放行() throws Exception {
        StubTool exec = new StubTool("run_cmd", false, Result.ok("命令输出"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(exec);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c1", "run_cmd", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("跑完了。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        // 默认权限模式（DEFAULT 下 EXEC 走 Ask）；dontAsk 短路后应无审批直接执行
        Agent sub = Agent.builder(client, registry, "test", engine(), SessionRuntime.empty(200000))
                .dontAsk(true)
                .build();
        String result = sub.runToCompletion(new CancelToken(), conv, "跑命令", null);
        assertEquals("跑完了。", result);
        assertEquals(1, exec.executions, "dontAsk 应自动放行 Ask 级工具（F12-②）");
    }

    @Test
    void 无dontAsk时Ask走默认emit路径() throws Exception {
        StubTool exec = new StubTool("run_cmd", false, Result.ok("不应执行"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(exec);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c1", "run_cmd", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("收到拒绝。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        BlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                agent(client, registry).runToCompletion(new CancelToken(), conv, "跑命令", events);
            } catch (Exception ignored) {
            }
        });
        // 模拟外部订阅者：收到 Approval 回 ALLOW_ONCE
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent e = events.poll(50, TimeUnit.MILLISECONDS);
            if (e instanceof AgentEvent.Approval a) {
                a.request().respond().offer(Outcome.ALLOW_ONCE);
            } else if (e instanceof AgentEvent.Text t && t.delta().equals("收到拒绝。")) {
                break;
            }
        }
        runner.join(5000);
        assertEquals(1, exec.executions, "emit 路径回传 ALLOW_ONCE 后应执行");
    }

    @Test
    void approvalUpgrader命中Ask决策() throws Exception {
        StubTool exec = new StubTool("run_cmd", false, Result.ok("升级放行了"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(exec);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c1", "run_cmd", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("完成。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        AtomicInteger upgraded = new AtomicInteger();
        Agent sub = Agent.builder(client, registry, "test", engine(), SessionRuntime.empty(200000))
                .approvalUpgrader(req -> {
                    upgraded.incrementAndGet();
                    return java.util.Optional.of(Outcome.ALLOW_ONCE);
                })
                .build();
        sub.runToCompletion(new CancelToken(), conv, "跑命令", null);
        assertEquals(1, upgraded.get(), "升级回调应在 Ask 决策时命中（F13）");
        assertEquals(1, exec.executions);
    }

    @Test
    void permissionMode覆盖生效_plan模式只暴露只读工具() throws Exception {
        StubTool ro = new StubTool("read_file", true, Result.ok("x"));
        StubTool rw = new StubTool("write_file", false, Result.ok("x"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(ro);
        registry.register(rw);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("计划好了。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        Agent sub = Agent.builder(client, registry, "test", engine(), SessionRuntime.empty(200000))
                .permissionMode(Mode.PLAN)
                .build();
        sub.runToCompletion(new CancelToken(), conv, "出个计划", null);
        List<String> exposed = client.reqs.get(0).tools().stream().map(d -> d.name()).toList();
        assertTrue(exposed.contains("read_file"));
        assertFalse(exposed.contains("write_file"), "plan 模式子 Agent 不应看到写工具");
    }

    @Test
    void allowedTools白名单收窄工具定义与执行闸() throws Exception {
        StubTool ro = new StubTool("read_file", true, Result.ok("x"));
        StubTool rw = new StubTool("write_file", false, Result.ok("不应执行"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(ro);
        registry.register(rw);

        FakeClient client = new FakeClient();
        // 越权调用 write_file（模型幻觉）→ 执行闸拦截回灌错误
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c1", "write_file", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("好的。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        Agent sub = Agent.builder(client, registry, "test", engine(), SessionRuntime.empty(200000))
                .allowedTools(java.util.Set.of("read_file"))
                .build();
        sub.runToCompletion(new CancelToken(), conv, "干活", null);
        assertEquals(0, rw.executions, "白名单外工具不得执行");
        assertEquals(1, client.reqs.get(0).tools().size(), "白名单收窄工具定义（F30/F31）");
        assertTrue(conv.getMessages().get(2).getToolResults().get(0).isError());
    }

    @Test
    void 事件转发到外部队列() throws Exception {
        StubTool ro = new StubTool("read_file", true, Result.ok("内容"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(ro);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("先读。"),
                new StreamEvent.ToolCallComplete("c1", "read_file", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("完成。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        BlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
        agent(client, registry).runToCompletion(new CancelToken(), conv, "读", events);

        List<AgentEvent> collected = new ArrayList<>();
        events.drainTo(collected);
        assertTrue(collected.stream().anyMatch(e -> e instanceof AgentEvent.Iter));
        assertTrue(collected.stream().anyMatch(e -> e instanceof AgentEvent.Text));
        assertTrue(collected.stream().anyMatch(e -> e instanceof AgentEvent.Tool t
                && t.event().phase() == Phase.END));
    }

    @Test
    void 取消抛CancellationException() throws Exception {
        StubTool ro = new StubTool("read_file", true, Result.ok("x"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(ro);

        FakeClient client = new FakeClient();
        client.setDefault(List.of(
                new StreamEvent.ToolCallComplete("c", "read_file", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        ConversationManager conv = new ConversationManager();

        CancelToken cancel = new CancelToken();
        Agent sub = Agent.builder(client, registry, "test", engine(), SessionRuntime.empty(200000))
                .maxTurns(10)
                .build();
        cancel.cancel(); // 预先取消：循环首个检查点即退出
        assertThrows(CancellationException.class,
                () -> sub.runToCompletion(cancel, conv, "任务", null));
    }
}
