package com.cortex.tool;

import java.util.Map;

/**
 * 统一工具抽象（F1）：每个工具向模型暴露名称、描述、参数 Schema，并提供执行入口。
 * 失败一律以 {@link Result#error} 返回，绝不抛异常打断会话（F9/N4）。
 */
public interface Tool {

    /** 工具名（模型看到并调用的名字），如 read_file。 */
    String name();

    /** 给模型的用途说明。 */
    String description();

    /** 手写 JSON Schema（type/properties/required），随请求发送给模型。 */
    Map<String, Object> inputSchema();

    /**
     * 执行工具。
     *
     * @param argsJson 模型给出的 JSON 参数（注册中心已把 null/空串归一为 "{}"）
     */
    Result execute(String argsJson);
}
