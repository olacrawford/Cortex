package com.cortex.session;

import com.cortex.conversation.Message;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话加载恢复（F21）：逐行读 JSONL，坏行跳过、从最后 compact 标记后构建、
 * 末尾孤立 tool_calls 截断，返回消息列表。
 */
public final class SessionLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionLoader() {}

    /** 加载会话消息列表；坏行静默跳过，孤立工具调用截断。 */
    public static List<Message> load(Path sessionDir) throws IOException {
        Path jsonl = sessionDir.resolve("conversation.jsonl");
        if (!Files.isRegularFile(jsonl)) {
            return List.of();
        }
        List<Message> msgs = new ArrayList<>();
        int lastCompactLine = -1;
        int lineNo = 0;
        try (var reader = Files.newBufferedReader(jsonl)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                Entry e;
                try {
                    e = MAPPER.readValue(line, Entry.class);
                } catch (IOException ignored) {
                    continue; // 坏行跳过
                }
                if ("compact".equals(e.type())) {
                    lastCompactLine = msgs.size();
                    continue;
                }
                Message m = toMessage(e);
                if (m != null) {
                    msgs.add(m);
                }
            }
        }
        // 从最后 compact 标记之后开始构建
        List<Message> fromCompact = (lastCompactLine >= 0)
                ? new ArrayList<>(msgs.subList(lastCompactLine, msgs.size()))
                : new ArrayList<>(msgs);
        return truncateOrphanedToolCallComplete(fromCompact);
    }

    /** 若末尾是带 toolCalls 的 assistant 且无后续 tool 消息，截断到该 assistant 之前。 */
    static List<Message> truncateOrphanedToolCallComplete(List<Message> msgs) {
        if (msgs.isEmpty()) {
            return msgs;
        }
        int last = msgs.size() - 1;
        Message tail = msgs.get(last);
        if (tail.getRole() == Message.Role.ASSISTANT && !tail.getToolCalls().isEmpty()) {
            // 孤立：最后一条 assistant 带 tool_calls 但无对应 tool 结果
            return new ArrayList<>(msgs.subList(0, last));
        }
        return new ArrayList<>(msgs);
    }

    private static Message toMessage(Entry e) {
        if (e.role() == null) {
            return null;
        }
        Message.Role role = roleOf(e.role());
        if (role == null) {
            return null;
        }
        List<ToolCall> calls = e.toolCalls() == null ? List.of() : e.toolCalls();
        List<ToolResult> results = e.toolResults() == null ? List.of() : e.toolResults();
        return new Message(role, e.content() == null ? "" : e.content(), calls, results);
    }

    private static Message.Role roleOf(String wire) {
        return switch (wire) {
            case "user" -> Message.Role.USER;
            case "assistant" -> Message.Role.ASSISTANT;
            case "tool" -> Message.Role.TOOL;
            default -> null;
        };
    }
}
