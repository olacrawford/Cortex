package com.cortex.session;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 会话列表项：id、标题、最后修改时间、模型标签、文件大小、目录。
 */
public record SessionInfo(String id, String title, Instant modifiedAt, String model, long size, Path dir) {}
