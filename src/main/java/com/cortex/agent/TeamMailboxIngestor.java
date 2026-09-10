package com.cortex.agent;

/**
 * 队员邮箱摄取（T20/T32/F41/F42/F43/F44）：runToCompletion 每轮请求 LLM 之前调用——
 * 读未读 → 构造 {@code <incoming-messages>} reminder 进 pendingReminders → 批量标已读；
 * plan_approval_response(approve=true) 额外把队员权限模式切回 DEFAULT。
 */
public final class TeamMailboxIngestor {

    private TeamMailboxIngestor() {}

    /** 摄取未读消息；有消息时返回 reminder 文本（已入 pendingReminders），无消息返回 null。 */
    public static String ingest(Agent agent, TeammateContext tc) {
        TeammateContext.ReadUnreadView view;
        try {
            view = tc.readUnread().get();
        } catch (Exception e) {
            return null; // 邮箱读取失败不阻塞 Loop
        }
        if (view == null || view.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<incoming-messages>\n")
                .append("收到 ").append(view.messages().size()).append(" 条新消息:\n");
        boolean approved = false;
        String rejectFeedback = null;
        int i = 1;
        for (TeammateContext.IncomingMessage m : view.messages()) {
            sb.append("[").append(i++).append("] 来自 ").append(m.from())
                    .append("(type=").append(m.type()).append("): ").append(m.summary()).append("\n");
            String content = m.content() == null ? "" : m.content();
            sb.append("    ").append(content, 0, Math.min(200, content.length())).append("\n");
            if ("plan_approval_response".equals(m.type())) {
                if (Boolean.TRUE.equals(m.approve())) {
                    approved = true;
                } else {
                    rejectFeedback = m.feedback() == null ? "" : m.feedback();
                }
            }
        }
        sb.append("</incoming-messages>");
        if (approved) {
            agent.setPermissionMode(com.cortex.permission.Mode.DEFAULT);
            sb.append("Lead 已批准计划，权限模式已切到 default，可执行计划。\n");
        } else if (rejectFeedback != null) {
            sb.append("Lead 驳回了计划，反馈：").append(rejectFeedback).append("。请调整后重新提交。\n");
        }
        agent.runtime().appendReminders(java.util.List.of(sb.toString()));
        try {
            tc.markRead().accept(view.indices());
        } catch (Exception ignored) {
            // 标记失败下轮会重复注入，可接受
        }
        return sb.toString();
    }
}
