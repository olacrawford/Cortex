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

    /** true=只读工具：可与其他只读工具并发执行，Plan Mode 下唯一放行的类别。 */
    boolean readOnly();

    /**
     * 本工具的执行超时；默认全局 {@link ToolRegistry#DEFAULT_TIMEOUT}。
     * Agent 工具（前台子 Agent 可同步跑到自动切后台阈值）覆盖为更大值。
     */
    default java.time.Duration timeout() {
        return ToolRegistry.DEFAULT_TIMEOUT;
    }

    /**
     * 执行工具。
     *
     * @param ctx     执行上下文（explicit cwd 等；模型不可见，F16/F19）
     * @param argsJson 模型给出的 JSON 参数（注册中心已把 null/空串归一为 "{}"）
     */
    default Result execute(ToolContext ctx, String argsJson) {
        return execute(argsJson); // 未适配 ctx 的工具沿用无 ctx 路径
    }

    /**
     * 执行工具（无 explicit cwd 语义的原始入口）；已适配 ctx 的工具经由
     * {@link #execute(ToolContext, String)} 优先分发。
     *
     * @param argsJson 模型给出的 JSON 参数（注册中心已把 null/空串归一为 "{}"）
     */
    Result execute(String argsJson);
}
