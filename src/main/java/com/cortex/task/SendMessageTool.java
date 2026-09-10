package com.cortex.task;

import com.cortex.tool.Result;
import com.cortex.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * SendMessage 工具（F20/G8）：按 name 找到已跑完、仍存活的后台子 Agent，
 * 把 message 作为新 user 消息续派并重新跑动；跑完经 &lt;task-notification&gt; 回灌主对话。
 */
public final class SendMessageTool implements Tool {

    private final Manager manager;
    private final TeamCollaboration collaboration; // 阶段14：队员上下文时分派到 Team 语义（可空）

    public SendMessageTool(Manager manager) {
        this(manager, null);
    }

    public SendMessageTool(Manager manager, TeamCollaboration collaboration) {
        this.manager = manager;
        this.collaboration = collaboration;
    }

    @Override
    public String name() {
        return "SendMessage";
    }

    @Override
    public String description() {
        return "给一个已完成后仍存活的后台子 Agent（按 name 定位）续派新任务；它会带着原上下文继续执行并再次回报。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string", "description", "启动任务时指定的 name"),
                        "to", Map.of("type", "string", "description", "Team 队员场景的收件人（队员名/agentId/*），与 name 二选一"),
                        "message", Map.of("type", "string", "description", "续派的新任务消息"),
                        "summary", Map.of("type", "string", "description", "Team 场景：5-10 词消息摘要"),
                        "type", Map.of("type", "string", "description", "Team 场景：text/shutdown_request/shutdown_response/plan_approval_response")),
                "required", List.of("message"));
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
        String name = TaskGetTool.stringArg(argsJson, "name");
        String message = TaskGetTool.stringArg(argsJson, "message");
        String type = TaskGetTool.stringArg(argsJson, "type");
        String to = TaskGetTool.stringArg(argsJson, "to");
        String summary = TaskGetTool.stringArg(argsJson, "summary");
        // 阶段14 schema 扩展：to 作为 name 的别名（Team 寻址用）
        String target = name != null ? name : to;
        if (target == null || target.isBlank()) {
            return Result.error("缺少必填参数 name");
        }
        if (message == null || message.isBlank()) {
            return Result.error("缺少必填参数 message");
        }
        // Team 队员上下文：走 Team 邮箱 + 寻址 + 续派（F34/T31）
        if (ctx.teammate() instanceof com.cortex.agent.TeammateContext tc && collaboration != null) {
            try {
                return Result.ok(collaboration.sendMessage(tc.teamName(), tc.memberName(),
                        target.trim(), summary, message, type, null, null));
            } catch (Exception e) {
                return Result.error(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }
        // ch13 默认流程：后台子 Agent 续派
        if ("*".equals(target.trim())) {
            return Result.error("广播（to=*）仅 Team 队员可用");
        }
        try {
            String id = manager.sendMessage(target.trim(), message);
            return Result.ok("{\"task_id\":\"" + id + "\",\"status\":\"resumed\"}");
        } catch (IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }
}
