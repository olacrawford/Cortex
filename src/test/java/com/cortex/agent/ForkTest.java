package com.cortex.agent;

import com.cortex.conversation.Message;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fork 消息构造与上下文识别（F22-F24）。 */
class ForkTest {

    @Test
    void 空父对话_只追加boilerplate加task() {
        List<Message> out = Fork.buildForkedMessages(List.of(), "统计行数");
        assertEquals(1, out.size());
        assertEquals(Message.Role.USER, out.get(0).getRole());
        assertTrue(out.get(0).getContent().startsWith(Fork.FORK_BOILERPLATE_TAG));
        assertTrue(out.get(0).getContent().endsWith("统计行数"));
        assertTrue(out.get(0).getContent().contains(Fork.FORK_BOILERPLATE));
    }

    @Test
    void 完整配对的父对话_克隆后只追加一条user() {
        Message user = new Message(Message.Role.USER, "读文件");
        Message assistant = new Message(Message.Role.ASSISTANT, "好的",
                List.of(new ToolCall("c1", "read_file", "{}")), List.of());
        Message tool = new Message(Message.Role.TOOL, "", List.of(),
                List.of(new ToolResult("c1", "内容", false)));
        List<Message> parent = List.of(user, assistant, tool);

        List<Message> out = Fork.buildForkedMessages(parent, "新任务");
        assertEquals(4, out.size());
        // 无悬空 tool_use：不追加 placeholder TOOL 消息，仅末尾追加 user
        assertEquals(Message.Role.USER, out.get(3).getRole());
        assertTrue(out.get(3).getContent().endsWith("新任务"));
    }

    @Test
    void 末尾悬空tool_use补placeholder结果() {
        Message user = new Message(Message.Role.USER, "读文件");
        Message assistant = new Message(Message.Role.ASSISTANT, "",
                List.of(new ToolCall("c1", "read_file", "{}"),
                        new ToolCall("c2", "grep", "{}")), List.of());
        List<Message> parent = List.of(user, assistant);

        List<Message> out = Fork.buildForkedMessages(parent, "继续");
        // user → assistant(toolCalls) → TOOL(2 个 placeholder) → user(boilerplate+task)
        assertEquals(4, out.size());
        Message placeholders = out.get(2);
        assertEquals(Message.Role.TOOL, placeholders.getRole());
        assertEquals(2, placeholders.getToolResults().size());
        assertTrue(placeholders.getToolResults().stream().allMatch(ToolResult::isError));
        assertEquals("c1", placeholders.getToolResults().get(0).toolCallId());
        assertEquals("c2", placeholders.getToolResults().get(1).toolCallId());
        Message task = out.get(3);
        assertEquals(Message.Role.USER, task.getRole());
        assertTrue(task.getContent().startsWith(Fork.FORK_BOILERPLATE_TAG));
    }

    @Test
    void 深拷贝_修改克隆不影响父列表() {
        Message user = new Message(Message.Role.USER, "原始");
        List<Message> parent = List.of(user);
        List<Message> out = Fork.buildForkedMessages(parent, "t");
        assertEquals(2, out.size());
        assertEquals("原始", parent.get(0).getContent(), "父消息不被修改");
        assertNotSame(parent.get(0), out.get(0));
    }

    @Test
    void isForkContext识别标记() {
        assertTrue(Fork.isForkContext(Fork.buildForkedMessages(List.of(), "x")));
        assertFalse(Fork.isForkContext(List.of(new Message(Message.Role.USER, "普通对话"))));
        assertFalse(Fork.isForkContext(List.of()));
        assertFalse(Fork.isForkContext(null));
    }
}
