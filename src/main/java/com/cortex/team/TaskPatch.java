package com.cortex.team;

import java.util.List;

/**
 * 任务更新补丁（F29）：null 字段不更新。
 *
 * @param addBlocks       把本任务加进这些任务的 blocks
 * @param addBlockedBy    本任务被这些任务阻塞（双向维护）
 */
public record TaskPatch(
        String title,
        String description,
        String status,
        String assignee,
        List<String> addBlocks,
        List<String> addBlockedBy,
        List<String> removeBlocks,
        List<String> removeBlockedBy) {

    public boolean isEmpty() {
        return title == null && description == null && status == null && assignee == null
                && (addBlocks == null || addBlocks.isEmpty())
                && (addBlockedBy == null || addBlockedBy.isEmpty())
                && (removeBlocks == null || removeBlocks.isEmpty())
                && (removeBlockedBy == null || removeBlockedBy.isEmpty());
    }
}
