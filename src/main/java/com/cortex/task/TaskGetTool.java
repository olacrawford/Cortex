package com.cortex.task;

import com.cortex.tool.Result;
import com.cortex.tool.Tool;

import java.util.Map;
import java.util.TreeMap;

/**
 * TaskGet 工具（F20）：返回指定任务的完整状态（含 result / err / usage / 起止时间）。
 */
public final class TaskGetTool implements Tool {

    private final Manager manager;
    private final TeamCollaboration collaboration; // 阶段14：队员上下文分派（可空）

    public TaskGetTool(Manager manager) {
        this(manager, null);
    }

    public TaskGetTool(Manager manager, TeamCollaboration collaboration) {
        this.manager = manager;
        this.collaboration = collaboration;
    }

    @Override
    public String name() {
        return "TaskGet";
    }

    @Override
    public String description() {
        return "查询单个后台子 Agent 任务的完整状态（status/result/err/usage/起止时间等）。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task_id", Map.of("type", "string", "description", "任务 ID（TaskList 返回的 id）")),
                "required", java.util.List.of("task_id"));
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(String argsJson) {
        return execute(com.cortex.tool.ToolContext.EMPTY, argsJson);
    }

    @Override
    public Result execute(com.cortex.tool.ToolContext ctx, String argsJson) {
        // Team 队员上下文：共享任务详情（F27）
        if (ctx.teammate() instanceof com.cortex.agent.TeammateContext tc && collaboration != null) {
            String teamTaskId = stringArg(argsJson, "task_id");
            String altId = stringArg(argsJson, "taskId");
            String id = teamTaskId != null ? teamTaskId : altId;
            try {
                return Result.ok(collaboration.taskGet(tc.teamName(), id));
            } catch (Exception e) {
                return Result.error(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }
        String id = stringArg(argsJson, "task_id");
        if (id == null || id.isBlank()) {
            return Result.error("缺少必填参数 task_id");
        }
        BackgroundTask t = manager.get(id.trim()).orElse(null);
        if (t == null) {
            return Result.error("未知 task_id: " + id);
        }
        Map<String, Object> item = new TreeMap<>();
        item.put("id", t.id());
        item.put("name", t.name() == null ? "" : t.name());
        item.put("status", t.status().wireName());
        item.put("tool_count", t.toolCount());
        item.put("last_activity", t.lastActivity());
        item.put("start_time", t.startTime().toString());
        item.put("end_time", t.endTime() == null ? "" : t.endTime().toString());
        item.put("usage", Map.of(
                "input", t.usage().inputTokens(),
                "output", t.usage().outputTokens(),
                "cache_write", t.usage().cacheWrite(),
                "cache_read", t.usage().cacheRead()));
        if (t.status() == Status.FAILED) {
            item.put("error", t.errorMessage());
        } else {
            item.put("result", t.result() == null ? "" : t.result());
        }
        return Result.ok(TaskListTool.jsonOf(item));
    }

    static String stringArg(String argsJson, String key) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return node.has(key) && node.get(key).isTextual() ? node.get(key).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
