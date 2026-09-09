package com.cortex.compact;

import com.cortex.conversation.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryPromptTest {

    @Test
    void 摘要提示含9部分与两阶段标签() {
        List<Message> prompt = SummaryPrompt.buildSummaryPrompt(
                List.of(new Message(Message.Role.USER, "hello")));
        assertEquals(1, prompt.size());
        assertEquals(Message.Role.USER, prompt.get(0).getRole());
        String content = prompt.get(0).getContent();
        assertTrue(content.contains("<analysis>"));
        assertTrue(content.contains("<summary>"));
        assertTrue(content.contains("不要调用任何工具"));
        for (String section : SummaryPrompt.SECTION_TITLES) {
            assertTrue(content.contains(section), "缺少小节: " + section);
        }
    }

    @Test
    void 序列化确定性() {
        List<Message> msgs = List.of(
                new Message(Message.Role.USER, "你好"),
                new Message(Message.Role.ASSISTANT, "收到", List.of(
                        new com.cortex.llm.ToolCall("c1", "read_file", "{\"path\":\"a\"}")), List.of()),
                new Message(Message.Role.TOOL, "", List.of(),
                        List.of(new com.cortex.llm.ToolResult("c1", "内容", false))));
        String s1 = SummaryPrompt.serializeConversation(msgs);
        String s2 = SummaryPrompt.serializeConversation(msgs);
        assertEquals(s1, s2);
        assertTrue(s1.contains("[call read_file id=c1"));
        assertTrue(s1.contains("[result id=c1 isError=false]"));
    }

    @Test
    void 提取summary() {
        assertEquals("xx", SummaryPrompt.extractSummary("abc<summary>xx</summary>yy"));
        // 缺失时返回原文
        String raw = "no tags";
        assertEquals(raw, SummaryPrompt.extractSummary(raw));
        // 尾部有其余内容时被裁剪
        assertEquals("inner", SummaryPrompt.extractSummary("<summary>inner</summary>tail"));
    }
}
