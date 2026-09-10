package com.cortex.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 6 个核心工具的 explicit cwd（F17/AC13/AC14）。 */
class ToolCwdTest {

    @TempDir
    Path cwdDir;

    private ToolContext ctx() {
        return ToolContext.EMPTY.withCwd(cwdDir);
    }

    @Test
    void read_file按ctx_cwd解析相对路径() {
        String content = "cwd 内容";
        assertDoesNotThrow(() -> Files.writeString(cwdDir.resolve("a.txt"), content));
        Result r = new ReadFileTool().execute(ctx(), "{\"path\":\"a.txt\"}");
        assertFalse(r.isError(), r.content());
        assertTrue(r.content().contains(content), "应读到 cwdDir/a.txt");
    }

    @Test
    void write_file按ctx_cwd写入() {
        Result r = new WriteFileTool().execute(ctx(), "{\"path\":\"out.txt\",\"content\":\"hi\"}");
        assertFalse(r.isError(), r.content());
        assertDoesNotThrow(() -> assertEquals("hi", Files.readString(cwdDir.resolve("out.txt"))));
    }

    @Test
    void edit_file按ctx_cwd修改() {
        assertDoesNotThrow(() -> Files.writeString(cwdDir.resolve("e.txt"), "old"));
        Result r = new EditFileTool().execute(ctx(),
                "{\"path\":\"e.txt\",\"old_string\":\"old\",\"new_string\":\"new\"}");
        assertFalse(r.isError(), r.content());
        assertDoesNotThrow(() -> assertEquals("new", Files.readString(cwdDir.resolve("e.txt"))));
    }

    @Test
    void bash在ctx_cwd目录内执行() throws Exception {
        // AC14：pwd 应输出 ctx cwd
        Result r = new BashTool().execute(ctx(), "{\"command\":\"pwd\"}");
        assertFalse(r.isError(), r.content());
        String real = cwdDir.toRealPath().toString(); // macOS /var → /private/var
        assertTrue(r.content().startsWith(real), "bash 工作目录应为 ctx cwd: " + r.content());
    }

    @Test
    void glob在ctx_cwd内搜索() throws Exception {
        Files.writeString(cwdDir.resolve("find-me.txt"), "x");
        Result r = new GlobTool().execute(ctx(), "{\"pattern\":\"find-me.txt\"}");
        assertFalse(r.isError(), r.content());
        assertTrue(r.content().contains("find-me.txt"), r.content());
    }

    @Test
    void grep在ctx_cwd内搜索() throws Exception {
        Files.writeString(cwdDir.resolve("hay.txt"), "needle here\n");
        Result r = new GrepTool().execute(ctx(), "{\"pattern\":\"needle\"}");
        assertFalse(r.isError(), r.content());
        assertTrue(r.content().contains("needle"), r.content());
    }

    @Test
    void 工具schema不含cwd字段() {
        // F19/N1：ctx 注入不进 schema，主 Agent 工具列表稳定
        for (Tool t : new Tool[]{new ReadFileTool(), new WriteFileTool(), new EditFileTool(),
                new BashTool(), new GlobTool(), new GrepTool()}) {
            assertFalse(t.inputSchema().toString().contains("cwd"), t.name() + " schema 不应含 cwd");
        }
    }
}
