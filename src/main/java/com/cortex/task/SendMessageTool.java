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

    public SendMessageTool(Manager manager) {
        this.manager = manager;
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
                        "message", Map.of("type", "string", "description", "续派的新任务消息")),
                "required", List.of("name", "message"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(String argsJson) {
        String name = TaskGetTool.stringArg(argsJson, "name");
        String message = TaskGetTool.stringArg(argsJson, "message");
        if (name == null || name.isBlank()) {
            return Result.error("缺少必填参数 name");
        }
        if (message == null || message.isBlank()) {
            return Result.error("缺少必填参数 message");
        }
        try {
            String id = manager.sendMessage(name.trim(), message);
            return Result.ok("{\"task_id\":\"" + id + "\",\"status\":\"resumed\"}");
        } catch (IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }
}
