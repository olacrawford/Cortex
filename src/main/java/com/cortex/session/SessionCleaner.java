package com.cortex.session;

import com.cortex.compact.state.SessionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 会话过期清理（F25/F26）：删除时间戳距当前超过 maxAge 的会话目录（含 JSONL 与 tool-results）。
 * 旧格式 session ID 无法解析时间戳 → 跳过（N3）；单个删除失败仅告警继续。
 */
public final class SessionCleaner {

    private static final Logger LOG = Logger.getLogger(SessionCleaner.class.getName());

    private SessionCleaner() {}

    /** 清理过期会话目录。 */
    public static void cleanExpired(Path sessionsDir, Duration maxAge) {
        if (!Files.isDirectory(sessionsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(sessionsDir)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String id = dir.getFileName().toString();
                try {
                    var parsed = SessionContext.parseSessionTime(id);
                    Duration age = Duration.between(
                            parsed.atZone(ZoneId.systemDefault()).toInstant(), java.time.Instant.now());
                    if (age.compareTo(maxAge) > 0) {
                        deleteDir(dir);
                    }
                } catch (DateTimeParseException e) {
                    // 旧格式跳过
                } catch (IOException e) {
                    LOG.warning("会话清理失败: " + dir + " → " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warning("会话目录扫描失败: " + e.getMessage());
        }
    }

    /** 递归删除目录；失败仅告警。 */
    private static void deleteDir(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warning("删除会话子项失败: " + p + " → " + e.getMessage());
                }
            }
        }
    }
}
