package com.cortex.subagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Agent 定义编目中心（F5/F6/F8）：三层加载（内置 classpath → 用户级 ~/.cortex/agents →
 * 项目级 &lt;root&gt;/.cortex/agents），后加载的优先级高、同名覆盖；插件层本期恒为空。
 * 单个文件解析失败仅 stderr 警告并跳过，不阻断启动（F7/N4——内置级除外，见 BuiltinLoader）。
 * 实现 agent 包的窄接口 {@link com.cortex.agent.AgentCatalogPort} 供 Agent 工具使用。
 */
public final class Catalog implements com.cortex.agent.AgentCatalogPort {

    private final Object lock = new Object();
    /** name → 定义（插入顺序即覆盖顺序：builtin → user → project）。 */
    private final Map<String, Definition> defs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<Source, List<Definition>> bySource = new java.util.EnumMap<>(Source.class);

    /**
     * 顺序加载 builtin → user → project（同名高优先级覆盖）。
     * 任何一层无目录 / 全部解析失败都返回非 null 的可用 Catalog。
     *
     * @param root 项目根目录
     */
    public static Catalog load(Path root) {
        Catalog c = new Catalog();
        c.addAll(BuiltinLoader.builtinDefinitions());
        c.addAll(loadFromDir(Path.of(System.getProperty("user.home"), ".cortex", "agents"), Source.USER));
        c.addAll(loadFromDir(root.resolve(".cortex").resolve("agents"), Source.PROJECT));
        return c;
    }

    /** 按名解析定义（大小写不敏感）；无命中返回 empty。 */
    public Optional<Definition> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(defs.get(name.strip()));
        }
    }

    /** 全部定义，按 name 升序。 */
    public List<Definition> list() {
        synchronized (lock) {
            return List.copyOf(defs.values());
        }
    }

    /** 某来源层下的全部定义。 */
    public List<Definition> listBySource(Source source) {
        synchronized (lock) {
            return List.copyOf(bySource.getOrDefault(source, List.of()));
        }
    }

    /**
     * Fork 路径用的临时定义（F22/F24）：name=__fork__、systemPrompt 为空（子 Agent 继承主系统提示）、
     * tools/disallowedTools 均空（工具集继承父，保留 Agent 工具，靠 Fork 嵌套双闸拦截）。
     */
    public Definition forkDefinition() {
        return new Definition(Definition.FORK_NAME, "Fork-based subagent",
                List.of(), List.of(),
                "inherit", 0, com.cortex.permission.Mode.DEFAULT, false, false,
                "", "", "fork://inline", Source.BUILTIN);
    }

    // ─── 内部 ───

    private void addAll(List<Definition> additions) {
        synchronized (lock) {
            for (Definition d : additions) {
                defs.put(d.name(), d);
                bySource.computeIfAbsent(d.source(), k -> new ArrayList<>()).add(d);
            }
        }
    }

    /** 扫描目录下全部 .md 文件；目录不存在返回空表；单个文件失败 stderr 警告跳过（F7）。 */
    private static List<Definition> loadFromDir(Path dir, Source source) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Definition> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            out.add(Parser.parseFile(p, source));
                        } catch (Exception e) {
                            System.err.printf("subagent %s: %s, skipped%n", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("subagent 目录扫描失败 " + dir + ": " + e.getMessage());
        }
        return out;
    }
}
