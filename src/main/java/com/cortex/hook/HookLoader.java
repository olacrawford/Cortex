package com.cortex.hook;

import com.cortex.permission.Matcher;
import com.cortex.permission.Matchers;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * hooks.yaml 加载器（F6-F8）：项目级 {@code <root>/.cortex/hooks.yaml} + 用户级
 * {@code ~/.cortex/hooks.yaml} 叠加合并（同名 hook 跳过后到者，F7）。
 * 所有加载错误（YAML 非法、字段缺失、event 未知、async+拦截事件、正则编译失败等）
 * 一律 stderr 输出后继续，不阻断进程（G2/N1/N9）。
 */
public final class HookLoader {

    private HookLoader() {}

    /** 主入口：双层加载并构造引擎。 */
    public static HookEngine load(Path projectRoot) {
        Path projectFile = projectRoot.resolve(".cortex").resolve("hooks.yaml");
        Path userFile = Path.of(System.getProperty("user.home"), ".cortex", "hooks.yaml");
        return load(projectFile, userFile);
    }

    /** 指定两个候选文件加载（测试注入用）：项目级先加载、用户级后加载，同名冲突跳过后到者（F7）。 */
    public static HookEngine load(Path userFile, Path projectFile) {
        List<HookRule> rules = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (FileSource fs : List.of(new FileSource(projectFile, "project"), new FileSource(userFile, "user"))) {
            if (!Files.isRegularFile(fs.path())) {
                continue;
            }
            List<HookRule> parsed = parseFile(fs.path());
            List<HookRule> accepted = new ArrayList<>();
            for (HookRule rule : parsed) {
                if (!seenNames.add(rule.name())) {
                    System.err.printf("hook \"%s\": duplicate name in %s hooks.yaml, skipped%n",
                            rule.name(), fs.tier());
                    continue;
                }
                accepted.add(rule);
            }
            if (!accepted.isEmpty()) {
                rules.addAll(accepted);
                sources.add(fs.path().toString());
            }
        }
        return new HookEngine(rules, sources, new HookExecutor());
    }

    private record FileSource(Path path, String tier) {}

    /** 解析单个 yaml 文件；整体解析失败只 stderr 一行（N9）。 */
    private static List<HookRule> parseFile(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (Exception e) {
            System.err.printf("hooks.yaml unreadable (%s): %s, skipped%n", file, e.getMessage());
            return List.of();
        }
        Object rootObj;
        try {
            rootObj = new Yaml().load(text);
        } catch (Exception e) {
            System.err.printf("hooks.yaml parse failed (%s): %s, skipped%n", file, e.getMessage());
            return List.of();
        }
        if (!(rootObj instanceof Map<?, ?> root) || !(root.get("hooks") instanceof List<?> rawHooks)) {
            System.err.printf("hooks.yaml has no \"hooks\" list (%s), skipped%n", file);
            return List.of();
        }
        List<HookRule> rules = new ArrayList<>();
        int idx = 0;
        for (Object o : rawHooks) {
            idx++;
            if (!(o instanceof Map<?, ?> raw)) {
                System.err.printf("hook #%d in %s is not a mapping, skipped%n", idx, file);
                continue;
            }
            compileRule(file.toString(), idx, cast(raw)).ifPresent(rules::add);
        }
        return rules;
    }

    /**
     * 单条 hook 编译（F8）：字段校验 + matcher 构造；任何问题 stderr 一行后返回 empty（N1）。
     */
    private static Optional<HookRule> compileRule(String source, int idx, Map<String, Object> raw) {
        String where = "hook #" + idx + " in " + source;
        String name = str(raw.get("name"));
        if (name == null || name.isBlank()) {
            System.err.printf("%s: missing name, skipped%n", where);
            return Optional.empty();
        }
        where = "hook \"" + name + "\"";

        Optional<Event> event = Event.parse(str(raw.get("event")));
        if (event.isEmpty()) {
            System.err.printf("%s: unknown event \"%s\", skipped%n", name, str(raw.get("event")));
            return Optional.empty();
        }

        // async + 拦截事件冲突（F28/AC8）
        boolean async = Boolean.TRUE.equals(raw.get("async"));
        if (async && event.get().isBlocking()) {
            System.err.printf("%s: async not allowed for blocking events, skipped%n", where);
            return Optional.empty();
        }

        // 条件（F11-F14）
        Condition condition = null;
        Object rawIf = raw.get("if");
        if (rawIf != null) {
            if (!(rawIf instanceof Map<?, ?> ifMap)) {
                System.err.printf("%s: \"if\" must be a mapping, skipped%n", where);
                return Optional.empty();
            }
            condition = Condition.fromMap(cast(ifMap), HookLoader::compileMatcher);
            if (condition == null) {
                System.err.printf("%s: invalid condition (all_of/any_of 互斥且各 atom 需 field+match), skipped%n", where);
                return Optional.empty();
            }
        }

        // 动作（F16/F26）
        Action action = compileAction(name, raw.get("action"));
        if (action == null) {
            return Optional.empty();
        }

        boolean onlyOnce = Boolean.TRUE.equals(raw.get("only_once"));
        Duration timeout = parseTimeout(name, str(raw.get("timeout")));

        return Optional.of(new HookRule(name.strip(), event.get(), condition, action,
                onlyOnce, async, timeout, source));
    }

