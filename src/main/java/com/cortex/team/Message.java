package com.cortex.team;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 邮箱消息（F32）。
 *
 * @param from      发件人成员名
 * @param to        收件人成员名（广播时逐个落盘，各自一份）
 * @param type      消息类型
 * @param summary   5-10 词摘要（纯文本消息必带）
 * @param content   消息体
 * @param payload   结构化载荷（如 plan_approval_response 的 {approve, feedback}）
 * @param timestamp epoch 秒
 * @param read      是否已读
 */
public record Message(
        @JsonProperty("from") String from,
        @JsonProperty("to") String to,
        @JsonProperty("type") MessageType type,
        @JsonProperty("summary") String summary,
        @JsonProperty("content") String content,
        @JsonProperty("payload") Payload payload,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("read") boolean read) {

    /** 结构化载荷（plan_approval_response 的 approve/feedback）。 */
    public record Payload(
            @JsonProperty("approve") Boolean approve,
            @JsonProperty("feedback") String feedback) {}

    public Message withRead(boolean read) {
        return new Message(from, to, type, summary, content, payload, timestamp, read);
    }

    public Message withTimestamp(long ts) {
        return new Message(from, to, type, summary, content, payload, ts, read);
    }
}
