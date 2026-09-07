package com.cortex.llm;

/**
 * 协议无关地承载一次工具执行结果（回灌进对话历史的形态）。
 */
public record ToolResult(
        String toolCallId,  // 对应 ToolCall.id
        String content,     // 执行产出（成功内容或结构化错误文本）
        boolean isError     // 是否为错误结果（错误也回灌，让模型自行调整）
) {}
