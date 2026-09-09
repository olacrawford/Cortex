package com.cortex.compact.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.SplittableRandom;
import java.util.logging.Logger;

/**
 * 会话生命周期信息。sessionId 在进程启动时一次性生成（{@code YYYYMMDD-HHMMSS-<4hex>}），
 * 进程内全局唯一；sessionDir 是会话目录（{@code .cortex/sessions/<session_id>}），
 * spillDir 是工具结果落盘目录（{@code sessionDir/tool-results}），JSONL 存档位于
 * {@code sessionDir/conversation.jsonl}。
 */
public record SessionContext(String sessionId, Path sessionDir, Path spillDir) {

    private static final Logger LOG = Logger.getLogger(SessionContext.class.getName());
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 新建会话：生成新 sessionId 并创建落盘目录。 */
    public static SessionContext create(Path workspace) throws IOException {
        String sessionId = newSessionId();
        Path sessionDir = workspace.resolve(".cortex/sessions").resolve(sessionId);
        Path spillDir = sessionDir.resolve("tool-results");
        Files.createDirectories(spillDir);
        return new SessionContext(sessionId, sessionDir, spillDir);
    }

    /** 打开已有会话：不创建目录，仅校验存在后填充字段（用于 /resume）。 */
    public static SessionContext open(Path workspace, String sessionId) {
        Path sessionDir = workspace.resolve(".cortex/sessions").resolve(sessionId);
        if (!Files.isDirectory(sessionDir)) {
            throw new IllegalArgumentException("会话目录不存在: " + sessionDir);
        }
        return new SessionContext(sessionId, sessionDir, sessionDir.resolve("tool-results"));
    }

    /**
     * 从 sessionId 前 15 位解析 {@code YYYYMMDD-HHMMSS} 时间，供过期清理与列表排序使用。
     * 旧格式 ID 无法解析时抛 {@link DateTimeParseException}，调用方据此跳过。
     */
    public static LocalDateTime parseSessionTime(String sessionId) {
        if (sessionId == null || sessionId.length() < 15) {
            throw new DateTimeParseException("无效的 session id", sessionId == null ? "" : sessionId, 0);
        }
        return LocalDateTime.parse(sessionId.substring(0, 15), ID_FORMAT);
    }

    /** 生成会话 id：{@code YYYYMMDD-HHMMSS-<4hex>}；SecureRandom 取 4 字节，失败时降级 SplittableRandom。 */
    private static String newSessionId() {
        byte[] bytes = new byte[4];
        try {
            SecureRandom.getInstanceStrong().nextBytes(bytes);
        } catch (Exception e) {
            LOG.warning("SecureRandom 不可用，降级为 SplittableRandom: " + e.getMessage());
            new SplittableRandom(System.nanoTime()).nextBytes(bytes);
        }
        String hex = HexFormat.of().formatHex(bytes).substring(0, 4);
        return LocalDateTime.now().format(ID_FORMAT) + "-" + hex;
    }
}
