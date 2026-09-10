package com.cortex.agent;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 队员上下文（T16/T20）：spawn 时由 team 包构造闭包注入子 Agent，
 * Agent 包不直接依赖 mailbox（避免循环）；消息以轻量 {@link IncomingMessage} 传递。
 *
 * @param teamName    所属团队 sanitized 名
 * @param memberName  成员名
 * @param agentId     邮箱 id
 * @param readUnread  读未读闭包
 * @param markRead    批量标已读闭包
 */
public record TeammateContext(
        String teamName,
        String memberName,
        String agentId,
        Supplier<ReadUnreadView> readUnread,
        Consumer<List<Integer>> markRead) {

    /** 未读视图：indices 与 messages 一一对应。 */
    public record ReadUnreadView(List<Integer> indices, List<IncomingMessage> messages) {

        public ReadUnreadView {
            indices = indices == null ? List.of() : indices;
            messages = messages == null ? List.of() : messages;
        }

        public boolean isEmpty() {
            return messages.isEmpty();
        }
    }

    /**
     * 轻量消息（T20）：只带 Loop 注入需要的字段。
     *
     * @param approve  plan_approval_response 的裁决（其他类型为 null）
     * @param feedback plan 驳回反馈
     */
    public record IncomingMessage(
            String from,
            String type,
            String summary,
            String content,
            Boolean approve,
            String feedback) {}
}
