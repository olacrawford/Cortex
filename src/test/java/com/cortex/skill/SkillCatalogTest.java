package com.cortex.skill;

import com.cortex.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillCatalogTest {

    @TempDir
    Path tmp;

    @TempDir
    Path userHome; // 用户层技能目录注入点，避免污染真实 ~/.cortex

    // ─── 单技能解析 ───

    @Test
    void parseSkillMD_带frontmatter() throws IOException {
        Path dir = skillDir(tmp, "demo",
                "---\nname: demo\ndescription: 演示技能\nallowed_tools:\n  - read_file\n  - bash\nmode: inline\n---\n按步骤执行演示。");
        Skill s = new SkillCatalog().parseSkillMD(dir);
        assertEquals("demo", s.meta().name());
        assertEquals("演示技能", s.meta().description());
        assertEquals(java.util.List.of("read_file", "bash"), s.meta().allowedTools());
        assertEquals("inline", s.meta().mode());
        assertEquals("按步骤执行演示。", s.promptBody());
        assertTrue(s.bodyLoaded());
        assertEquals("none", s.meta().forkContext());
        assertEquals(dir, s.sourceDir());
    }

    @Test
    void parseSkillMD_无frontmatter时描述回退到body第一行非标题() throws IOException {
        Path dir = skillDir(tmp, "note", "# 标题\n\n这是描述行。\n\n正文。");
        Skill s = new SkillCatalog().parseSkillMD(dir);
        assertEquals("note", s.meta().name(), "name 缺省取目录名");
        assertEquals("这是描述行。", s.meta().description(), "描述回退到 body 第一行非标题行");
    }

    @Test
    void parseSkillMD_坏yaml降级为无frontmatter() throws IOException {
        Path dir = skillDir(tmp, "broken", "---\nname: [未闭合\n---\n正文内容。");
        Skill s = new SkillCatalog().parseSkillMD(dir);
        assertEquals("broken", s.meta().name(), "坏 YAML 不抛异常（N3），目录名兜底");
        assertTrue(s.promptBody().contains("正文内容"));
    }

    @Test
    void parseSkillMD_contextFork向后兼容() throws IOException {
        Path dir = skillDir(tmp, "old-style", "---\nname: old-style\ndescription: 旧写法\ncontext: fork\n---\nbody");
        Skill s = new SkillCatalog().parseSkillMD(dir);
        assertEquals("fork", s.meta().mode());
        assertTrue(s.meta().isFork());
        assertEquals("none", s.meta().forkContext(), "fork_context 缺省 none");
    }

    @Test
    void parseSkillMD_目录名空格转连字符() throws IOException {
        Path dir = skillDir(tmp, "My Skill", "---\ndescription: 带空格目录名\n---\nbody");
        Skill s = new SkillCatalog().parseSkillMD(dir);
        assertEquals("my-skill", s.meta().name(), "name 缺省取目录名小写化并把空格换 -（F5）");
    }

    @Test
    void loadSkill_yaml加prompt优先于SKILLmd() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("both"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: both\ndescription: md版\n---\nmd 正文");
        Files.writeString(dir.resolve("skill.yaml"), "name: both\ndescription: yaml版\nallowed_tools:\n  - read_file\n");
        Files.writeString(dir.resolve("prompt.md"), "yaml 形态正文");

        SkillCatalog catalog = new SkillCatalog();
        Skill s = catalog.loadSkill(dir);
        assertEquals("yaml版", s.meta().description(), "skill.yaml + prompt.md 优先（F3）");
        assertNull(s.promptBody(), "phase-1 不读 prompt.md（N2）");
        assertFalse(s.bodyLoaded());

        catalog.register(s, SkillSource.PROJECT);
        Skill full = catalog.getFull("both");
        assertNotNull(full.promptBody());
        assertEquals("yaml 形态正文", full.promptBody(), "getFull 按需加载正文（F4）");
        assertTrue(full.bodyLoaded());
    }

    @Test
    void loadSkill_两者都缺返回null() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("empty-skill"));
        Files.writeString(dir.resolve("README.md"), "不是技能");
        assertNull(new SkillCatalog().loadSkill(dir));
    }

    // ─── 两层加载与覆盖 ───

    @Test
    void loadCatalog_项目层覆盖用户层_顺序保序() throws IOException {
        Path workDir = Files.createDirectories(tmp.resolve("ws"));
        Path userSkills = Files.createDirectories(userHome.resolve("skills"));
        Path projSkills = Files.createDirectories(workDir.resolve(".cortex/skills"));

        skillDir(userSkills, "dup", "---\nname: dup\ndescription: 用户层版本\n---\n用户层正文");
        skillDir(userSkills, "demo-user", "---\nname: demo-user\ndescription: 仅用户层\n---\n正文");
        skillDir(projSkills, "dup", "---\nname: dup\ndescription: 项目层版本\n---\n项目层正文");
        skillDir(projSkills, "proj-only", "---\nname: proj-only\ndescription: 仅项目层\n---\n正文");

        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(workDir, userSkills);
        assertEquals("项目层版本", catalog.get("dup").meta().description(), "项目层覆盖用户层（F2）");
        assertEquals(SkillSource.PROJECT, catalog.source("dup"));
        assertEquals(SkillSource.USER, catalog.source("demo-user"));
        // 保序（LinkedHashMap）：user 层在前、项目层在后
        var names = catalog.list().stream().map(s -> s.meta().name()).toList();
        assertTrue(names.indexOf("demo-user") < names.indexOf("proj-only"));
    }

    @Test
    void loadTier_目录不存在静默跳过() {
        SkillCatalog catalog = new SkillCatalog();
        assertDoesNotThrow(() -> catalog.loadFromDirectory(tmp.resolve("不存在的目录"), SkillSource.PROJECT));
        assertEquals(0, catalog.list().size());
    }

    @Test
    void loadTier_单技能解析失败不中断其它技能() throws IOException {
        Path skills = Files.createDirectories(tmp.resolve("skills"));
        skillDir(skills, "good", "---\nname: good\ndescription: 好技能\n---\n正文");
        // SKILL.md 是目录 → readString 抛 IOException，被 loadTier 按技能粒度吞掉
        Files.createDirectories(skills.resolve("bad-dir").resolve("SKILL.md"));

        SkillCatalog catalog = new SkillCatalog();
        catalog.loadFromDirectory(skills, SkillSource.PROJECT);
        assertNotNull(catalog.get("good"), "坏技能不中断其它技能（N1）");
        assertNull(catalog.get("bad-dir"));
    }

    @Test
    void getFull_热重载与读失败保留旧缓存() throws IOException {
        Path dir = skillDir(tmp, "hot", "---\nname: hot\ndescription: v1\n---\n正文第一版");
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(catalog.parseSkillMD(dir), SkillSource.PROJECT);
        assertEquals("正文第一版", catalog.getFull("hot").promptBody());

        // 修改 SKILL.md → getFull 立即重读（F4 热更新）
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: hot\ndescription: v2\n---\n正文第二版");
        assertEquals("正文第二版", catalog.getFull("hot").promptBody());

        // 读失败（文件被删）→ 保留旧缓存
        Files.delete(dir.resolve("SKILL.md"));
        assertEquals("正文第二版", catalog.getFull("hot").promptBody(), "读失败保留旧缓存（F4）");

        // sourceDir 为 null → 直接返回缓存
        catalog.register(new Skill(catalog.get("hot").meta(), "无源缓存正文", null, true), SkillSource.PROJECT);
        assertEquals("无源缓存正文", catalog.getFull("hot").promptBody());
    }

    @Test
    void buildActiveContext_空集合返回空串_命中拼段() throws IOException {
        SkillCatalog catalog = new SkillCatalog();
        assertEquals("", catalog.buildActiveContext(Set.of()));
        catalog.register(catalog.parseSkillMD(
                skillDir(tmp, "a-skill", "---\nname: a-skill\ndescription: d\n---\nA 的正文")), SkillSource.PROJECT);
        String ctx = catalog.buildActiveContext(Set.of("a-skill", "不存在的"));
        assertTrue(ctx.startsWith("## Active Skills"));
        assertTrue(ctx.contains("### a-skill"));
        assertTrue(ctx.contains("A 的正文"));
        assertFalse(ctx.contains("不存在的"));
    }

    @Test
    void validateTools_引用未注册工具被识别并可移除() throws IOException {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(catalog.parseSkillMD(skillDir(tmp, "bad",
                "---\nname: bad\ndescription: d\nallowed_tools:\n  - 不存在的工具\n---\nb")), SkillSource.PROJECT);
        catalog.register(catalog.parseSkillMD(skillDir(tmp, "good",
                "---\nname: good\ndescription: d\nallowed_tools:\n  - read_file\n---\nb")), SkillSource.PROJECT);

        ToolRegistry registry = ToolRegistry.createDefault();
        assertEquals(Set.of("bad"), catalog.validateTools(registry));
        catalog.remove("bad");
        assertTrue(catalog.validateTools(registry).isEmpty());
        assertNull(catalog.get("bad"));
        assertNotNull(catalog.get("good"));
    }

    // ─── 辅助 ───

    private static Path skillDir(Path root, String name, String content) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("SKILL.md"), content);
        return dir;
    }
}
