package com.cortex.llm;

import java.util.List;

/**
 * 一次流式请求的全部入参（协议无关）：
 * 持久对话历史、本轮工具集、两段系统提示、本轮 system-reminder（已含标签；空=不注入）。
 * reminder 每轮动态构造、不写入持久历史（F6/N3）。
 */
public record Request(
        List<com.cortex.conversation.Message> messages,
        List<ToolDef> tools,
        SystemPrompt system,
        String reminder) {}