    /** 动作对象编译：type 分派 + 各类型必填子字段校验（F16/F26）。 */
    private static Action compileAction(String name, Object rawAction) {
        if (!(rawAction instanceof Map<?, ?> m)) {
            System.err.printf("hook \"%s\": missing action, skipped%n", name);
            return null;
        }
        Map<String, Object> a = cast(m);
        String type = str(a.get("type"));
        switch (type == null ? "" : type) {
            case "shell" -> {
                String command = str(a.get("command"));
                if (command == null || command.isBlank()) {
                    return warnAction(name, "shell action requires \"command\"");
                }
                return new Action.Shell(command);
            }
            case "prompt" -> {
                String text = str(a.get("text"));
                if (text == null) {
                    return warnAction(name, "prompt action requires \"text\"");
                }
                return new Action.Prompt(text);
            }
            case "http" -> {
                String url = str(a.get("url"));
                if (url == null || url.isBlank()) {
                    return warnAction(name, "http action requires \"url\"");
                }
                Map<String, String> headers = new LinkedHashMap<>();
                if (a.get("headers") instanceof Map<?, ?> h) {
                    for (Map.Entry<?, ?> e : h.entrySet()) {
                        headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
                return new Action.Http(url, str(a.get("method")), headers, str(a.get("body")));
            }
            case "subagent" -> {
                String agentName = str(a.get("agent_name"));
                String prompt = str(a.get("prompt"));
                if (agentName == null || agentName.isBlank() || prompt == null) {
                    return warnAction(name, "subagent action requires \"agent_name\" and \"prompt\"");
                }
                return new Action.Subagent(agentName, prompt);
            }
            default -> {
                return warnAction(name, "unknown action type \"" + type + "\"");
            }
        }
    }

    private static Action warnAction(String name, String reason) {
        System.err.printf("hook \"%s\": %s, skipped%n", name, reason);
        return null;
    }

    /** 条件 match 对象 → Matcher（F14）：type ∈ exact/glob/regex/not；hook 上下文统一路径 glob 语义。 */
    private static Matcher compileMatcher(Object rawMatch) {
        if (!(rawMatch instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, Object> match = cast(m);
        String type = str(match.get("type"));
        String value = str(match.get("value"));
        try {
            return switch (type == null ? "" : type) {
                case "exact" -> value == null ? null : new Matcher.ExactMatcher(value);
                case "glob" -> value == null ? null : Matchers.compile(value, false);
                case "regex" -> value == null ? null : Matchers.compile("~" + value, false);
                case "not" -> {
                    Object inner = match.get("inner");
                    if (!(inner instanceof Map<?, ?> innerMap)) {
                        yield null;
                    }
                    Matcher compiled = compileMatcher(innerMap);
                    yield compiled == null ? null : new Matcher.NotMatcher(compiled);
                }
                default -> null;
            };
        } catch (Matchers.MatcherCompileException e) {
            return null; // 正则编译失败等按加载错误处理（F14）
        }
    }

    /** 时长串解析：{@code 30s} / {@code 500ms} / {@code 2m}；缺省 30s（F8）。 */
    private static Duration parseTimeout(String name, String text) {
        if (text == null || text.isBlank()) {
            return HookRule.DEFAULT_TIMEOUT;
        }
        String t = text.strip().toLowerCase();
        try {
            if (t.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2)));
            }
            if (t.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(t.substring(0, t.length() - 1)));
            }
            if (t.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(t.substring(0, t.length() - 1)));
            }
        } catch (NumberFormatException ignored) {
            // 落到下面的报错
        }
        System.err.printf("hook \"%s\": invalid timeout \"%s\", using default 30s%n", name, text);
        return HookRule.DEFAULT_TIMEOUT;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
