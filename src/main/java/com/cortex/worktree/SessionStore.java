package com.cortex.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * WorktreeSession 的 JSON 原子读写（F30/T2）：先写 {@code <file>.tmp} 再
 * {@code ATOMIC_MOVE} 覆盖，任何时刻磁盘上都有一份完整文件。
 * {@code save(path, null)} 写入字面量 {@code null}——确保下次启动不误恢复（G11）。
 */
public final class SessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionStore() {}

    /**
     * 读取会话：文件不存在 / 内容为 {@code null} 字面量 / 空白 → {@link Optional#empty()}；
     * 非法 JSON 抛 {@link IOException}（N5：调用方捕获后只警告并清空，不阻断启动）。
     */
    public static Optional<WorktreeSession> load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || text.equals("null")) {
            return Optional.empty();
        }
        try {
            WorktreeSession session = MAPPER.readValue(text, WorktreeSession.class);
            return session == null ? Optional.empty() : Optional.of(session);
        } catch (IOException e) {
            throw new IOException("worktree session 文件解析失败: " + e.getMessage(), e);
        }
    }

    /** 原子写会话；{@code session == null} 时写入 {@code null} 字面量。 */
    public static void save(Path file, WorktreeSession session) throws IOException {
        String json = session == null ? "null" : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(session);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.getParent());
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 清空会话（等同 {@code save(file, null)}）。 */
    public static void clear(Path file) throws IOException {
        save(file, null);
    }
}
