package com.cortex.session;

import com.cortex.conversation.Message;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @TempDir
    Path tempDir;

    @Test
    void appendAndRead() throws Exception {
        Path dir = tempDir.resolve("20260601-143022-a1b2");
        try (Writer w = Writer.create(dir)) {
            w.append(new Message(Message.Role.USER, "hi"), "model-x", true);
            w.append(new Message(Message.Role.ASSISTANT, "hello"), null, false);
            w.append(new Message(Message.Role.TOOL, "", List.of(), List.of(new ToolResult("c1", "ok", false))), null, false);
        }
        List<String> lines = Files.readAllLines(dir.resolve("conversation.jsonl"));
        assertEquals(3, lines.size());
        Entry first = MAPPER.readValue(lines.get(0), Entry.class);
        assertEquals("user", first.role());
        assertEquals("hi", first.content());
        assertEquals("model-x", first.model());
        Entry third = MAPPER.readValue(lines.get(2), Entry.class);
        assertEquals("tool", third.role());
        assertEquals(1, third.toolResults().size());
    }

    @Test
    void compactMarker() throws Exception {
        Path dir = tempDir.resolve("20260601-143022-b2c3");
        try (Writer w = Writer.create(dir)) {
            w.append(new Message(Message.Role.USER, "old"), null, true);
            w.writeCompactMarker();
            w.append(new Message(Message.Role.USER, "new"), null, false);
        }
        List<Message> msgs = SessionLoader.load(dir);
        assertEquals(1, msgs.size());
        assertEquals("new", msgs.get(0).getContent());
    }

    @Test
    void loadBadLineSkip() throws Exception {
        Path dir = tempDir.resolve("20260601-143022-c3d4");
        Path jsonl = dir.resolve("conversation.jsonl");
        Files.createDirectories(dir);
        Files.writeString(jsonl, "{\"role\":\"user\",\"content\":\"good\"}\n"
                + "this is not json\n"
                + "{\"role\":\"assistant\",\"content\":\"reply\"}\n");
        List<Message> msgs = SessionLoader.load(dir);
        assertEquals(2, msgs.size());
        assertEquals("good", msgs.get(0).getContent());
    }

    @Test
    void loadOrphanedToolCallComplete() throws Exception {
        Path dir = tempDir.resolve("20260601-143022-d4e5");
        Path jsonl = dir.resolve("conversation.jsonl");
        Files.createDirectories(dir);
        // 最后一条 assistant 带 tool_calls 但无后续 tool 结果
        Files.writeString(jsonl, "{\"role\":\"user\",\"content\":\"go\"}\n"
                + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\",\"name\":\"read_file\",\"args\":\"{}\"}]}\n");
        List<Message> msgs = SessionLoader.load(dir);
        assertEquals(1, msgs.size());
        assertEquals("go", msgs.get(0).getContent());
    }

    @Test
    void listSessions() throws Exception {
        makeSession("now", "标题A", "model-a");
        makeSession("1d", "标题B", "model-b");
        makeSession("2d", "标题C", "model-c");
        List<SessionInfo> infos = SessionList.list(tempDir);
        assertEquals(3, infos.size());
        assertTrue(infos.get(0).title().contains("标题"));
        assertTrue(infos.get(0).modifiedAt().isAfter(infos.get(1).modifiedAt()));
    }

    @Test
    void listSkipsOldFormat() throws Exception {
        makeSession("now", "标题A", "model-a");
        // 旧格式目录名
        Path old = tempDir.resolve("1717000000-abc12345");
        Files.createDirectories(old);
        Files.writeString(old.resolve("conversation.jsonl"), "{\"role\":\"user\",\"content\":\"old\"}\n");
        List<SessionInfo> infos = SessionList.list(tempDir);
        assertEquals(1, infos.size());
        assertFalse(infos.get(0).id().contains("-abc"));
    }

    @Test
    void cleanExpired() throws Exception {
        Path old31 = makeSession("31d", "旧", "m");
        Path recent1 = makeSession("1d", "新", "m");
        SessionCleaner.cleanExpired(tempDir, java.time.Duration.ofDays(30));
        assertFalse(Files.exists(old31));
        assertTrue(Files.exists(recent1));
    }

    private Path makeSession(String when, String title, String model) throws Exception {
        LocalDateTime dt = switch (when) {
            case "1d" -> LocalDateTime.now().minusDays(1);
            case "2d" -> LocalDateTime.now().minusDays(2);
            case "31d" -> LocalDateTime.now().minusDays(31);
            default -> LocalDateTime.now();
        };
        Path dir = tempDir.resolve(dt.format(FMT) + "-ab12");
        Files.createDirectories(dir);
        try (Writer w = Writer.create(dir)) {
            w.append(new Message(Message.Role.USER, title), model, true);
        }
        return dir;
    }
}
