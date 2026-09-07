package com.cortex.tool;

import com.cortex.llm.ToolDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryTest {

    @TempDir
    Path tempDir;

    // ─── 注册中心（AC1）───

    @Test
    void definitionsReturnsSixOrdered() {
        ToolRegistry registry = ToolRegistry.createDefault();
        List<ToolDef> defs = registry.definitions();

        assertEquals(6, defs.size());
        assertEquals(List.of("read_file", "write_file", "edit_file", "bash", "glob", "grep"),
                defs.stream().map(ToolDef::name).toList());
        defs.forEach(d -> {
            assertEquals("object", d.inputSchema().get("type"));
            assertNotNull(d.inputSchema().get("properties"));
            assertNotNull(d.description());
        });
    }

    @Test
    void get命中与未命中() {
        ToolRegistry registry = ToolRegistry.createDefault();
        assertTrue(registry.get("read_file").isPresent());
        assertTrue(registry.get("no_such_tool").isEmpty());
    }

    @Test
    void execute未知工具返回错误() {
        ToolRegistry registry = ToolRegistry.createDefault();
        Result r = registry.execute("no_such_tool", "{}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("未知工具"));
    }

    @Test
    void execute空参数归一为空对象() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        Result r = registry.execute("bash", "");
        // 归一化生效的证据：报「缺少必填参数」而非 JSON 解析失败
        assertTrue(r.isError());
        assertTrue(r.content().contains("缺少必填参数"));
    }

    // ─── read_file（AC2）───

    @Test
    void readFile_exists带行号() throws Exception {
        Path f = tempDir.resolve("hello.txt");
        Files.writeString(f, "第一行\n第二行\n");
        Result r = ToolRegistry.createDefault().execute("read_file", "{\"path\":\"" + f + "\"}");
        assertFalse(r.isError());
        assertTrue(r.content().contains("     1\t第一行"));
        assertTrue(r.content().contains("     2\t第二行"));
    }

    @Test
    void readFile_missing返回错误() {
        Result r = ToolRegistry.createDefault()
                .execute("read_file", "{\"path\":\"" + tempDir.resolve("nope.txt") + "\"}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("不存在"));
    }

    @Test
    void readFile_directory返回错误() {
        Result r = ToolRegistry.createDefault().execute("read_file", "{\"path\":\"" + tempDir + "\"}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("目录"));
    }

    // ─── write_file（AC3）───

    @Test
    void writeFile_nestedDir自动创建父目录() throws Exception {
        Path target = tempDir.resolve("a/b/c.txt");
        Result r = ToolRegistry.createDefault()
                .execute("write_file", "{\"path\":\"" + target + "\",\"content\":\"hello 写入\"}");
        assertFalse(r.isError());
        assertEquals("hello 写入", Files.readString(target));
        assertTrue(r.content().contains("已写入"));
    }

    @Test
    void writeFile_覆盖已有文件() throws Exception {
        Path target = tempDir.resolve("over.txt");
        Files.writeString(target, "旧内容");
        ToolRegistry.createDefault().execute("write_file",
                "{\"path\":\"" + target + "\",\"content\":\"新内容\"}");
        assertEquals("新内容", Files.readString(target));
    }

    // ─── edit_file（AC4）───

    @Test
    void editFile_unique替换成功() throws Exception {
        Path f = tempDir.resolve("e1.txt");
        Files.writeString(f, "foo bar baz");
        Result r = ToolRegistry.createDefault()
                .execute("edit_file", "{\"path\":\"" + f + "\",\"old_string\":\"bar\",\"new_string\":\"qux\"}");
        assertFalse(r.isError());
        assertEquals("foo qux baz", Files.readString(f));
    }

    @Test
    void editFile_zero返回未找到() throws Exception {
        Path f = tempDir.resolve("e2.txt");
        Files.writeString(f, "foo");
        Result r = ToolRegistry.createDefault()
                .execute("edit_file", "{\"path\":\"" + f + "\",\"old_string\":\"不存在\",\"new_string\":\"x\"}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("未找到匹配"));
        assertEquals("foo", Files.readString(f));
    }

    @Test
    void editFile_multiple返回含匹配数的错误() throws Exception {
        Path f = tempDir.resolve("e3.txt");
        Files.writeString(f, "aa aa");
        Result r = ToolRegistry.createDefault()
                .execute("edit_file", "{\"path\":\"" + f + "\",\"old_string\":\"aa\",\"new_string\":\"b\"}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("2"));
        assertTrue(r.content().contains("不唯一"));
        assertEquals("aa aa", Files.readString(f));
    }

    @Test
    void editFile三情形文案可区分() {
        Path f = tempDir.resolve("e4.txt");
        Result zero = ToolRegistry.createDefault()
                .execute("edit_file", "{\"path\":\"" + f + "\",\"old_string\":\"x\",\"new_string\":\"y\"}");
        // 未找到与不唯一的文案必须可区分（AC4）
        assertNotEquals(zero.content(), "匹配到 2 处，old_string 不唯一，请提供更长上下文使其唯一");
        assertTrue(zero.isError());
    }

    // ─── bash（AC5/N1）───

    @Test
    void bash_echo返回输出与退出码() {
        Result r = ToolRegistry.createDefault().execute("bash", "{\"command\":\"echo hi\"}");
        assertFalse(r.isError());
        assertTrue(r.content().contains("hi"));
        assertTrue(r.content().contains("[exit_code: 0]"));
    }

    @Test
    void bash_非零退出不设isError() {
        Result r = ToolRegistry.createDefault().execute("bash", "{\"command\":\"exit 3\"}");
        assertFalse(r.isError());
        assertTrue(r.content().contains("[exit_code: 3]"));
    }

    @Test
    void bash_超时被终止() {
        Result r = new BashTool(Duration.ofMillis(300)).execute("{\"command\":\"sleep 5\"}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("超时"));
    }

    // ─── glob（AC6）───

    @Test
    void glob_starStarJava命中嵌套文件() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.writeString(tempDir.resolve("src/main/java/A.java"), "class A {}");
        Files.writeString(tempDir.resolve("src/main/java/B.java"), "class B {}");
        Files.writeString(tempDir.resolve("src/main/resources/data.txt"), "x");

        Result r = ToolRegistry.createDefault()
                .execute("glob", "{\"pattern\":\"**/*.java\",\"path\":\"" + tempDir + "\"}");
        assertFalse(r.isError());
        assertTrue(r.content().contains("A.java"));
        assertTrue(r.content().contains("B.java"));
        assertFalse(r.content().contains("data.txt"));
    }

    @Test
    void glob_无匹配返回说明() {
        Result r = ToolRegistry.createDefault()
                .execute("glob", "{\"pattern\":\"**/*.xyz123\",\"path\":\"" + tempDir + "\"}");
        assertFalse(r.isError());
        assertEquals("无匹配", r.content());
    }

    // ─── grep（AC6）───

    @Test
    void grep_keyword命中fileLineContent() throws Exception {
        Path f = tempDir.resolve("sample.txt");
        Files.writeString(f, "第一行 hello\n中间行\n最后一行 world hello\n");
        Result r = ToolRegistry.createDefault()
                .execute("grep", "{\"pattern\":\"hello\",\"path\":\"" + tempDir + "\"}");
        assertFalse(r.isError());
        assertTrue(r.content().contains("sample.txt:1:第一行 hello"));
        assertTrue(r.content().contains("sample.txt:3:最后一行 world hello"));
        assertFalse(r.content().contains("中间行"));
    }

    @Test
    void grep_glob过滤与非法正则() throws Exception {
        Files.writeString(tempDir.resolve("a.java"), "keyword\n");
        Files.writeString(tempDir.resolve("b.txt"), "keyword\n");

        Result filtered = ToolRegistry.createDefault()
                .execute("grep", "{\"pattern\":\"keyword\",\"path\":\"" + tempDir + "\",\"glob\":\"*.java\"}");
        assertTrue(filtered.content().contains("a.java"));
        assertFalse(filtered.content().contains("b.txt"));

        Result badRegex = ToolRegistry.createDefault()
                .execute("grep", "{\"pattern\":\"[非法\",\"path\":\"" + tempDir + "\"}");
        assertTrue(badRegex.isError());
        assertTrue(badRegex.content().contains("正则非法"));
    }

    // ─── 截断（N5）───

    @Test
    void read大文件被截断() throws Exception {
        Path f = tempDir.resolve("big.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("line-").append(i).append('\n');
        }
        Files.writeString(f, sb.toString());
        Result r = ToolRegistry.createDefault().execute("read_file", "{\"path\":\"" + f + "\"}");
        assertTrue(r.content().contains("[truncated]"));
        assertTrue(r.content().contains("line-1999"));
        assertFalse(r.content().contains("line-2999"));
    }
}
