package com.cortex.skill;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 技能编目中心（F1/F2）：两层目录扫描（用户全局 ~/.cortex/skills → 项目 .cortex/skills，后者同名覆盖），
 * LinkedHashMap 保序；phase-1 加载只读 meta（N2），phase-2 由 {@link #getFull} 按需重读正文（F4 热更新）。
 * 单技能解析失败 / 目录缺失 / IO 异常只跳过不中断其它技能（N1）。
 */
public final class SkillCatalog {

    private static final Logger LOG = Logger.getLogger(SkillCatalog.class.getName());

    private static final String SKILL_MD = "SKILL.md";
    private static final String SKILL_YAML = "skill.yaml";
    private static final String PROMPT_MD = "prompt.md";

    /** name → Skill；LinkedHashMap 保序（F1）。 */
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    /** name → 来源层级；与 skills 同序。 */
    private final Map<String, SkillSource> sources = new LinkedHashMap<>();

    /** 注册/覆盖一条技能（N6：同名覆盖，调用方按 tier 顺序决定优先级）。 */
    public void register(Skill skill, SkillSource source) {
        skills.put(skill.meta().name(), skill);
        sources.put(skill.meta().name(), source);
    }

    /** 按 name 取技能（phase-1 缓存，正文可能未加载）。 */
    public Skill get(String name) {
        return skills.get(name);
    }

    /**
     * 取技能并保证正文已加载（F4）：sourceDir 非空时每次重读 body（编辑即生效），
     * 读失败保留旧缓存，避免读到编辑中的半成品；sourceDir 为 null 直接返回缓存。
     */
    public Skill getFull(String name) {
        Skill cached = skills.get(name);
        if (cached == null || cached.sourceDir() == null) {
            return cached;
        }
        try {
            Skill fresh = cached.withBody(readBody(cached.sourceDir()));
            skills.put(name, fresh);
            return fresh;
        } catch (IOException e) {
            return cached;
        }
    }

    /** 全部技能（加载顺序 = user 层在前、项目层在后，同层按目录名字典序）。 */
    public List<Skill> list() {
        return List.copyOf(skills.values());
    }

    /** 技能来源层级；未注册返回 null。 */
    public SkillSource source(String name) {
        return sources.get(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(skills.keySet());
    }

    /**
     * 两层加载（F2）：tier1 用户全局 → tier2 项目；同名技能项目层覆盖用户层。
     * 已有内容整体清空后重扫。
     */
    public void loadCatalog(Path workDir) {
        loadCatalog(workDir, userSkillsDir());
    }

    /** 指定用户层目录的加载（测试注入用）。 */
    public void loadCatalog(Path workDir, Path userSkills) {
        skills.clear();
        sources.clear();
        loadTier(userSkills, SkillSource.USER);
        loadTier(workDir.resolve(".cortex").resolve("skills"), SkillSource.PROJECT);
    }

    /** reload 等价于整体重扫（InstallSkill 安装后调用）。 */
    public void reload(Path workDir) {
        loadCatalog(workDir);
    }

    /** 只加载单个目录（测试 / 指定层加载用）。目录不存在静默返回。 */
    public void loadFromDirectory(Path dir, SkillSource source) {
        loadTier(dir, source);
    }

    // ─── 内部：目录扫描 ───

    private static Path userSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".cortex", "skills");
    }

    /** 容错扫描一层目录（N1）：目录缺失 / 不可读 / 单技能解析失败都跳过，不中断其它技能。 */
    private void loadTier(Path dir, SkillSource source) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        List<Path> subDirs;
        try (Stream<Path> stream = Files.list(dir)) {
            subDirs = stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            LOG.warning("技能目录不可读,跳过: " + dir + " (" + e.getMessage() + ")");
            return;
        }
        for (Path sub : subDirs) {
            try {
                Skill skill = loadSkill(sub);
                if (skill != null) {
                    register(skill, source);
                }
            } catch (Exception e) {
                LOG.warning("技能解析失败,跳过 " + sub + ": " + e.getMessage());
            }
        }
    }

    /**
     * 单技能加载（F3）二选一：优先 {@code skill.yaml + prompt.md}（phase-1 只读 yaml meta，N2），
     * 否则 {@code SKILL.md}（可选 frontmatter + body）；两者都不存在返回 null。
     */
    Skill loadSkill(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve(SKILL_YAML))) {
            return loadFromYamlAndPrompt(dir);
        }
        if (Files.isRegularFile(dir.resolve(SKILL_MD))) {
            return parseSkillMD(dir);
        }
        return null;
    }

    /** skill.yaml + prompt.md 形态：phase-1 只解析 yaml meta，正文留给 getFull（bodyLoaded=false，N2）。 */
    private Skill loadFromYamlAndPrompt(Path dir) throws IOException {
        String yamlText = Files.readString(dir.resolve(SKILL_YAML));
        Map<String, Object> map = parseYamlOrEmpty(yamlText);
        SkillMeta meta = metaFromMap(map, dirName(dir));
        return new Skill(meta, null, dir, false);
    }

    /**
     * SKILL.md 形态（F3）：可选 YAML frontmatter（两行 {@code ---} 之间）+ body。
     * YAML 解析失败降级为「无 frontmatter」（N3）；frontmatter 缺 description 时回退 body 第一行非标题行。
     */
    Skill parseSkillMD(Path dir) throws IOException {
        String text = Files.readString(dir.resolve(SKILL_MD));
        Map<String, Object> front = null;
        String body = text;
        if (text.startsWith("---")) {
            int end = findFrontmatterEnd(text);
            if (end >= 0) {
                String yamlText = text.substring(3, end);
                Map<String, Object> parsed = parseYamlOrEmpty(yamlText);
                if (parsed != null && !parsed.isEmpty()) {
                    front = parsed;
                    body = text.substring(end + 3);
                }
            }
        }
        if (front == null) {
            front = Map.of();
        }
        SkillMeta meta = metaFromMap(front, dirName(dir));
        if (meta.description() == null || meta.description().isBlank()) {
            meta = withFallbackDescription(meta, body);
        }
        return new Skill(meta, body.strip(), dir, true);
    }

    // ─── 内部：meta 绑定 ───

    /**
     * Map → SkillMeta（F5）：name 缺省取目录名小写化并把空格换 {@code -}；
     * mode 缺省 inline 且兼容 {@code context: fork}；fork_context 缺省 none；
     * {@code allowed_tools}/{@code when_to_use} 下划线键与驼峰键都识别。
     */
    SkillMeta metaFromMap(Map<String, Object> map, String dirName) {
        String name = str(map.get("name"));
        if (name == null || name.isBlank()) {
            name = dirName.toLowerCase().replace(' ', '-');
        }
        String mode = str(map.get("mode"));
        if (mode == null || mode.isBlank()) {
            // 向后兼容旧写法 context: fork（F5）
            mode = "fork".equalsIgnoreCase(str(map.get("context"))) ? "fork" : null;
        }
        return new SkillMeta(
                name.strip(),
                str(map.get("description")),
                firstNonBlank(str(map.get("when_to_use")), str(map.get("whenToUse"))),
                strList(map.get("tags")),
                strList(map.get("allowed_tools")),
                mode,
                str(map.get("model")),
                str(map.get("fork_context")));
    }

    /** 缺描述时回退：body 第一行非空且非 Markdown 标题的行。 */
    private static SkillMeta withFallbackDescription(SkillMeta meta, String body) {
        String fallback = "";
        for (String line : body.split("\n", -1)) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                fallback = t;
                break;
            }
        }
        return new SkillMeta(meta.name(), fallback, meta.whenToUse(), meta.tags(),
                meta.allowedTools(), meta.mode(), meta.model(), meta.forkContext());
    }

    // ─── Active Skills 上下文 ───

    /**
     * 拼「## Active Skills」段（F6/T6）：每个激活技能一节 {@code ### name} + 正文；
     * 集合为空或无命中返回空串。
     */
    public String buildActiveContext(Set<String> activeSkillNames) {
        if (activeSkillNames == null || activeSkillNames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## Active Skills\n");
        for (String name : skills.keySet()) {
            if (!activeSkillNames.contains(name)) {
                continue;
            }
            Skill s = skills.get(name);
            sb.append("\n### ").append(name).append("\n\n");
            if (s.promptBody() != null) {
                sb.append(s.promptBody().strip()).append("\n");
            }
        }
        return sb.length() == "## Active Skills\n".length() ? "" : sb.toString();
    }

    // ─── 启动期白名单校验（Plan：validateTools → warning + 移除）───

    /** 校验 allowed_tools 是否都已注册；返回不通过的技能名集合（去重，空 = 全部通过）。 */
    public Set<String> validateTools(com.cortex.tool.ToolRegistry registry) {
        Set<String> bad = new java.util.LinkedHashSet<>();
        for (Skill s : skills.values()) {
            for (String tool : s.meta().allowedTools()) {
                if (registry.get(tool).isEmpty()) {
                    bad.add(s.meta().name());
                }
            }
        }
        return bad;
    }

    /** 移除一条技能（validateTools 不通过时由调用方使用）。 */
    public void remove(String name) {
        skills.remove(name);
        sources.remove(name);
    }

    // ─── 静态工具 ───

    /** 读某技能目录的最新正文：prompt.md 优先，否则 SKILL.md 去 frontmatter 后的 body。 */
    static String readBody(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve(PROMPT_MD))) {
            return Files.readString(dir.resolve(PROMPT_MD));
        }
        Path md = dir.resolve(SKILL_MD);
        if (Files.isRegularFile(md)) {
            String text = Files.readString(md);
            if (text.startsWith("---")) {
                int end = findFrontmatterEnd(text);
                if (end >= 0) {
                    return text.substring(end + 3).strip();
                }
            }
            return text.strip();
        }
        throw new IOException("技能目录缺少 SKILL.md / prompt.md: " + dir);
    }

    /** frontmatter 结束行（第二行 {@code ---}）起始偏移；未闭合返回 -1。 */
    private static int findFrontmatterEnd(String text) {
        String[] lines = text.split("\n", -1);
        int offset = lines[0].length() + 1; // 跳过首行 "---"
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                return offset;
            }
            offset += lines[i].length() + 1;
        }
        return -1;
    }

    /** SnakeYAML 解析；任何失败返回空 Map（N3 降级为无 frontmatter）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYamlOrEmpty(String yamlText) {
        try {
            Object obj = new Yaml().load(yamlText);
            return obj instanceof Map ? (Map<String, Object>) obj : Map.of();
        } catch (Exception e) {
            LOG.warning("YAML 解析失败,按无 frontmatter 处理: " + e.getMessage());
            return Map.of();
        }
    }

    private static String dirName(Path dir) {
        Path p = dir.getFileName();
        return p == null ? dir.toString() : p.toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item).strip());
            }
        }
        return out;
    }
}
