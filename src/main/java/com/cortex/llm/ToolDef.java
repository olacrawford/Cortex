package com.cortex.llm;

import java.util.Map;

/**
 * 协议无关的工具定义：注册中心导出、随请求发送给模型，由各协议适配器转换为 SDK 类型。
 */
public record ToolDef(
        String name,                    // 工具名，如 read_file
        String description,             // 给模型的用途说明
        Map<String, Object> inputSchema // 完整 JSON Schema 对象：type/properties/required
) {}
