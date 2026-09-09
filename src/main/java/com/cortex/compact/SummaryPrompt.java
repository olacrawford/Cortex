package com.cortex.compact;

import com.cortex.conversation.Message;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;

import java.util.List;

/**
 * 摘要 Prompt 模板与解析。
 * 摘要请求不传任何工具定义（F8）；模型被明确禁止调用工具（F8）；
 * 分两阶段输出（F9）：先在 {@code <analysis>} 写分析草稿（丢弃），再在 {@code <summary>}
 * 写正式摘要（固定 9 部分结构，F10）。
 */
public final class SummaryPrompt {

    private SummaryPrompt() {}

    /** 9 个小节标题，固定字面字符串，便于 extractSummary 解析与单测匹配。 */
    public static final String[] SECTION_TITLES = {
            "## 1 主要请求和意图",
            "## 2 关键技术概念",
            "## 3 文件和代码段",
            "## 4 错误和修复",
            "## 5 问题解决过程",
            "## 6 所有用户消息原文",
            "## 7 待办任务",
            "## 8 当前工作(最详细)",
            "## 9 可能的下一步"
    };

    private static final String SUMMARY_INSTRUCTION = """
            You are summarizing a coding agent conversation. Output in two phases.

            <analysis>
            (在这里写分析草稿,会被丢弃)
            </analysis>

            <summary>
            ## 1 主要请求和意图
            ## 2 关键技术概念
            ## 3 文件和代码段
            ## 4 错误和修复
            ## 5 问题解决过程
            ## 6 所有用户消息原文
            ## 7 待办任务
            ## 8 当前工作(最详细)
            ## 9 可能的下一步
            </summary>

            不要调用任何工具,输出纯文本。
            """;

    /** 构造摘要请求消息：返回长度为 1 的列表，仅一条 user 消息。 */
    public static List<Message> buildSummaryPrompt(List<Message> msgs) {
        String content = SUMMARY_INSTRUCTION + "\n\n[conversation]\n" + serializeConversation(msgs);
        return List.of(new Message(Message.Role.USER, content));
    }

    /** 把对话扁平化成可读文本（不暴露 ToolCall.input 原始 JSON 之外的敏感信息）。纯函数。 */
    static String serializeConversation(List<Message> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Message m : msgs) {
            switch (m.getRole()) {
                case USER -> sb.append("user: ").append(nullSafe(m.getContent())).append('\n');
                case ASSISTANT -> {
                    sb.append("assistant: ").append(nullSafe(m.getContent())).append('\n');
                    for (ToolCall c : m.getToolCalls()) {
                        sb.append("[call ").append(c.name()).append(" id=").append(c.id())
                                .append(" args=").append(c.args()).append("]\n");
                    }
                }
                case TOOL -> {
                    for (ToolResult r : m.getToolResults()) {
                        sb.append("[result id=").append(r.toolCallId())
                                .append(" isError=").append(r.isError())
                                .append("] ").append(nullSafe(r.content())).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 从模型返回的整段文本里抠出最后一对 {@code <summary>...</summary>} 之间的正文；找不到时返回原文。 */
    public static String extractSummary(String raw) {
        if (raw == null) {
            return "";
        }
        int open = raw.lastIndexOf("<summary>");
        int close = raw.lastIndexOf("</summary>");
        if (open >= 0 && close > open) {
            return raw.substring(open + "<summary>".length(), close).strip();
        }
        return raw.strip();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
