package com.cortex.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    @TempDir
    Path tempDir;

    @Test
    void 缺失文件降级为空且不抛() {
        Settings s = Settings.load(tempDir.resolve("nope.yaml"));
        assertEquals(Settings.empty(), s);
    }

    @Test
    void 非法YAML降级为空() throws Exception {
        Path bad = tempDir.resolve("bad.yaml");
        Files.writeString(bad, "{{{ 不是合法 YAML :::");
        assertEquals(Settings.empty(), Settings.load(bad));
    }

    @Test
    void 合法配置加载() throws Exception {
        Path good = tempDir.resolve("good.yaml");
        Files.writeString(good, """
                defaultMode: acceptEdits
                permissions:
                  allow:
                    - "Bash(git *)"
                  deny:
                    - "Read(.env)"
                """);
        Settings s = Settings.load(good);
        assertEquals("acceptEdits", s.defaultMode());
        assertEquals(1, s.allow().size());
        assertEquals("Bash(git *)", s.allow().get(0));
        assertEquals("Read(.env)", s.deny().get(0));
    }

    @Test
    void toRuleSet跳过非法条目() {
        RuleSet rs = Settings.toRuleSet(new Settings(null,
                java.util.List.of("Bash(git *)", "非法(条目"),
                java.util.List.of("Read")));
        assertEquals(1, rs.allow().size());
        assertEquals(1, rs.deny().size());
    }

    @Test
    void friendlyName映射() {
        assertEquals("Bash", Settings.friendlyName("bash"));
        assertEquals("Read", Settings.friendlyName("read_file"));
        assertEquals("Write", Settings.friendlyName("write_file"));
        assertEquals("Edit", Settings.friendlyName("edit_file"));
        assertEquals("Glob", Settings.friendlyName("glob"));
        assertEquals("Grep", Settings.friendlyName("grep"));
        assertEquals("ghost", Settings.friendlyName("ghost")); // 未知原样
    }

    @Test
    void categorize判定表() {
        assertEquals(Category.READ, Settings.categorize("read_file", true));
        assertEquals(Category.READ, Settings.categorize("write_file", true)); // readOnly 优先
        assertEquals(Category.WRITE, Settings.categorize("write_file", false));
        assertEquals(Category.WRITE, Settings.categorize("edit_file", false));
        assertEquals(Category.EXEC, Settings.categorize("bash", false));
        assertEquals(Category.EXEC, Settings.categorize("未知工具", false)); // N7 最严
    }

    @Test
    void extractTarget各分支() {
        assertEquals(new Settings.TargetInfo("/tmp/a.txt", true, true),
                Settings.extractTarget(new com.cortex.llm.ToolCall("1", "read_file", "{\"path\":\"/tmp/a.txt\"}")));
        assertEquals(new Settings.TargetInfo(".", true, true),
                Settings.extractTarget(new com.cortex.llm.ToolCall("2", "glob", "{\"pattern\":\"**\"}"))); // path 缺省 "."
        assertEquals(new Settings.TargetInfo("echo hi", false, true),
                Settings.extractTarget(new com.cortex.llm.ToolCall("3", "bash", "{\"command\":\"echo hi\"}")));
        assertFalse(Settings.extractTarget(new com.cortex.llm.ToolCall("4", "read_file", "不是JSON")).ok()); // 解析失败
        assertFalse(Settings.extractTarget(new com.cortex.llm.ToolCall("5", "read_file", "{}")).ok()); // 缺 path
        assertFalse(Settings.extractTarget(new com.cortex.llm.ToolCall("6", "ghost", "{}")).ok()); // 未知工具
        assertTrue(Settings.extractTarget(new com.cortex.llm.ToolCall("7", "bash", "坏JSON")).isFile() == false);
        assertFalse(Settings.extractTarget(new com.cortex.llm.ToolCall("8", "bash", "坏JSON")).ok());
    }
}
