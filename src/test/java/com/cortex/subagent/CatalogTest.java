package com.cortex.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Catalog 三层加载（F5/F6/F7）：内置加载、同名覆盖、forkDefinition、坏文件跳过。 */
class CatalogTest {

    @TempDir
    Path root;

    private static final String EXPLORE_OVERRIDE = """
            ---
            name: Explore
            description: 项目级覆盖的 Explore
            maxTurns: 10
            ---

            项目级正文。
            """;

    @Test
    void builtin三个定义加载成功() {
        List<Definition> defs = BuiltinLoader.builtinDefinitions();
        assertEquals(3, defs.size());
        assertEquals(List.of("Explore", "Plan", "general-purpose"),
                defs.stream().map(Definition::name).sorted().toList());
        for (Definition d : defs) {
            assertEquals(Source.BUILTIN, d.source());
            assertFalse(d.systemPrompt().isBlank(), d.name() + " 应有非空正文");
        }
        // 内置字段抽查（F32）
        Definition explore = defs.stream().filter(d -> d.name().equals("Explore")).findFirst().orElseThrow();
        assertEquals("haiku", explore.model());
        assertEquals(30, explore.maxTurns());
        assertTrue(explore.disallowedTools().contains("write_file"));
        Definition plan = defs.stream().filter(d -> d.name().equals("Plan")).findFirst().orElseThrow();
        assertEquals(15, plan.maxTurns());
        assertEquals(com.cortex.permission.Mode.PLAN, plan.permissionMode());
        assertTrue(plan.disallowedTools().contains("Agent"));
    }

    @Test
    void catalogLoad默认含内置三层() {
        Catalog c = Catalog.load(root);
        assertTrue(c.resolve("general-purpose").isPresent());
        assertTrue(c.resolve("Explore").isPresent());
        assertTrue(c.resolve("Plan").isPresent());
    }

    @Test
    void 项目级覆盖内置_用户级覆盖内置() throws Exception {
        // 项目级覆盖 Explore
        Path projectAgents = root.resolve(".cortex/agents");
        Files.createDirectories(projectAgents);
        Files.writeString(projectAgents.resolve("explore.md"), EXPLORE_OVERRIDE);

        // 用户级覆盖 Plan（临时改 user.home）
        String oldHome = System.getProperty("user.home");
        Path fakeHome = Files.createTempDirectory("cortex-home");
        Path userAgents = fakeHome.resolve(".cortex/agents");
        Files.createDirectories(userAgents);
        Files.writeString(userAgents.resolve("plan.md"), """
                ---
                name: Plan
                description: 用户级覆盖的 Plan
                maxTurns: 9
                ---
                用户级正文。
                """);
        System.setProperty("user.home", fakeHome.toString());
        try {
            Catalog c = Catalog.load(root);
            Definition explore = c.resolve("explore").orElseThrow(); // 大小写不敏感（AC16）
            assertEquals(Source.PROJECT, explore.source());
            assertEquals(10, explore.maxTurns());
            assertTrue(explore.systemPrompt().contains("项目级正文"));

            Definition plan = c.resolve("Plan").orElseThrow();
            assertEquals(Source.USER, plan.source());
            assertEquals(9, plan.maxTurns());

            Definition gp = c.resolve("general-purpose").orElseThrow();
            assertEquals(Source.BUILTIN, gp.source()); // 未被覆盖

            // 三层叠加时 resolve 返回最高优先级
            assertEquals(3, c.listBySource(Source.BUILTIN).size());
            assertEquals(1, c.listBySource(Source.PROJECT).size());
            assertEquals(1, c.listBySource(Source.USER).size());
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void 只有内置时resolve返回BUILTIN() {
        Catalog c = Catalog.load(root);
        assertEquals(Source.BUILTIN, c.resolve("Explore").orElseThrow().source());
    }

    @Test
    void 坏文件跳过_好文件不受影响() throws Exception {
        Path agents = root.resolve(".cortex/agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("bad.md"), "---\nname: 缺 description 也缺闭合\nbody");
        Files.writeString(agents.resolve("good.md"), """
                ---
                name: good-one
                description: 正常定义
                ---
                正文。
                """);
        Catalog c = Catalog.load(root);
        assertTrue(c.resolve("good-one").isPresent(), "坏文件不应阻断其他文件（F7）");
        // bad.md 的 name 行本身非法（中文名），应被跳过而非加载
        assertTrue(c.resolve("general-purpose").isPresent());
    }

    @Test
    void forkDefinition返回isFork临时定义() {
        Catalog c = Catalog.load(root);
        Definition fork = c.forkDefinition();
        assertTrue(fork.isFork());
        assertEquals(Definition.FORK_NAME, fork.name());
        assertTrue(fork.systemPrompt().isEmpty(), "Fork 走继承系统提示");
        assertTrue(fork.tools().isEmpty());
        assertTrue(fork.disallowedTools().isEmpty(), "Fork 工具集继承父（含 Agent 工具，AC5）");
    }

    @Test
    void resolve未知名字返回empty() {
        Catalog c = Catalog.load(root);
        assertTrue(c.resolve("non-existent").isEmpty());
        assertTrue(c.resolve("").isEmpty());
        assertTrue(c.resolve(null).isEmpty());
    }
}
