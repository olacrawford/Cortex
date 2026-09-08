package com.cortex.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cortex.llm.ToolCall;
import org.yaml.snakeyaml.Yaml;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单个权限配置文件（F4）：defaultMode + permissions.allow/deny 规则串列表。
 * 加载失败（缺失/格式非法）一律降级为空配置，绝不抛异常致引擎构造失败（N5）。
 */
public record Settings(String defaultMode, List<String> allow, List<String> deny) {

    public static Settings empty() {
        return new Settings(null, List.of(), List.of());
    }

    /** 加载单个 YAML 配置；缺失或解析失败 → empty（N5 降级，不额外放开权限）。 */
    public static Settings load(Path path) {
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        try {
            String text = Files.readString(path);
            Object loaded = new Yaml().load(new StringReader(text));
            if (!(loaded instanceof Map<?, ?> map)) {
                return empty();
            }
            String defaultMode = map.get("defaultMode") instanceof String s ? s : null;
            return new Settings(
                    defaultMode,
                    stringList(map.get("permissions"), "allow"),
                    stringList(map.get("permissions"), "deny"));
        } catch (Exception e) {
            return empty(); // 格式非法/IO 失败：降级为空，不致错
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object permissions, String key) {
        if (!(permissions instanceof Map<?, ?> permMap)) {
            return List.of();
        }
        Object list = ((Map<String, Object>) permMap).get(key);
        if (!(list instanceof List<?> raw)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof String s && !s.isBlank()) {
                result.add(s.strip());
            }
        }
        return List.copyOf(result);
    }

    /** 把规则串列表解析为规则集；非法条目跳过（N5）。 */
    public static RuleSet toRuleSet(Settings s) {
        List<Rule> allow = new ArrayList<>();
        List<Rule> deny = new ArrayList<>();
        for (String text : s.allow()) {
            Rule.parse(text, true).ifPresent(allow::add);
        }
        for (String text : s.deny()) {
            Rule.parse(text, false).ifPresent(deny::add);
        }
        return new RuleSet(allow, deny);
    }

    /** 内部工具名 → 面向用户的友好名；未知原样返回（F3/AC4）。 */
    public static String friendlyName(String internal) {
        return switch (internal) {
            case "bash" -> "Bash";
            case "read_file" -> "Read";
            case "write_file" -> "Write";
            case "edit_file" -> "Edit";
            case "glob" -> "Glob";
            case "grep" -> "Grep";
            default -> internal;
        };
    }

    /**
     * 类别判定（N7 最严）：readOnly=true 一律 READ（优先于名字）；
     * write_file/edit_file → WRITE；其余（含 bash、未知工具）→ EXEC。
     */
    public static Category categorize(String internal, boolean readOnly) {
        if (readOnly) {
            return Category.READ;
        }
        return switch (internal) {
            case "write_file", "edit_file" -> Category.WRITE;
            default -> Category.EXEC;
        };
    }

    /**
     * 沙箱/规则匹配的目标信息：文件类取 path（glob/grep 取搜索根，空 → "."）；Bash 取 command。
     * ok=false 表示解析失败或缺必填字段（调用方按最严处理，N7/AC15）。
     */
    public record TargetInfo(String target, boolean isFile, boolean ok) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static TargetInfo extractTarget(ToolCall call) {
        return switch (call.name()) {
            case "read_file", "write_file", "edit_file" -> fileTarget(call, false);
            case "glob", "grep" -> fileTarget(call, true); // 沙箱只围栏搜索根 path；缺省 "."
            case "bash" -> {
                try {
                    var node = MAPPER.readTree(call.args() == null || call.args().isBlank() ? "{}" : call.args());
                    yield new TargetInfo(node.path("command").asText(""), false, true);
                } catch (Exception e) {
                    yield new TargetInfo("", false, false);
                }
            }
            default -> new TargetInfo("", false, false); // 未知工具
        };
    }

    /** pathOptional=true（glob/grep）：path 缺省视为 "."；false（read/write/edit）：path 必填，缺失按最严拒绝。 */
    private static TargetInfo fileTarget(ToolCall call, boolean pathOptional) {
        try {
            var node = MAPPER.readTree(call.args() == null || call.args().isBlank() ? "{}" : call.args());
            String path = node.has("path") && node.get("path").isTextual() ? node.get("path").asText() : "";
            if (path.isBlank()) {
                if (pathOptional) {
                    return new TargetInfo(".", true, true);
                }
                return new TargetInfo("", true, false);
            }
            return new TargetInfo(path, true, true);
        } catch (Exception e) {
            return new TargetInfo("", true, false);
        }
    }

    /** 转义命令串中的 glob 元字符，保证「永久放行」生成的精确规则不被泛化。 */
    public static String escapeGlob(String command) {
        StringBuilder sb = new StringBuilder();
        for (char c : command.toCharArray()) {
            if (c == '*' || c == '?' || c == '[' || c == ']') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public Settings {
        allow = allow == null ? List.of() : List.copyOf(allow);
        deny = deny == null ? List.of() : List.copyOf(deny);
    }

    /** 供 Persister 写回用：构造可变副本。 */
    public Settings withAllowRule(String ruleText) {
        List<String> merged = new ArrayList<>(allow);
        if (!merged.contains(ruleText)) {
            merged.add(ruleText);
        }
        return new Settings(defaultMode, merged, deny);
    }

    /** 序列化为 YAML 文本（Persister 写本地层文件用）。 */
    public String toYaml() {
        StringBuilder sb = new StringBuilder();
        if (defaultMode != null && !defaultMode.isEmpty()) {
            sb.append("defaultMode: ").append(defaultMode).append('\n');
        }
        sb.append("permissions:\n");
        sb.append("  allow:\n");
        for (String a : allow) {
            sb.append("    - \"").append(a.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
        }
        sb.append("  deny:\n");
        for (String d : deny) {
            sb.append("    - \"").append(d.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
        }
        return sb.toString();
    }
}
