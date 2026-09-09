package com.cortex.compact.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.SplittableRandom;
import java.util.logging.Logger;

/**
 * 会话生命周期信息。sessionId 在进程启动时一次性生成（{@code <unix_ts>-<short_random>}），
 * 进程内全局唯一、不持久化；spillDir 是工具结果落盘目录，固定指向
 * {@code .cortex/sessions/<session_id>/tool-results/}。
 */
public record SessionContext(String sessionId, Path spillDir) {

    private static final Logger LOG = Logger.getLogger(SessionContext.class.getName());

    public static SessionContext create(Path workspace) throws IOException {
        String sessionId = newSessionId();
        Path spillDir = workspace.resolve(".cortex/sessions").resolve(sessionId).resolve("tool-results");
        Files.createDirectories(spillDir);
        return new SessionContext(sessionId, spillDir);
    }

    /** 生成会话 id：SecureRandom 取 4 字节，失败时降级为 SplittableRandom 并写 warning。 */
    private static String newSessionId() {
        byte[] bytes = new byte[4];
        try {
            SecureRandom.getInstanceStrong().nextBytes(bytes);
        } catch (Exception e) {
            LOG.warning("SecureRandom 不可用，降级为 SplittableRandom: " + e.getMessage());
            new SplittableRandom(System.nanoTime()).nextBytes(bytes);
        }
        String hex = HexFormat.of().formatHex(bytes);
        return Instant.now().getEpochSecond() + "-" + hex;
    }
}
