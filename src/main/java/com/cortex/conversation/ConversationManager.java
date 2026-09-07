package com.cortex.conversation;

import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话历史管理：维护消息序列，供 LLM 请求组装上下文。
 * 线程约定：一轮对话内由提交线程与 Agent 虚拟线程先后独占，不做并发保护。
 */
public class ConversationManager {

    private final List<Message> messages = new ArrayList<>();

    public void addUserMessage(String text) {
        messages.add(new Message(Message.Role.USER, text));
    }

    public void addAssistantMessage(String text) {
        messages.add(new Message(Message.Role.ASSISTANT, text));
    }

    /** 追加一个 assistant 工具调用回合（可同时携带 preamble 文本）。 */
    public void addAssistantWithToolCalls(String text, List<ToolCall> calls) {
        messages.add(new Message(Message.Role.ASSISTANT, text, calls, List.of()));
    }

    /** 追加一个工具结果回合（一条消息可携带多个结果）。 */
    public void addToolResults(List<ToolResult> results) {
        messages.add(new Message(Message.Role.TOOL, "", List.of(), results));
    }

    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public void clear() {
        messages.clear();
    }

    public int size() {
        return messages.size();
    }
}
