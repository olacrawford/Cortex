package com.cortex.compact;

import com.cortex.compact.state.ContentReplacementState;
import com.cortex.compact.state.SessionContext;
import com.cortex.conversation.Message;
import com.cortex.llm.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Layer1Test {

    @TempDir
    Path tempDir;

    private SessionContext session() throws IOException {
        return SessionContext.create(tempDir);
    }

    private static Message toolMessage(List<ToolResult> results) {
        return new Message(Message.Role.TOOL, "", List.of(), results);
    }

    private static ToolResult result(String id, int bytes, boolean err) {
        return new ToolResult(id, "x".repeat(bytes), err);
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void 单条超阈值被替换且落盘() throws IOException {
        SessionContext session = session();
        ContentReplacementState state = new ContentReplacementState();
        List<Message> msgs = List.of(toolMessage(List.of(result("c1", 60000, false))));

        List<Message> out = ContextCompactor.offloadAndSnip(msgs, state, session);

        String content = out.get(0).getToolResults().get(0).content();
        assertTrue(content.contains("[content offloaded] original size: 60000 bytes"));
        assertTrue(content.contains("[saved to]"));
        assertTrue(content.contains("[head preview]"));
        assertTrue(content.contains("读取工具读取该路径"));
        // 头部预览 ≤ 20 行且 ≤ 2048 字节
        int headStart = content.indexOf("[head preview]\n") + "[head preview]\n".length();
        String head = content.substring(headStart, content.indexOf("完整内容已保存"));
        assertTrue(head.strip().split("\n").length <= CompactConstants.PREVIEW_HEAD_LINES);
        assertTrue(head.strip().getBytes(StandardCharsets.UTF_8).length <= CompactConstants.PREVIEW_HEAD_BYTES);
        // 落盘文件存在
        assertTrue(Files.exists(session.spillDir().resolve("c1")));
    }

    @Test
    void 聚合超阈值按字节大到小落盘() throws IOException {
        SessionContext session = session();
        ContentReplacementState state = new ContentReplacementState();
        // 5 条 45000 字节，均低于单条阈值 50000，合计 225000 > 聚合阈值 200000
        List<Message> msgs = List.of(toolMessage(List.of(
                result("a", 45000, false), result("b", 45000, false), result("c", 45000, false),
                result("d", 45000, false), result("e", 45000, false))));

        List<Message> out = ContextCompactor.offloadAndSnip(msgs, state, session);

        int kept = 0;
        for (ToolResult r : out.get(0).getToolResults()) {
            if (!r.content().contains("[content offloaded]")) {
                kept += bytes(r.content());
            }
        }
        assertTrue(kept <= CompactConstants.MESSAGE_AGGREGATE_LIMIT,
                "聚合应回落到 ≤ 200000，实际 " + kept);
        // 按字节大→小：只替换 1 条最大项即可让聚合达标（225000-45000=180000 ≤ 200000）
        long replaced = out.get(0).getToolResults().stream()
                .filter(r -> r.content().contains("[content offloaded]")).count();
        assertEquals(1, replaced, "应只替换最小的必要数量");
        // 被替换的是最大的一条（其余 4 条保持原文）
        assertEquals("x".repeat(45000), out.get(0).getToolResults().stream()
                .filter(r -> !r.content().contains("[content offloaded]")).findFirst().orElseThrow().content());
    }

    @Test
    void 幂等落盘文件只写一次() throws IOException {
        SessionContext session = session();
        ContentReplacementState state = new ContentReplacementState();
        List<Message> msgs = List.of(toolMessage(List.of(result("c1", 60000, false))));

        ContextCompactor.offloadAndSnip(msgs, state, session);
        Path file = session.spillDir().resolve("c1");
        long mtime1 = Files.getLastModifiedTime(file).toMillis();
        // 第二次：已决策 → 不复用落盘逻辑，但内容一致
        ContextCompactor.offloadAndSnip(msgs, state, session);
        long mtime2 = Files.getLastModifiedTime(file).toMillis();
        assertEquals(mtime1, mtime2, "重复落盘应跳过 I/O");
    }

    @Test
    void 决策冻结跨轮一致() throws IOException {
        SessionContext session = session();
        ContentReplacementState state = new ContentReplacementState();
        List<Message> msgs = List.of(toolMessage(List.of(result("c1", 60000, false))));

        List<Message> out1 = ContextCompactor.offloadAndSnip(msgs, state, session);
        List<Message> out2 = ContextCompactor.offloadAndSnip(msgs, state, session);
        assertEquals(out1.get(0).getToolResults().get(0).content(),
                out2.get(0).getToolResults().get(0).content());
    }

    @Test
    void 落盘失败降级不替换不写账本() throws IOException {
        SessionContext session = session();
        ContentReplacementState state = new ContentReplacementState();
        // 让 spillDir 指向一个已存在的普通文件，resolve 出的子路径写入必然失败
        Path file = tempDir.resolve("not-a-dir");
        Files.writeString(file, "x");
        SessionContext bad = new SessionContext(session.sessionId(), session.sessionDir(), file);

        List<Message> msgs = List.of(toolMessage(List.of(result("c1", 60000, false))));
        List<Message> out = ContextCompactor.offloadAndSnip(msgs, state, bad);

        // 不替换、不写账本
        assertEquals("x".repeat(60000), out.get(0).getToolResults().get(0).content());
        assertFalse(state.seen("c1"));
    }

    @Test
    void 预览体逐字节稳定() throws IOException {
        SessionContext session = session();
        String head = ContextCompactor.headPreview("a\nb\nc\n");
        Path spill = session.spillDir().resolve("c1");
        String p1 = ContextCompactor.buildPreview(100, head, spill);
        String p2 = ContextCompactor.buildPreview(100, head, spill);
        assertEquals(p1, p2);
    }
}
