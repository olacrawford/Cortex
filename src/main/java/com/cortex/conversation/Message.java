package com.cortex.conversation;

import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;

import java.util.List;

/**
 * 对话历史中的一条消息。
 * 普通回合只携带 role + content；
 * assistant 工具调用回合携带 toolCalls（模型请求的工具调用）；
 * TOOL 回合携带 toolResults（工具执行结果，一条消息可含多个）。
 */
public class Message {
    public enum Role {
        USER,
        ASSISTANT,
        /** 携带工具执行结果的回合。 */
        TOOL
    }

    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final List<ToolResult> toolResults;

    public Message(Role role, String content) {
        this(role, content, List.of(), List.of());
    }

    public Message(Role role, String content, List<ToolCall> toolCalls, List<ToolResult> toolResults) {
        this.role = role;
        this.content = content;
        this.toolCalls = List.copyOf(toolCalls);
        this.toolResults = List.copyOf(toolResults);
    }

    public Role getRole() { return role; }
    public String getContent() { return content; }
    /** 仅 assistant 工具调用回合非空。 */
    public List<ToolCall> getToolCalls() { return toolCalls; }
    /** 仅 TOOL 回合非空。 */
    public List<ToolResult> getToolResults() { return toolResults; }
}
