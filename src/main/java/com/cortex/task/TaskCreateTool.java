package com.cortex.task;

import com.cortex.agent.TeammateContext;
import com.cortex.tool.Result;
import com.cortex.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * TaskCreate（F26/G6）：Team 共享任务列表新建任务——仅 Team 队员上下文可用
 * （主 Agent 与普通子 Agent 经过滤不可见，AC9）。
 */
public final class TaskCreateTool implements Tool {

    private final TeamCollaboration collaboration;

    public TaskCreateTool(TeamCollaboration collaboration) {
        this.collaboration = collaboration;
    }

    @Override
    public String name() {
        return "TaskCreate";
    }

    @Override
    public String description() {
        return "在团队共享任务列表中新建任务（仅团队成员可用），返回 taskId；可用 blockedBy 声明依赖。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string", "description", "任务标题"),
                        "description", Map.of("type", "string", "description", "任务详情"),
                        "assignee", Map.of("type", "string", "description", "指派给哪位队员（可空）"),
                        "blockedBy", Map.of("type", "array",
                                "items", Map.of("type", "string"),
                                "description", "被哪些 taskId 阻塞")),
                "required", List.of("title"));
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
            return Result.error("TaskCreate 仅团队成员可用");
        }
        String title = TaskGetTool.stringArg(argsJson, "title");
        if (title == null || title.isBlank()) {
            return Result.error("缺少必填参数 title");
        }
        try {
            return Result.ok(collaboration.taskCreate(tc.teamName(), title,
                    TaskGetTool.stringArg(argsJson, "description"),
                    TaskGetTool.stringArg(argsJson, "assignee"),
                    stringList(argsJson, "blockedBy")));
        } catch (Exception e) {
            return Result.error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    static java.util.List<String> stringList(String argsJson, String key) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            if (!node.has(key) || !node.get(key).isArray()) {
                return List.of();
            }
            List<String> out = new java.util.ArrayList<>();
            node.get(key).forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
