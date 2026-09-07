package com.cortex.llm;

/**
 * 协议无关地承载模型发起的一次工具调用（流式分片拼接完成后的形态）。
 */
public record ToolCall(
        String id,      // provider 侧调用 id，回灌结果时按此配对
        String name,    // 工具名，注册中心按名查找
        String args     // 拼接完成的 JSON 参数（原始字符串）
) {}
