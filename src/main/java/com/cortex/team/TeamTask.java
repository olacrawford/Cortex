package com.cortex.team;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 共享任务（F30）：Team 任务列表 tasks.json 的元素。 */
public record TeamTask(
        @JsonProperty("id") String id,
        @JsonProperty("title") String title,
        @JsonProperty("description") String description,
        @JsonProperty("status") String status,          // pending/in_progress/completed/blocked
        @JsonProperty("assignee") String assignee,      // 队员名（可空）
        @JsonProperty("blockedBy") java.util.List<String> blockedBy,
        @JsonProperty("blocks") java.util.List<String> blocks,
        @JsonProperty("createdAt") long createdAt,
        @JsonProperty("updatedAt") long updatedAt) {

    public TeamTask {
        blockedBy = blockedBy == null ? java.util.List.of() : java.util.List.copyOf(blockedBy);
        blocks = blocks == null ? java.util.List.of() : java.util.List.copyOf(blocks);
    }

    public TeamTask withStatus(String newStatus, long ts) {
        return new TeamTask(id, title, description, newStatus, assignee, blockedBy, blocks, createdAt, ts);
    }
}
