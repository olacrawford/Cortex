package com.cortex.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HookLoaderTest {

    @TempDir
    Path tmp;

    private static final String VALID_YAML = """
            hooks:
              - name: block-write
                event: PreToolUse
                if:
                  all_of:
                    - field: tool_name
                      match: { type: exact, value: write_file }
                action:
                  type: shell
                  command: "echo blocked >&2; exit 2"
              - name: zh-cn
                event: SessionStart
                action:
                  type: prompt
                  text: "用 zh-CN 回复"
            """;

    private Path writeUser(String content) throws IOException {
        Path f = tmp.resolve("user-hooks.yaml");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private Path writeProject(String content) throws IOException {
        Path f = tmp.resolve("project-hooks.yaml");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void load_合法yaml解析出两条规则() throws IOException {
        HookEngine engine = HookLoader.load(writeUser(VALID_YAML), tmp.resolve("nope.yaml"));
        assertEquals(2, engine.rules().size());
        assertEquals(1, engine.sources().size());

        HookRule first = engine.rules().get(0);
        assertEquals("block-write", first.name());
        assertEquals(Event.PRE_TOOL_USE, first.event());
        assertTrue(first.event().isBlocking());
        assertFalse(first.async());
        assertEquals(HookRule.DEFAULT_TIMEOUT, first.timeout());
        assertNotNull(first.condition());

        HookRule second = engine.rules().get(1);
        assertEquals(Event.SESSION_START, second.event());
        assertInstanceOf(Action.Prompt.class, second.action());
    }

    @Test
    void load_文件不存在静默_得到空引擎() {
        HookEngine engine = HookLoader.load(tmp.resolve("a.yaml"), tmp.resolve("b.yaml"));
        assertTrue(engine.rules().isEmpty());
        assertTrue(engine.sources().isEmpty());
    }

    @Test
    void load_整体yaml解析失败只报错不抛() throws IOException {
        Path bad = writeProject("hooks: [ { this is not valid yaml: : :");
        HookEngine engine = HookLoader.load(tmp.resolve("nope.yaml"), bad);
        assertTrue(engine.rules().isEmpty());
    }

    @Test
    void load_字段缺失与枚举错跳过该条其余正常() throws IOException {
        Path file = writeProject("""
                hooks:
                  - event: SessionStart
                    action: { type: prompt, text: "x" }
                  - name: unknown-ev
                    event: UnknownEvent
                    action: { type: prompt, text: "x" }
                  - name: bad-action
                    event: Stop
                    action: { type: teleport }
                  - name: no-command
                    event: Stop
                    action: { type: shell }
                  - name: ok
                    event: Stop
                    action: { type: shell, command: "echo hi" }
                """);
        HookEngine engine = HookLoader.load(tmp.resolve("nope.yaml"), file);
        assertEquals(1, engine.rules().size());
        assertEquals("ok", engine.rules().get(0).name());
    }

    @Test
    void load_allOf与anyOf同时存在跳过() throws IOException {
        Path file = writeProject("""
                hooks:
                  - name: both-modes
                    event: Stop
                    if:
                      all_of:
                        - field: a
                          match: { type: exact, value: "1" }
                      any_of:
                        - field: b
                          match: { type: exact, value: "2" }
                    action: { type: shell, command: "true" }
                """);
        HookEngine engine = HookLoader.load(tmp.resolve("nope.yaml"), file);
        assertTrue(engine.rules().isEmpty(), "AC16：all_of/any_of 互斥");
    }

    @Test
    void load_async加拦截事件跳过且stderr含固定文案() throws IOException {
        Path file = writeProject("""
                hooks:
                  - name: bad-async
                    event: PreToolUse
                    async: true
                    action: { type: shell, command: "echo x" }
                  - name: good-hook
                    event: SessionStart
                    action: { type: shell, command: "echo ok" }
                """);
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream oldErr = System.err;
        System.setErr(new java.io.PrintStream(buf, true));
        HookEngine engine;
        try {
            engine = HookLoader.load(tmp.resolve("nope.yaml"), file);
        } finally {
            System.setErr(oldErr);
        }
        assertEquals(1, engine.rules().size());
        assertEquals("good-hook", engine.rules().get(0).name());
        String err = buf.toString();
        assertTrue(err.contains("async not allowed for blocking events"), err);
        assertTrue(err.contains("bad-async"), err);
    }

    @Test
    void load_跨文件同名冲突项目级保留用户级跳过() throws IOException {
        Path user = writeUser("""
                hooks:
                  - name: dup
                    event: SessionStart
                    action: { type: prompt, text: "user版" }
                """);
        Path project = writeProject("""
                hooks:
                  - name: dup
                    event: SessionStart
                    action: { type: prompt, text: "project版" }
                """);
        HookEngine engine = HookLoader.load(user, project);
        assertEquals(1, engine.rules().size());
        Action.Prompt p = (Action.Prompt) engine.rules().get(0).action();
        assertEquals("project版", p.text(), "同名 hook 项目级胜出（F7）");
        // 来源文件：仅项目级计入 sources
        assertEquals(1, engine.sources().size());
    }

    @Test
    void load_非法正则matcher跳过该条() throws IOException {
        Path file = writeProject("""
                hooks:
                  - name: bad-regex
                    event: Stop
                    if:
                      all_of:
                        - field: x
                          match: { type: regex, value: "[unclosed" }
                    action: { type: shell, command: "true" }
                """);
        HookEngine engine = HookLoader.load(tmp.resolve("nope.yaml"), file);
        assertTrue(engine.rules().isEmpty(), "F14：正则编译失败视为加载错误");
    }

    @Test
    void load_not条件与timeout解析() throws IOException {
        Path file = writeProject("""
                hooks:
                  - name: not-rule
                    event: Stop
                    if:
                      all_of:
                        - field: tool_name
                          match:
                            type: not
                            inner: { type: exact, value: read_file }
                    action: { type: shell, command: "true" }
                    timeout: 500ms
                    only_once: true
                """);
        HookEngine engine = HookLoader.load(tmp.resolve("nope.yaml"), file);
        assertEquals(1, engine.rules().size());
        assertEquals(java.time.Duration.ofMillis(500), engine.rules().get(0).timeout());
        assertTrue(engine.rules().get(0).onlyOnce());
    }

}
