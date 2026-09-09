package com.cortex.tool;

import com.cortex.llm.ToolDef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 注册中心（F1/F3/F5）：集中登记工具、按名查找、按序导出工具定义、按名执行。
 * 工具集固定为六个核心工具，不支持运行时增删（spec「不做的事」）。
 */
public final class ToolRegistry {

    /** 内置默认执行超时（不可配置，N1）。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final List<String> order = new ArrayList<>();
    private final Map<String, Tool> tools = new HashMap<>();

    /** 登记一个工具；重名视为程序错误，直接抛出。 */
    public void register(Tool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalArgumentException("工具已注册: " + tool.name());
        }
        order.add(tool.name());
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 按注册顺序导出全部工具定义（随请求发送给模型，AC1）。 */
    public List<ToolDef> definitions() {
        List<ToolDef> defs = new ArrayList<>();
        for (String name : order) {
            Tool t = tools.get(name);
            defs.add(new ToolDef(t.name(), t.description(), t.inputSchema()));
        }
        return List.copyOf(defs);
    }

    /** Plan Mode 用：仅导出只读工具定义，保持注册顺序。 */
    public List<ToolDef> readOnlyDefinitions() {
        List<ToolDef> defs = new ArrayList<>();
        for (String name : order) {
            Tool t = tools.get(name);
            if (t.readOnly()) {
                defs.add(new ToolDef(t.name(), t.description(), t.inputSchema()));
            }
        }
        return List.copyOf(defs);
    }

    /** 分批判定：只读工具可与相邻只读工具并发；未知工具返回 false（按串行处理）。 */
    public boolean isReadOnly(String name) {
        Tool t = tools.get(name);
        return t != null && t.readOnly();
    }

    /** 已注册工具数量（/status 用，O(1)）。 */
    public int count() {
        return tools.size();
    }

    /**
     * 按名执行工具。未知工具、参数解析失败、执行异常一律返回 error 结果而非抛异常。
     *
     * @param argsJson 模型给出的 JSON 参数；null/空串归一为 "{}"
     */
    public Result execute(String name, String argsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return Result.error("未知工具: " + name);
        }
        String args = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
        try {
            return tool.execute(args);
        } catch (Exception e) {
            // 双保险：工具内部已兜底，这里防的是工具实现本身的未预期异常
            return Result.error("工具执行异常: " + e.getMessage());
        }
    }

    /** 构造内置六个核心工具的注册中心。 */
    public static ToolRegistry createDefault() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new BashTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        return registry;
    }
}
