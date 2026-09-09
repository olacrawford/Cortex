package com.cortex.task;

import com.cortex.tool.Result;
import com.cortex.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TaskList 工具（F20）：返回当前全部后台任务的简要列表（id/name/status/tool_count/last_activity）。
 * 只读元工具，模型可随时查询。
 */
public final class TaskListTool implements Tool {

    private final Manager manager;

    public TaskListTool(Manager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskList";
    }

    @Override
    public String description() {
        return "列出当前所有后台子 Agent 任务（id、name、status、tool_count、last_activity）。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(String argsJson) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (BackgroundTask t : manager.list()) {
            Map<String, Object> item = new java.util.TreeMap<>();
            item.put("id", t.id());
            item.put("name", t.name() == null ? "" : t.name());
            item.put("status", t.status().wireName());
            item.put("tool_count", t.toolCount());
            item.put("last_activity", t.lastActivity());
            items.add(item);
        }
        return Result.ok(toJson(items));
    }

    private static String toJson(List<Map<String, Object>> items) {
        if (items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(jsonOf(items.get(i)));
        }
        return sb.append("]").toString();
    }

    /** 简单扁平 Map → JSON 对象（嵌套 Map 递归、字符串转义、数值直出）；task 包内工具共用。 */
    static String jsonOf(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            sb.append(valueJson(e.getValue()));
        }
        return sb.append("}").toString();
    }

    private static String valueJson(Object v) {
        if (v instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) nested;
            return jsonOf(cast);
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return "\"" + String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }
}
