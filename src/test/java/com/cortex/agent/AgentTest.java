package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolDef;
import com.cortex.tool.Result;
import com.cortex.tool.Tool;
import com.cortex.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentTest {

    /** 脚本化假客户端：每次 stream() 按顺序回放一段预设事件。 */
    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> script = new ArrayList<>();
        private int index = 0;
        final List<List<ToolDef>> receivedTools = new ArrayList<>();

        void enqueue(List<StreamEvent> events) {
            script.add(events);
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<ToolDef> tools) {
            receivedTools.add(tools);
            return new LinkedBlockingQueue<>(script.get(index++));
        }
    }

    /** 计数桩工具：execute 时记录调用。 */
    private static final class StubTool implements Tool {
        final String toolName;
        final Result stubResult;
        int executions = 0;
        final List<String> receivedArgs = new ArrayList<>();

        StubTool(String toolName, Result stubResult) {
            this.toolName = toolName;
            this.stubResult = stubResult;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return "测试桩工具"; }
        @Override public Map<String, Object> inputSchema() { return new LinkedHashMap<>(); }

        @Override
        public Result execute(String argsJson) {
            executions++;
            receivedArgs.add(argsJson);
            return stubResult;
        }
    }

    /** 逐条取事件直到 Done/Failed，限时兜底。 */
    private static List<AgentEvent> drain(BlockingQueue<AgentEvent> queue) throws InterruptedException {
        List<AgentEvent> events = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent e = queue.poll(100, TimeUnit.MILLISECONDS);
            if (e == null) {
                continue;
            }
            events.add(e);
            if (e instanceof AgentEvent.Done || e instanceof AgentEvent.Failed) {
                return events;
            }
        }
        fail("事件流未在限时内结束: " + events);
        return events;
    }

    private static ToolDef stubDef(String name) {
        return new ToolDef(name, "测试桩工具", new LinkedHashMap<>());
    }

    // ─── 场景 (a)：单轮闭环，工具执行 + 结果回灌 + 最终答复（AC8）───

    @Test
    void 单轮闭环_工具执行后给最终答复() throws Exception {
        StubTool stub = new StubTool("read_file", Result.ok("文件内容：hello"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("我先读一下文件。"),
                new StreamEvent.ToolCallComplete("call-1", "read_file", "{\"path\":\"a.txt\"}"),
                new StreamEvent.StreamEnd("tool_use", 10, 5)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("文件里写的是 hello。"),
                new StreamEvent.StreamEnd("stop", 20, 10)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("读 a.txt 并总结");
        List<AgentEvent> list = drainList(new Agent(client, registry).run(conv));

        // 事件序列：Text(preamble) → Tool START → Tool END → Text(最终) → Done
        assertInstanceOf(AgentEvent.Text.class, list.get(0));
        assertEquals("我先读一下文件。", ((AgentEvent.Text) list.get(0)).delta());
        assertInstanceOf(AgentEvent.Tool.class, list.get(1));
        assertEquals(Phase.START, ((AgentEvent.Tool) list.get(1)).event().phase());
        assertEquals("a.txt", ((AgentEvent.Tool) list.get(1)).event().args());
        assertInstanceOf(AgentEvent.Tool.class, list.get(2));
        ToolEvent end = ((AgentEvent.Tool) list.get(2)).event();
        assertEquals(Phase.END, end.phase());
        assertFalse(end.isError());
        assertEquals("文件内容：hello", end.result());
        assertInstanceOf(AgentEvent.Text.class, list.get(3));
        assertEquals("文件里写的是 hello。", ((AgentEvent.Text) list.get(3)).delta());
        assertInstanceOf(AgentEvent.Done.class, list.get(4));

        // 桩工具只执行一次，参数原样传入
        assertEquals(1, stub.executions);
        assertEquals("{\"path\":\"a.txt\"}", stub.receivedArgs.get(0));

        // 两次请求都携带了工具定义
        assertEquals(2, client.receivedTools.size());
        assertEquals(List.of("read_file"),
                client.receivedTools.get(0).stream().map(ToolDef::name).toList());

        // 对话历史：user → assistant(带 toolCalls) → tool(带 toolResults) → assistant(最终)
        var msgs = conv.getMessages();
        assertEquals(4, msgs.size());
        assertEquals(ToolCall.class, msgs.get(1).getToolCalls().get(0).getClass());
        assertEquals("call-1", msgs.get(1).getToolCalls().get(0).id());
        assertEquals("read_file", msgs.get(1).getToolCalls().get(0).name());
        assertEquals("我先读一下文件。", msgs.get(1).getContent());
        assertEquals("call-1", msgs.get(2).getToolResults().get(0).toolCallId());
        assertEquals("文件内容：hello", msgs.get(2).getToolResults().get(0).content());
        assertFalse(msgs.get(2).getToolResults().get(0).isError());
        assertEquals("文件里写的是 hello。", msgs.get(3).getContent());
    }

    // ─── 场景 (b)：续答仍请求工具时不再执行（AC9 单轮上限）───

    @Test
    void 续答再请求工具则忽略_只执行一轮() throws Exception {
        StubTool stub = new StubTool("bash", Result.ok("ok"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c1", "bash", "{\"command\":\"ls\"}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("我还想再执行一次。"),
                new StreamEvent.ToolCallComplete("c2", "bash", "{\"command\":\"pwd\"}"),
                new StreamEvent.StreamEnd("stop", 0, 0)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("执行 ls");
        List<AgentEvent> list = drainList(new Agent(client, registry).run(conv));

        // 只有一对 START/END；c2 未被执行
        long startCount = list.stream()
                .filter(e -> e instanceof AgentEvent.Tool t && t.event().phase() == Phase.START)
                .count();
        assertEquals(1, startCount);
        assertEquals(1, stub.executions);
        assertTrue(stub.receivedArgs.stream().noneMatch(a -> a.contains("pwd")));

        // 最终答复取续答文本；历史里没有第二轮的 toolCalls 回合（被忽略的调用直接丢弃）
        assertInstanceOf(AgentEvent.Done.class, list.get(list.size() - 1));
        var msgs = conv.getMessages();
        assertEquals(4, msgs.size());
        assertEquals("我还想再执行一次。", msgs.get(3).getContent());
        assertTrue(msgs.get(3).getToolCalls().isEmpty());
    }

    // ─── 场景 (c)：续答为空文本时占位提示 ───

    @Test
    void 续答为空文本时用占位提示() throws Exception {
        StubTool stub = new StubTool("bash", Result.ok("done"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c1", "bash", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(new StreamEvent.StreamEnd("stop", 0, 0)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("跑一下");
        List<AgentEvent> list = drainList(new Agent(client, registry).run(conv));

        assertInstanceOf(AgentEvent.Done.class, list.get(list.size() - 1));
        assertEquals("（工具结果已回灌；本章为单轮工具模式，不再发起新一轮工具调用。）",
                conv.getMessages().get(3).getContent());
    }

    // ─── 场景 (d)：纯文本回合不走工具 ───

    @Test
    void 纯文本回合直接结束() throws Exception {
        StubTool stub = new StubTool("read_file", Result.ok("x"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub);

        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("你好！"),
                new StreamEvent.StreamEnd("stop", 1, 1)));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("你好");
        List<AgentEvent> list = drainList(new Agent(client, registry).run(conv));

        assertEquals(2, list.size());
        assertInstanceOf(AgentEvent.Text.class, list.get(0));
        assertInstanceOf(AgentEvent.Done.class, list.get(1));
        assertEquals(0, stub.executions);
        assertEquals(2, conv.size()); // user + assistant
        assertEquals("你好！", conv.getMessages().get(1).getContent());
    }

    // ─── 场景 (e)：上游错误 → Failed，会话历史不写脏数据 ───

    @Test
    void 流式错误转为Failed() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("bash", Result.ok("x")));

        FakeClient client = new FakeClient();
        client.enqueue(List.of(new StreamEvent.Error("连接超时")));

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hi");
        List<AgentEvent> list = drainList(new Agent(client, registry).run(conv));

        assertEquals(1, list.size());
        assertInstanceOf(AgentEvent.Failed.class, list.get(0));
        assertEquals("连接超时", ((AgentEvent.Failed) list.get(0)).message());
        assertEquals(1, conv.size());
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

    /** drain 包装：让 transform/apply 表达式可用。 */
    private static List<AgentEvent> drainList(BlockingQueue<AgentEvent> queue) throws InterruptedException {
        return drain(queue);
    }
}
