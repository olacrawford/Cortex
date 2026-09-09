package com.cortex.task;

import com.cortex.tool.Result;
import com.cortex.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * TaskStop 工具（F20）：触发后台任务取消（置取消旗标）；终态以 TaskGet 查询为准。
 */
public final class TaskStopTool implements Tool {

    private final Manager manager;

    public TaskStopTool(Manager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskStop";
    }

    @Override
    public String description() {
        return "请求取消一个后台子 Agent 任务（异步生效，任务最终状态为 cancelled）。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task_id", Map.of("type", "string", "description", "任务 ID")),
                "required", List.of("task_id"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(String argsJson) {
        String id = TaskGetTool.stringArg(argsJson, "task_id");
        if (id == null || id.isBlank()) {
            return Result.error("缺少必填参数 task_id");
        }
        if (!manager.stop(id.trim())) {
            return Result.error("未知 task_id: " + id);
        }
        return Result.ok("{\"task_id\":\"" + id.trim() + "\",\"status\":\"cancellation_requested\"}");
    }
}
