package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.conversation.Message;
import com.cortex.llm.LlmClient;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.ToolDef;
import com.cortex.prompt.PromptBuilder;
import com.cortex.tool.Result;
import com.cortex.tool.Tool;
import com.cortex.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentTest {

    /** 脚本化假客户端：每次 stream() 逐次回放脚本；脚本耗尽后回放 defaultResponse。 */
    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> script = new ArrayList<>();
        private List<StreamEvent> defaultResponse = List.of(new StreamEvent.StreamEnd("stop", 0, 0));
        private int index = 0;
        final List<List<ToolDef>> receivedTools = new ArrayList<>();
        final List<String> receivedSuffix = new ArrayList<>();

        void enqueue(List<StreamEvent> events) {
            script.add(events);
        }

        void setDefault(List<StreamEvent> events) {
            defaultResponse = events;
        }

        int streamCalls() {
            return index;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<ToolDef> tools, String systemSuffix) {
            receivedTools.add(tools);
            receivedSuffix.add(systemSuffix);
            List<StreamEvent> events = index < script.size() ? script.get(index) : defaultResponse;
            index++;
            return new LinkedBlockingQueue<>(events);
        }
    }

    /** 计数桩工具。 */
    private static final class StubTool implements Tool {
        final String toolName;
        final boolean ro;
        final Result stubResult;
        int executions = 0;
        final List<String> receivedArgs = new ArrayList<>();

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
            receivedArgs.add(argsJson);
            return stubResult;
        }
    }

    /** 阻塞桩工具：execute 阻塞在 latch 上直到被中断（供取消用例）。 */
    private static final class BlockingTool implements Tool {
        final CountDownLatch latch = new CountDownLatch(1);

        @Override public String name() { return "blocking"; }
        @Override public String description() { return "阻塞桩工具"; }
        @Override public boolean readOnly() { return false; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }

        @Override
        public Result execute(String argsJson) {
            try {
                latch.await(10, TimeUnit.SECONDS);
                return Result.ok("latch-opened");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.error("被中断");
            }
        }
    }

    /** 只读插桩工具：统计并发峰值。 */
    private static final class ConcurrencyProbeTool implements Tool {
        final String toolName;
        final AtomicInteger inFlight;
        final AtomicInteger peak;
        final AtomicLong endAt;

        ConcurrencyProbeTool(String toolName, AtomicInteger inFlight, AtomicInteger peak, AtomicLong endAt) {
            this.toolName = toolName;
            this.inFlight = inFlight;
            this.peak = peak;
            this.endAt = endAt;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return "并发探针"; }
        @Override public boolean readOnly() { return true; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }

        @Override
        public Result execute(String argsJson) {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            endAt.set(System.currentTimeMillis());
            return Result.ok("result-" + toolName);
        }
    }

    /** 有副作用插桩工具：记录开始时刻。 */
    private static final class TimestampedTool implements Tool {
        final String toolName;
        final AtomicLong startAt;

        TimestampedTool(String toolName, AtomicLong startAt) {
            this.toolName = toolName;
            this.startAt = startAt;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return "计时桩工具"; }
        @Override public boolean readOnly() { return false; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }

        @Override
        public Result execute(String argsJson) {
            startAt.set(System.currentTimeMillis());
            return Result.ok("result-" + toolName);
        }
    }

    private static ToolDef def(String name, boolean ro) {
        return new ToolDef(name, "测试桩工具", Map.of());
    }

    /** 逐条取事件直到 Done（Agent 保证任何路径都以 Done 收尾），限时兜底。 */
    private static List<AgentEvent> drain(BlockingQueue<AgentEvent> queue) throws InterruptedException {
        List<AgentEvent> events = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent e = queue.poll(100, TimeUnit.MILLISECONDS);
            if (e == null) {
                continue;
            }
            events.add(e);
            if (e instanceof AgentEvent.Done) {
                return events;
            }
        }
        fail("事件流未在限时内结束: " + events);
        return events;
    }

    private static List<AgentEvent> runAndDrain(Agent agent, ConversationManager conv, CancelToken cancel)
            throws InterruptedException {
        return drain(agent.run(conv, Mode.NORMAL, cancel));
    }

    // ─── 场景 A：多轮链路（AC1/AC2/AC6/AC7/F6）───

    @Test
    void 多轮链路_工具执行后自然完成() throws Exception {
        StubTool stub = new StubTool("read_file", true, Result.ok("文件内容：hello"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("我先读一下文件。"),
                new StreamEvent.ToolCallComplete("call-1", "read_file", "{\"path\":\"a.txt\"}"),
                new StreamEvent.UsageEvent(new com.cortex.llm.Usage(10, 5)),
                new StreamEvent.StreamEnd("tool_use", 10, 5)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("文件里写的是 hello。"),
                new StreamEvent.UsageEvent(new com.cortex.llm.Usage(20, 8)),
                new StreamEvent.StreamEnd("stop", 20, 8)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("读 a.txt 并总结");
        List<AgentEvent> list = runAndDrain(new Agent(client, registry), conv, new CancelToken());

        // 事件序列：Iter(1) → Text(preamble) → UsageReport → Tool START/END → Iter(2) → Text(最终) → UsageReport → Done
        assertInstanceOf(AgentEvent.Iter.class, list.get(0));
        assertEquals(1, ((AgentEvent.Iter) list.get(0)).iter());
        assertEquals("我先读一下文件。", ((AgentEvent.Text) list.get(1)).delta());
        assertInstanceOf(AgentEvent.UsageReport.class, list.get(2));
        assertEquals(10, ((AgentEvent.UsageReport) list.get(2)).usage().inputTokens());
        assertInstanceOf(AgentEvent.Tool.class, list.get(3));
        assertEquals(Phase.START, ((AgentEvent.Tool) list.get(3)).event().phase());
        assertInstanceOf(AgentEvent.Tool.class, list.get(4));
        ToolEvent end = ((AgentEvent.Tool) list.get(4)).event();
        assertEquals(Phase.END, end.phase());
        assertEquals("文件内容：hello", end.result());
        assertInstanceOf(AgentEvent.Iter.class, list.get(5));
        assertEquals(2, ((AgentEvent.Iter) list.get(5)).iter());
        assertEquals("文件里写的是 hello。", ((AgentEvent.Text) list.get(6)).delta());
        assertInstanceOf(AgentEvent.UsageReport.class, list.get(7));
        assertEquals(20, ((AgentEvent.UsageReport) list.get(7)).usage().inputTokens());
        assertInstanceOf(AgentEvent.Done.class, list.get(8));

        // 历史序列：user → assistant(toolCalls) → tool(results) → assistant(最终)
        var msgs = conv.getMessages();
        assertEquals(4, msgs.size());
        assertEquals(Message.Role.USER, msgs.get(0).getRole());
        assertEquals(Message.Role.ASSISTANT, msgs.get(1).getRole());
        assertEquals(1, msgs.get(1).getToolCalls().size());
        assertEquals("read_file", msgs.get(1).getToolCalls().get(0).name());
        assertEquals(Message.Role.TOOL, msgs.get(2).getRole());
        assertEquals("文件内容：hello", msgs.get(2).getToolResults().get(0).content());
        assertEquals(Message.Role.ASSISTANT, msgs.get(3).getRole());
        assertEquals("文件里写的是 hello。", msgs.get(3).getContent());
    }

    // ─── 场景 B：迭代上限兜底（AC3）───

    @Test
    void 迭代上限_恰好MAX轮后停止() throws Exception {
        StubTool stub = new StubTool("read_file", true, Result.ok("x"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.setDefault(List.of(
                new StreamEvent.ToolCallComplete("c", "read_file", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("循环调工具");
        List<AgentEvent> list = runAndDrain(new Agent(client, registry), conv, new CancelToken());

        assertEquals(Agent.MAX_ITERATIONS, client.streamCalls());
        assertTrue(list.stream().anyMatch(e -> e instanceof AgentEvent.Notice n
                && n.message().equals(Agent.NOTICE_MAX_ITER)));
        assertEquals(Message.Role.ASSISTANT, conv.lastRole().orElseThrow());
        assertEquals(Agent.NOTICE_MAX_ITER, conv.getMessages().get(conv.size() - 1).getContent());
    }

    // ─── 场景 C：连续未知工具停止 + 混入已知工具重置（AC4）───

    @Test
    void 连续未知工具达阈值即停() throws Exception {
        StubTool stub = new StubTool("read_file", true, Result.ok("x"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.setDefault(List.of(
                new StreamEvent.ToolCallComplete("c", "hallucinated_tool", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("调用不存在的工具");
        List<AgentEvent> list = runAndDrain(new Agent(client, registry), conv, new CancelToken());

        assertEquals(Agent.MAX_UNKNOWN_RUN, client.streamCalls());
        assertTrue(list.stream().anyMatch(e -> e instanceof AgentEvent.Notice n
                && n.message().equals(Agent.NOTICE_UNKNOWN_TOOLS)));
        assertEquals(Message.Role.ASSISTANT, conv.lastRole().orElseThrow());
    }

    @Test
    void 混入已知工具则计数重置不提前停() throws Exception {
        StubTool stub = new StubTool("read_file", true, Result.ok("x"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c1", "ghost_tool", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c2", "ghost_tool", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of( // 混入已知工具 → 计数重置
                new StreamEvent.ToolCallComplete("c3", "read_file", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("完成。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("混合调用");
        runAndDrain(new Agent(client, registry), conv, new CancelToken());

        // 若计数未重置，第 3 轮就会停；实际跑满 4 轮并以最终文本收尾
        assertEquals(4, client.streamCalls());
        assertEquals("完成。", conv.getMessages().get(conv.size() - 1).getContent());
    }

    // ─── 场景 D：保序分批并发（AC8/N6）───

    @Test
    void 连续只读并发_副作用串行_结果按调用序回灌() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicLong roEnd1 = new AtomicLong();
        AtomicLong roEnd2 = new AtomicLong();
        AtomicLong rwStart = new AtomicLong();

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ConcurrencyProbeTool("ro_a", inFlight, peak, roEnd1));
        registry.register(new ConcurrencyProbeTool("ro_b", inFlight, peak, roEnd2));
        registry.register(new TimestampedTool("rw", rwStart));

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("1", "ro_a", "{}"),
                new StreamEvent.ToolCallComplete("2", "ro_b", "{}"),
                new StreamEvent.ToolCallComplete("3", "rw", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("都做完了。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("批量执行");
        runAndDrain(new Agent(client, registry), conv, new CancelToken());

        // 两只读确实并发（峰值 ≥2），有副作用工具在其后开始
        assertTrue(peak.get() >= 2, "两只读应并发执行，峰值=" + peak.get());
        assertTrue(rwStart.get() >= Math.max(roEnd1.get(), roEnd2.get()),
                "有副作用工具应在两只读完成后才开始");

        // 结果按调用序回灌（按内容比对，不依赖工具名）
        var toolResults = conv.getMessages().get(2).getToolResults();
        assertEquals("result-ro_a", toolResults.get(0).content());
        assertEquals("result-ro_b", toolResults.get(1).content());
        assertEquals("result-rw", toolResults.get(2).content());
    }

    // ─── 场景 E：执行中取消，历史一致（AC9）───

    @Test
    void 执行中取消_历史配对合法_会话可继续() throws Exception {
        BlockingTool blocking = new BlockingTool();
        StubTool stub = new StubTool("read_file", true, Result.ok("x"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(blocking);
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("我先跑个命令。"),
                new StreamEvent.ToolCallComplete("c1", "blocking", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        // 若取消失效、循环意外继续，走 defaultResponse（历史断言会失败暴露问题）
        client.setDefault(List.of(
                new StreamEvent.TextDelta("不该到这里。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("会卡住的任务");
        CancelToken cancel = new CancelToken();
        BlockingQueue<AgentEvent> queue = new Agent(client, registry).run(conv, Mode.NORMAL, cancel);

        // 看到工具 START 后，等工具进入执行再取消
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent e = queue.poll(50, TimeUnit.MILLISECONDS);
            if (e instanceof AgentEvent.Tool t && t.event().phase() == Phase.START) {
                Thread.sleep(150); // 确保 execute 已进入阻塞
                cancel.cancel();
                break;
            }
            if (e instanceof AgentEvent.Done) {
                break;
            }
        }
        List<AgentEvent> rest = drain(queue);
        assertInstanceOf(AgentEvent.Done.class, rest.get(rest.size() - 1));

        // 历史配对合法：assistant(toolCalls) → tool(results) → assistant(已取消文本收尾)
        var msgs = conv.getMessages();
        assertEquals(Message.Role.ASSISTANT, conv.lastRole().orElseThrow());
        assertEquals(Agent.NOTICE_CANCELLED, msgs.get(msgs.size() - 1).getContent());
        assertEquals(Message.Role.TOOL, msgs.get(msgs.size() - 2).getRole());
        assertEquals(1, msgs.get(msgs.size() - 2).getToolResults().size());
        assertFalse(msgs.get(msgs.size() - 3).getToolCalls().isEmpty());

        // 会话可继续：再跑一轮纯文本正常收尾
        client.enqueue(List.of(
                new StreamEvent.TextDelta("继续没问题。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        List<AgentEvent> second = runAndDrain(new Agent(client, registry), conv, new CancelToken());
        assertTrue(second.stream().anyMatch(e -> e instanceof AgentEvent.Text t
                && t.delta().equals("继续没问题。")), "events=" + second + " streamCalls=" + client.streamCalls());
        assertEquals("继续没问题。", conv.getMessages().get(conv.size() - 1).getContent());
    }

    // ─── 场景 F：Plan Mode 只放开只读工具 + 系统后缀（AC13）───

    @Test
    void 计划模式_只注入只读工具与计划态后缀() throws Exception {
        ToolRegistry registry = ToolRegistry.createDefault();
        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("计划如下。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("出一个方案");
        List<AgentEvent> list = drain(new Agent(client, registry).run(conv, Mode.PLAN, new CancelToken()));

        assertInstanceOf(AgentEvent.Done.class, list.get(list.size() - 1));
        List<ToolDef> tools = client.receivedTools.get(0);
        assertTrue(tools.stream().allMatch(d -> List.of("read_file", "glob", "grep").contains(d.name())),
                "计划模式只应注入只读工具: " + tools);
        assertEquals(3, tools.size());
        assertEquals(PromptBuilder.PLAN_MODE_REMINDER, client.receivedSuffix.get(0));
    }

    // ─── 场景 G：流出错恢复（AC5）───

    @Test
    void 流出错_发Failed_历史收尾合法() throws Exception {
        StubTool stub = new StubTool("read_file", true, Result.ok("x"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(new StreamEvent.Error("连接超时")));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("恢复后正常。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hi");
        List<AgentEvent> list = runAndDrain(new Agent(client, registry), conv, new CancelToken());

        assertTrue(list.stream().anyMatch(e -> e instanceof AgentEvent.Failed f
                && f.message().equals("连接超时")));
        assertEquals(Agent.NOTICE_STREAM_ERR, conv.getMessages().get(conv.size() - 1).getContent());
        // 恢复后可继续
        client.streamCalls();
        runAndDrain(new Agent(client, registry), conv, new CancelToken());
        assertEquals("恢复后正常。", conv.getMessages().get(conv.size() - 1).getContent());
    }

    // ─── preview ───

    @Test
    void preview取关键字段并截断() {
        assertEquals("/tmp/a.txt", Agent.preview("{\"path\":\"/tmp/a.txt\"}"));
        assertEquals("ls -la", Agent.preview("{\"command\":\"ls -la\"}"));
        String longArg = "x".repeat(100);
        String preview = Agent.preview("{\"path\":\"" + longArg + "\"}");
        assertEquals(81, preview.length()); // 80 字符 + 省略号
        assertTrue(preview.endsWith("…"));
    }
}
