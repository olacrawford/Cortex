package com.cortex.tui;

import com.cortex.task.BackgroundTask;
import com.cortex.task.Status;

/**
 * 后台任务通知渲染（F19/T26）：任务完成后拼 &lt;task-notification&gt; 块注入主对话 reminder 区
 * （只对模型可见，N7——不进用户视窗、不占工具调用配额）。
 */
final class Tasks {

    private Tasks() {}

    /** BackgroundTask → &lt;task-notification&gt; 文本（含最终结果或错误描述）。 */
    static String buildTaskNotification(BackgroundTask bt) {
        StringBuilder sb = new StringBuilder();
        sb.append("<task-notification>\n");
        sb.append("Task ").append(bt.id())
                .append(bt.name() == null ? "" : " (name=\"" + bt.name() + "\")")
                .append(": ").append(bt.status().wireName()).append("\n");
        if (bt.status() == Status.FAILED) {
            sb.append("Error: ").append(bt.errorMessage()).append("\n");
        } else if (bt.status() == Status.CANCELLED) {
            sb.append("The task was cancelled before completion.\n");
        } else {
            String r = bt.result();
            sb.append("Result: ").append(r == null || r.isBlank() ? "（无文本输出）" : r.strip()).append("\n");
        }
        sb.append("</task-notification>");
        return sb.toString();
    }
}
