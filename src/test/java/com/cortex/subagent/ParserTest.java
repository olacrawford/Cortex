package com.cortex.subagent;

import com.cortex.permission.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Agent 定义文件解析（F4/AC22）：必填校验、非法字段降级、dontAsk 识别、正文提取。 */
class ParserTest {

    @TempDir
    Path tmp;

    private static final String FULL = """
            ---
            name: worker
            description: 测试用角色
            tools:
              - read_file
              - grep
            disallowedTools:
              - bash
            model: haiku
            maxTurns: 7
            permissionMode: dontAsk
            background: true
            ---

            你是测试子 Agent。
            第二行正文。
            """;

    @Test
    void 完整frontmatter全部字段生效() {
        Definition d = Parser.parseDefinition(FULL.getBytes(), "mem://full", Source.USER);
        assertEquals("worker", d.name());
        assertEquals("测试用角色", d.description());
        assertEquals(List.of("read_file", "grep"), d.tools());
        assertEquals(List.of("bash"), d.disallowedTools());
        assertEquals("haiku", d.model());
        assertEquals(7, d.maxTurns());
        assertEquals(Mode.DEFAULT, d.permissionMode()); // dontAsk 单独记入布尔
        assertTrue(d.dontAsk());
        assertTrue(d.background());
        assertTrue(d.systemPrompt().contains("你是测试子 Agent。"));
        assertTrue(d.systemPrompt().contains("第二行正文。"));
        assertFalse(d.systemPrompt().contains("name:"));
        assertEquals(Source.USER, d.source());
        assertFalse(d.isFork());
    }

    @Test
    void 仅必填字段_其余取默认值() {
        String md = """
                ---
                name: minimal
                description: 只有必填
                ---
                正文
                """;
        Definition d = Parser.parseDefinition(md.getBytes(), "mem://min", Source.PROJECT);
        assertEquals("minimal", d.name());
        assertEquals("inherit", d.model());
        assertEquals(0, d.maxTurns());
        assertEquals(Mode.DEFAULT, d.permissionMode());
        assertFalse(d.dontAsk());
        assertFalse(d.background());
        assertTrue(d.tools().isEmpty());
        assertTrue(d.disallowedTools().isEmpty());
        assertEquals("正文", d.systemPrompt());
    }

    @Test
    void 非法model降级inherit() {
        String md = """
                ---
                name: bad-model
                description: x
                model: gpt-4
                ---
                body
                """;
        Definition d = Parser.parseDefinition(md.getBytes(), "mem://bm", Source.BUILTIN);
        assertEquals("inherit", d.model(), "unknown model 应回 fallback 到 inherit（AC22）");
    }

    @Test
    void 非法permissionMode降级default() {
        String md = """
                ---
                name: bad-mode
                description: x
                permissionMode: weirdMode
                ---
                body
                """;
        Definition d = Parser.parseDefinition(md.getBytes(), "mem://bmode", Source.BUILTIN);
        assertEquals(Mode.DEFAULT, d.permissionMode());
        assertFalse(d.dontAsk());
    }

    @Test
    void permissionMode支持四种标准模式() {
        for (String text : new String[]{"default", "acceptEdits", "plan", "bypassPermissions"}) {
            String md = "---\nname: m\ndescription: x\npermissionMode: " + text + "\n---\nbody";
            Definition d = Parser.parseDefinition(md.getBytes(), "mem://m", Source.BUILTIN);
            assertEquals(Mode.parse(text).orElseThrow(), d.permissionMode(), "permissionMode=" + text);
            assertFalse(d.dontAsk());
        }
    }

    @Test
    void 缺name抛异常() {
        String md = "---\ndescription: 没有名字\n---\nbody";
        Parser.ParserException e = assertThrows(Parser.ParserException.class,
                () -> Parser.parseDefinition(md.getBytes(), "mem://n", Source.USER));
        assertTrue(e.getMessage().contains("name"));
    }

    @Test
    void 缺description抛异常() {
        String md = "---\nname: no-desc\n---\nbody";
        assertThrows(Parser.ParserException.class,
                () -> Parser.parseDefinition(md.getBytes(), "mem://d", Source.USER));
    }

    @Test
    void frontmatter未闭合抛异常() {
        String md = "---\nname: unclosed\ndescription: x\nbody 没有闭合线";
        assertThrows(Parser.ParserException.class,
                () -> Parser.parseDefinition(md.getBytes(), "mem://u", Source.USER));
    }

    @Test
    void 无frontmatter的纯文本不可用_name缺失报错() {
        assertThrows(Parser.ParserException.class,
                () -> Parser.parseDefinition("just body".getBytes(), "mem://p", Source.USER));
    }

    @Test
    void name非法字符被拒() {
        String md = "---\nname: 1bad\ndescription: x\n---\nbody";
        assertThrows(Parser.ParserException.class,
                () -> Parser.parseDefinition(md.getBytes(), "mem://b1", Source.USER));
        String md2 = "---\nname: has space\ndescription: x\n---\nbody";
        assertThrows(Parser.ParserException.class,
                () -> Parser.parseDefinition(md2.getBytes(), "mem://b2", Source.USER));
    }

    @Test
    void parseFile读取真实文件() throws Exception {
        Path f = tmp.resolve("wc.md");
        Files.writeString(f, FULL);
        Definition d = Parser.parseFile(f, Source.PROJECT);
        assertEquals("worker", d.name());
        assertTrue(d.filePath().endsWith("wc.md"));
        assertEquals(Source.PROJECT, d.source());
    }
}
