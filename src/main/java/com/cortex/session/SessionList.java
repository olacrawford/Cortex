package com.cortex.session;

import com.cortex.compact.state.SessionContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话列表扫描（F18/F19）：遍历 {@code sessions} 下子目录，按最后修改时间倒序返回有效会话。
 * 旧格式 session ID（无法解析时间戳）与缺少 conversation.jsonl 的目录被跳过（N3）。
 */
public final class SessionList {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE_NAME = "conversation.jsonl";
    private static final int TITLE_MAX = 50;

    private SessionList() {}

    /** 扫描目录，返回按最后修改时间倒序的有效会话列表。 */
    public static List<SessionInfo> list(Path sessionsDir) throws IOException {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<SessionInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(sessionsDir)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String id = dir.getFileName().toString();
                try {
                    SessionContext.parseSessionTime(id);
                } catch (DateTimeParseException e) {
                    continue; // 旧格式跳过
                }
                Path jsonl = dir.resolve(FILE_NAME);
                if (!Files.isRegularFile(jsonl)) {
                    continue;
                }
                SessionInfo info = toInfo(id, jsonl, dir);
                if (info != null) {
                    result.add(info);
                }
            }
        }
        result.sort(Comparator.comparing(SessionInfo::modifiedAt).reversed());
        return result;
    }

    private static SessionInfo toInfo(String id, Path jsonl, Path dir) {
        String title = null;
        String model = null;
        try (var reader = Files.newBufferedReader(jsonl)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Entry e;
                try {
                    e = MAPPER.readValue(line, Entry.class);
                } catch (IOException ignored) {
                    continue;
                }
                if (e.type() != null) {
                    continue; // compact 标记行跳过
                }
                if ("user".equals(e.role())) {
                    title = truncate(e.content());
                }
                if (model == null && e.model() != null) {
                    model = e.model();
                }
                if (title != null) {
                    break;
                }
            }
        } catch (IOException e) {
            return null;
        }
        long size;
        java.time.Instant modified;
        try {
            size = Files.size(jsonl);
            modified = Files.getLastModifiedTime(jsonl).toInstant();
        } catch (IOException e) {
            return null;
        }
        return new SessionInfo(id, title == null ? "(无标题)" : title, modified, model, size, dir);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.length() <= TITLE_MAX) {
            return t;
        }
        return t.substring(0, TITLE_MAX) + "…";
    }
}
