package com.cortex.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Team 持久化辅助（T2/T3b/F63）：sanitize、原子写、读取、跨进程 members reload。
 */
public final class Persistence {

    private static final Pattern INVALID = Pattern.compile("[^a-zA-Z0-9._-]+");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Persistence() {}

    /** 只保留 [a-zA-Z0-9._-]，其余替换为 -，首尾去 -；空结果返回空串（F5-1）。 */
    public static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        String s = INVALID.matcher(name).replaceAll("-").replaceAll("^-+|-+$", "");
        return s;
    }

    /** 原子写 JSON：先写 <path>.tmp 再 ATOMIC_MOVE 覆盖（F63）。 */
    public static void atomicWriteJson(Path path, Object value) throws IOException {
        byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.createDirectories(path.getParent());
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 读 JSON；文件不存在返回 empty；解析失败抛 IOException（调用方按目录跳过）。 */
    public static <T> Optional<T> readJson(Path path, Class<T> type) throws IOException {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(Files.readAllBytes(path), type));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("JSON 解析失败: " + path + ": " + e.getMessage(), e);
        }
    }

    static String writeValue(Object value) throws IOException {
        return new String(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardCharsets.UTF_8);
    }

    /**
     * 跨进程兜底（F19c/T3b）：调用方持 Team.lock 后调用，从磁盘重读 members 覆盖内存——
     * Pane 后端 Lead 与子进程各持一份 Team，先 reload 再改写避免丢更新。
     * 读取失败静默保留内存现状。
     */
    public static void reloadMembersFromDiskLocked(Team team) {
        try {
            Optional<TeamSnapshot> snap = readJson(team.configPath(), TeamSnapshot.class);
            if (snap.isPresent() && snap.get().members() != null) {
                team.replaceMembersLocked(List.copyOf(snap.get().members()));
            }
        } catch (Exception ignored) {
            // 静默回退内存现状
        }
    }

    /** config.json 的磁盘形态（Team 内存对象含派生字段，不直接序列化）。 */
    public record TeamSnapshot(
            @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
            @com.fasterxml.jackson.annotation.JsonProperty("sanitizedName") String sanitizedName,
            @com.fasterxml.jackson.annotation.JsonProperty("leadAgentId") String leadAgentId,
            @com.fasterxml.jackson.annotation.JsonProperty("backend") BackendType backend,
            @com.fasterxml.jackson.annotation.JsonProperty("description") String description,
            @com.fasterxml.jackson.annotation.JsonProperty("createdAt") long createdAt,
            @com.fasterxml.jackson.annotation.JsonProperty("members") List<TeammateInfo> members) {

        public TeamSnapshot {
            if (members == null) {
                members = new ArrayList<>();
            }
        }
    }
}
