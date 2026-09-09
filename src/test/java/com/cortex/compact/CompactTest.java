package com.cortex.compact;

import com.cortex.compact.state.AutoCompactTrackingState;
import com.cortex.compact.state.ContentReplacementState;
import com.cortex.compact.state.SessionContext;
import com.cortex.compact.support.FakeCompactProvider;
import com.cortex.conversation.ConversationManager;
import com.cortex.conversation.Message;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompactTest {

    @TempDir
    Path tempDir;

    private SessionContext session() throws IOException {
        return SessionContext.create(tempDir);
    }

    private static Message user(String s) { return new Message(Message.Role.USER, s); }

    private ContextCompactor.Input input(FakeCompactProvider client, ConversationManager conv,
                                         ContentReplacementState replacement, Recovery.RecoveryState recovery,
                                         AutoCompactTrackingState auto, SessionContext session,
                                         int cw, long est, ContextCompactor.TriggerKind trigger) {
        return new ContextCompactor.Input(conv, client, cw, List.of(), replacement, recovery, auto,
                session, 0, 0, est, trigger);
    }

    private static void fillBig(ConversationManager conv, int chars) {
        conv.addUserMessage("x".repeat(chars));
        conv.addAssistantMessage("y".repeat(chars));
    }

    @Test
    void 自动触发阈值时执行摘要() throws Exception {
        SessionContext session = session();
        ConversationManager conv = new ConversationManager();
        // 构造超出 50000-20000-13000=17000 token 的对话（8 条消息，足够让近期原文成为真子集）
        fillBig(conv, 20000);
        fillBig(conv, 20000);
        fillBig(conv, 20000);
        fillBig(conv, 20000);
        long est = Token.estimateTokens(0, conv.getMessages(), 0);

        FakeCompactProvider client = new FakeCompactProvider();
        client.enqueue(List.of(new StreamEvent.TextDelta("<summary>摘要内容</summary>"),
                new StreamEvent.StreamEnd("stop", 0, 0)));

        ContentReplacementState replacement = new ContentReplacementState();
        AutoCompactTrackingState auto = new AutoCompactTrackingState();
        ContextCompactor.Input in = input(client, conv, replacement, new Recovery.RecoveryState(), auto,
                session, 50000, est, ContextCompactor.TriggerKind.AUTO);
        ContextCompactor.CompactMsg msg = ContextCompactor.manage(in);

        assertEquals(1, client.summarizeCalls);
        assertTrue(msg.afterTokens() < msg.beforeTokens());
        // conversation 被替换为「摘要 + 恢复 + 近期原文」
        List<Message> msgs = conv.getMessages();
        assertTrue(msgs.get(0).getContent().contains("## 历史会话摘要"));
        assertTrue(msgs.get(0).getContent().contains("摘要内容"));
    }

    @Test
    void 自动低于阈值跳过摘要() throws Exception {
        SessionContext session = session();
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hi");

        FakeCompactProvider client = new FakeCompactProvider();
        ContentReplacementState replacement = new ContentReplacementState();
        AutoCompactTrackingState auto = new AutoCompactTrackingState();
        ContextCompactor.Input in = input(client, conv, replacement, new Recovery.RecoveryState(), auto,
                session, 50000, 100, ContextCompactor.TriggerKind.AUTO);
        ContextCompactor.CompactMsg msg = ContextCompactor.manage(in);

        assertEquals(0, client.summarizeCalls);
        assertEquals(100, msg.beforeTokens());
    }

    @Test
    void 自动熔断后跳过摘要() throws Exception {
        SessionContext session = session();
        ConversationManager conv = new ConversationManager();
        fillBig(conv, 20000);
        fillBig(conv, 20000);
        long est = Token.estimateTokens(0, conv.getMessages(), 0);

        FakeCompactProvider client = new FakeCompactProvider();
        AutoCompactTrackingState auto = new AutoCompactTrackingState();
        auto.recordFailure(); auto.recordFailure(); auto.recordFailure(); // 触发熔断

        ContentReplacementState replacement = new ContentReplacementState();
        ContextCompactor.Input in = input(client, conv, replacement, new Recovery.RecoveryState(), auto,
                session, 50000, est, ContextCompactor.TriggerKind.AUTO);
        ContextCompactor.CompactMsg msg = ContextCompactor.manage(in);

        assertEquals(0, client.summarizeCalls);
    }

    @Test
    void 手动跳过阈值与熔断() throws Exception {
        SessionContext session = session();
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hi");

        FakeCompactProvider client = new FakeCompactProvider();
        client.enqueue(List.of(new StreamEvent.TextDelta("<summary>手动摘要</summary>"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        AutoCompactTrackingState auto = new AutoCompactTrackingState();
        auto.recordFailure(); auto.recordFailure(); auto.recordFailure();

        ContentReplacementState replacement = new ContentReplacementState();
        ContextCompactor.Input in = input(client, conv, replacement, new Recovery.RecoveryState(), auto,
                session, 50000, 100, ContextCompactor.TriggerKind.MANUAL);
        ContextCompactor.CompactMsg msg = ContextCompactor.manage(in);

        assertEquals(1, client.summarizeCalls);
        assertTrue(conv.getMessages().get(0).getContent().contains("手动摘要"));
    }

    @Test
    void 紧急先跑第1层再摘要() throws Exception {
        SessionContext session = session();
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hi");
        // 一条 60000 字节工具结果触发 layer1 落盘
        conv.addToolResults(List.of(new ToolResult("c1", "x".repeat(60000), false)));

        FakeCompactProvider client = new FakeCompactProvider();
        client.enqueue(List.of(new StreamEvent.TextDelta("<summary>紧急摘要</summary>"),
                new StreamEvent.StreamEnd("stop", 0, 0)));

        ContentReplacementState replacement = new ContentReplacementState();
        ContextCompactor.Input in = input(client, conv, replacement, new Recovery.RecoveryState(),
                new AutoCompactTrackingState(), session, 50000, 100, ContextCompactor.TriggerKind.EMERGENCY);
        ContextCompactor.CompactMsg msg = ContextCompactor.manage(in);

        // layer1 落盘文件存在
        assertTrue(Files.exists(session.spillDir().resolve("c1")));
        assertEquals(1, client.summarizeCalls);
    }

    @Test
    void 自动失败累计熔断计数() throws Exception {
        SessionContext session = session();
        ConversationManager conv = new ConversationManager();
        fillBig(conv, 20000);
        fillBig(conv, 20000);
        long est = Token.estimateTokens(0, conv.getMessages(), 0);

        // 摘要请求返回普通错误 → autoCompact 失败 → 计数 +1
        FakeCompactProvider client = new FakeCompactProvider();
        client.enqueue(List.of(new StreamEvent.Error("boom")));

        AutoCompactTrackingState auto = new AutoCompactTrackingState();
        ContentReplacementState replacement = new ContentReplacementState();
        ContextCompactor.Input in = input(client, conv, replacement, new Recovery.RecoveryState(), auto,
                session, 50000, est, ContextCompactor.TriggerKind.AUTO);
        assertThrows(CompactException.class, () -> ContextCompactor.manage(in));
        // 连续 3 次失败后熔断
        auto.recordFailure();
        auto.recordFailure();
        assertTrue(auto.tripped());
    }
}
