package com.cortex.task;

/**
 * 后台任务状态（F15）。
 */
public enum Status {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public String wireName() {
        return switch (this) {
            case RUNNING -> "running";
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }
}
