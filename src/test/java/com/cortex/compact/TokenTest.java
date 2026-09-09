package com.cortex.compact;

import com.cortex.conversation.Message;
import com.cortex.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenTest {

    @Test
    void usageAnchor把四字段相加() {
        assertEquals(1000 + 500 + 200 + 300, Token.usageAnchor(new Usage(1000, 500, 200, 300)));
        assertEquals(0, Token.usageAnchor(new Usage(0, 0, 0, 0)));
    }

    @Test
    void estimateTokens锚点加增量() {
        // anchor=1000, anchorMsgLen=1 → 只算 m2 的字符增量
        Message m2 = new Message(Message.Role.USER, "x".repeat(700)); // 700 字节 → ceil(700/3.5)=200
        long est = Token.estimateTokens(1000, List.of(
                new Message(Message.Role.USER, "hello"), m2), 1);
        assertEquals(1000 + 200, est);
    }

    @Test
    void estimateTokens纯字符退化() {
        Message m = new Message(Message.Role.USER, "x".repeat(700));
        long est = Token.estimateTokens(0, List.of(m), 0);
        assertEquals(200, est);
    }

    @Test
    void estimateTokens空返回0() {
        assertEquals(0, Token.estimateTokens(0, List.of(), 0));
    }

    @Test
    void estimateTokens大锚点不溢出() {
        Message m = new Message(Message.Role.USER, "x".repeat(700));
        long est = Token.estimateTokens(2_000_000_000L, List.of(m), 0);
        assertTrue(est > 2_000_000_000L);
    }

    @Test
    void estimateTokens统计工具结果与调用() {
        Message tool = new Message(Message.Role.TOOL, "", List.of(),
                List.of(new com.cortex.llm.ToolResult("c1", "y".repeat(700), false)));
        long est = Token.estimateTokens(0, List.of(tool), 0);
        assertEquals(200, est);
    }
}
