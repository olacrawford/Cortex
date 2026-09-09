package com.cortex.compact;

import com.cortex.compact.state.AutoCompactTrackingState;
import com.cortex.compact.state.ContentReplacementState;
import com.cortex.compact.state.SessionContext;
import com.cortex.compact.support.FakeCompactProvider;
import com.cortex.conversation.ConversationManager;
import com.cortex.conversation.Message;
import com.cortex.llm.PromptTooLongException;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolDef;
import com.cortex.llm.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Layer2Test {

    @TempDir
    Path tempDir;

    private SessionContext session() throws IOException {
        return SessionContext.create(tempDir);
    }

    private ContextCompactor.Input input(FakeCompactProvider client, ConversationManager conv, int cw,
                                         ContextCompactor.TriggerKind trigger) throws IOException {
        return new ContextCompactor.Input(conv, client, cw, List.of(),
                new ContentReplacementState(), new Recovery.RecoveryState(),
                new AutoCompactTrackingState(), session(), 0, 0, 0, trigger);
    }

    private static Message user(String s) { return new Message(Message.Role.USER, s); }
    private static Message assistant(String s) { return new Message(Message.Role.ASSISTANT, s); }
    private static Message tool(List<ToolResult> rs) { return new Message(Message.Role.TOOL, "", List.of(), rs); }

    @Test
    void pickRecentTail两个下界都满足() {
        // 构造 6 条短消息，token 与条数都足够
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            msgs.add(user("u" + i));
        }
        List<Message> tail = ContextCompactor.pickRecentTail(msgs);
        // 6 条足够：应保留全部（token≥10000 由 chars 决定，但 6 条已 ≥5）
        assertEquals(6, tail.size());
        assertEquals(Message.Role.USER, tail.get(0).getRole());
    }

    @Test
    void pickRecentTail配对修正起点不是落单tool() {
        // 最近原文尾：... user, assistant(toolCalls), tool(results) 恰好被截断在 tool 前 → 起点前推到 assistant
        List<Message> msgs = new ArrayList<>();
        // 大量填充以越过 token/条数下界，最后三条是 user/assistant(toolCalls)/tool
        for (int i = 0; i < 20; i++) {
            msgs.add(user("u" + i + "x".repeat(200)));
        }
        msgs.add(user("final"));
        msgs.add(new Message(Message.Role.ASSISTANT, "call", List.of(new ToolCall("c1", "read_file", "{}")), List.of()));
        msgs.add(tool(List.of(new ToolResult("c1", "内容", false))));
        List<Message> tail = ContextCompactor.pickRecentTail(msgs);
        assertNotEquals(Message.Role.TOOL, tail.get(0).getRole(),
                "截断点不应落在落单 tool_result 上");
    }

    @Test
    void joinAfterSummary避免连续user() {
        Message summary = new Message(Message.Role.USER, "摘要+恢复");
        List<Message> recent = List.of(user("u1"), assistant("a1"));
        List<Message> joined = ContextCompactor.joinAfterSummary(summary, recent);
        assertEquals(4, joined.size());
        assertEquals(Message.Role.USER, joined.get(0).getRole());
        assertEquals(Message.Role.ASSISTANT, joined.get(1).getRole(), "应插入 assistant 衔接占位");
        assertEquals(Message.Role.USER, joined.get(2).getRole());
        assertEquals(Message.Role.ASSISTANT, joined.get(3).getRole());
    }

    @Test
    void joinAfterSummary空近期原文() {
        Message summary = new Message(Message.Role.USER, "摘要");
        List<Message> joined = ContextCompactor.joinAfterSummary(summary, List.of());
        assertEquals(1, joined.size());
    }

    @Test
    void groupByUserTurn分组() {
        List<Message> msgs = List.of(user("u0"), assistant("a0"), tool(List.of()),
                user("u1"), assistant("a1"));
        List<List<Message>> groups = ContextCompactor.groupByUserTurn(msgs);
        assertEquals(2, groups.size());
        assertEquals(3, groups.get(0).size());
        assertEquals(2, groups.get(1).size());
    }

    @Test
    void groupByUserTurn首条非user() {
        List<Message> msgs = List.of(assistant("a0"), user("u0"), assistant("a1"));
        List<List<Message>> groups = ContextCompactor.groupByUserTurn(msgs);
        assertEquals(2, groups.size());
        assertEquals(1, groups.get(0).size()); // 首条 assistant 单独塞进第 0 组
        assertEquals(2, groups.get(1).size());
    }

    @Test
    void ptlRetry前三次丢一组() throws Exception {
        FakeCompactProvider client = new FakeCompactProvider();
        // 脚本：第一次 PTL，第二次成功
        client.enqueue(List.of(new StreamEvent.Error("ptl",
                new PromptTooLongException(new RuntimeException("ptl")))));
        client.enqueue(List.of(new StreamEvent.TextDelta("<summary>ok</summary>"),
                new StreamEvent.StreamEnd("stop", 0, 0)));

        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            msgs.add(user("u" + i));
            msgs.add(assistant("a" + i));
        }
        ConversationManager conv = new ConversationManager();
        ContextCompactor.Input in = input(client, conv, 200000, ContextCompactor.TriggerKind.MANUAL);
        String text = ContextCompactor.ptlRetry(in, msgs,
                new PromptTooLongException(new RuntimeException("first")));
        assertEquals("ok", text);
        assertEquals(2, client.streamCalls());
    }

    @Test
    void ptlRetry全部丢光抛异常() throws IOException {
        FakeCompactProvider client = new FakeCompactProvider();
        // 始终 PTL
        client.setDefault(List.of(new StreamEvent.Error("ptl",
                new PromptTooLongException(new RuntimeException("ptl")))));
        List<Message> msgs = List.of(user("u0"), assistant("a0"));
        ConversationManager conv = new ConversationManager();
        ContextCompactor.Input in = input(client, conv, 200000, ContextCompactor.TriggerKind.MANUAL);
        assertThrows(CompactException.class,
                () -> ContextCompactor.ptlRetry(in, msgs, new PromptTooLongException(new RuntimeException("first"))));
    }

    @Test
    void summarizeOnce提取摘要() throws IOException {
        FakeCompactProvider client = new FakeCompactProvider();
        client.enqueue(List.of(new StreamEvent.TextDelta("<summary>hello</summary>"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();
        ContextCompactor.Input in = input(client, conv, 200000, ContextCompactor.TriggerKind.MANUAL);
        String text = ContextCompactor.summarizeOnce(in, List.of(user("u")));
        assertEquals("hello", text);
        // 摘要请求 tools 为空
        assertEquals(1, client.summarizeCalls);
    }
}
