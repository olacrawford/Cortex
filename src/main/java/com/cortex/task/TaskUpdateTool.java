package com.cortex.task;

import com.cortex.agent.TeammateContext;
import com.cortex.tool.Result;
import com.cortex.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * TaskUpdate（F29/G6）：更新共享任务（状态/指派/依赖），依赖关系双向维护——仅 Team 队员可用。
 */
public final class TaskUpdateTool implements Tool {

    private final TeamCollaboration collaboration;

    public TaskUpdateTool(TeamCollaboration collaboration) {
        this.collaboration = collaboration;
    }

    @Override
    public String name() {
        return "TaskUpdate";
    }

    @Override
    public String description() {
        return "更新团队共享任务：标题/详情/状态（pending/in_progress/completed/blocked）/指派人，"
                + "或调整依赖（addBlockedBy/addBlocks 双向维护）。仅团队成员可用。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "taskId", Map.of("type", "string", "description", "任务 id"),
                        "title", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "status", Map.of("type", "string",
                                "enum", List.of("pending", "in_progress", "completed", "blocked")),
                        "assignee", Map.of("type", "string"),
                        "addBlocks", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "本任务阻塞这些 taskId"),
                        "addBlockedBy", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "这些 taskId 阻塞本任务"),
                        "removeBlocks", Map.of("type", "array", "items", Map.of("type", "string")),
                        "removeBlockedBy", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required", List.of("taskId"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(String argsJson) {
        return execute(com.cortex.tool.ToolContext.EMPTY, argsJson);
    }

    @Override
    public Result execute(com.cortex.tool.ToolContext ctx, String argsJson) {
        if (!(ctx.teammate() instanceof TeammateContext tc) || collaboration == null) {
            return Result.error("TaskUpdate 仅团队成员可用");
        }
        String taskId = TaskGetTool.stringArg(argsJson, "taskId");
        if (taskId == null || taskId.isBlank()) {
            return Result.error("缺少必填参数 taskId");
        }
        try {
            return Result.ok(collaboration.taskUpdate(tc.teamName(), taskId,
                    TaskGetTool.stringArg(argsJson, "title"),
                    TaskGetTool.stringArg(argsJson, "description"),
                    TaskGetTool.stringArg(argsJson, "status"),
                    TaskGetTool.stringArg(argsJson, "assignee"),
                    TaskCreateTool.stringList(argsJson, "addBlocks"),
                    TaskCreateTool.stringList(argsJson, "addBlockedBy"),
                    TaskCreateTool.stringList(argsJson, "removeBlocks"),
                    TaskCreateTool.stringList(argsJson, "removeBlockedBy")));
        } catch (Exception e) {
            return Result.error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
