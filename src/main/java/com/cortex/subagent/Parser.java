package com.cortex.subagent;

import com.cortex.permission.Mode;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent 定义文件解析器（F4）：Markdown = {@code ---} frontmatter（YAML）+ 正文（子 Agent 系统提示）。
 * 必填字段缺失 / frontmatter 未闭合抛 {@link ParserException}（N4：非内置文件仅 stderr 警告跳过）；
 * model / permissionMode / maxTurns 非法值走 stderr 警告并降级默认值，文件仍可用（AC22）。
 */
public final class Parser {

    /** 解析失败（结构性错误：缺必填字段 / frontmatter 未闭合 / YAML 非法）。 */
    public static final class ParserException extends RuntimeException {
        public ParserException(String message) {
            super(message);
        }
    }

    /** 角色名约束：字母开头，字母/数字/连字符/下划线，1-32（F4）。 */
    static final Pattern AGENT_NAME_REGEX = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,31}$");

    private static final Set<String> VALID_MODELS = Set.of("inherit", "haiku", "sonnet", "opus");

    private Parser() {}

    /** 从字节解析定义（classpath 资源与文件共用）。 */
    public static Definition parseDefinition(byte[] data, String filePath, Source source) {
        String text = stripBom(new String(data, StandardCharsets.UTF_8));
        Split split = splitFrontmatter(text);
        Map<String, Object> front = split.yaml == null ? Map.of() : parseYaml(split.yaml, filePath);
        return toDefinition(front, split.body, filePath, source);
    }

    /** 从文件解析定义。 */
    public static Definition parseFile(Path path, Source source) throws IOException {
        return parseDefinition(Files.readAllBytes(path), path.toAbsolutePath().toString(), source);
    }

    // ─── 内部 ───

    private static final record Split(String yaml, String body) {}

    /**
     * 切分 frontmatter 与正文：无 frontmatter 返回 (null, 全文)；
     * 未闭合 yaml 为空串（调用方对闭合性单独检查过——此处未闭合按异常处理）。
     */
    private static Split splitFrontmatter(String text) {
        if (!text.startsWith("---")) {
            return new Split(null, text.strip());
        }
        String[] lines = text.split("\n", -1);
        int yamlStart = lines[0].length() + 1; // 跳过首行 "---"
        int offset = yamlStart;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                String yaml = text.substring(yamlStart, offset);
                int bodyStart = Math.min(text.length(), offset + lines[i].length() + 1);
                return new Split(yaml, text.substring(bodyStart).strip());
            }
            offset += lines[i].length() + 1;
        }
        throw new ParserException("frontmatter 未闭合");
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String yaml, String filePath) {
        try {
            Object obj = new Yaml().load(yaml);
            return obj instanceof Map ? (Map<String, Object>) obj : Map.of();
        } catch (Exception e) {
            throw new ParserException("frontmatter YAML 解析失败: " + filePath + ": " + e.getMessage());
        }
    }

    /** frontmatter Map + body → Definition（含字段校验与降级，F4/AC22）。 */
    private static Definition toDefinition(Map<String, Object> front, String body, String filePath, Source source) {
        String name = str(front.get("name"));
        if (name == null || !AGENT_NAME_REGEX.matcher(name.strip()).matches()) {
            throw new ParserException("name 缺失或非法（需字母开头，1-32 位字母/数字/连字符/下划线）: " + filePath);
        }
        name = name.strip();
        String description = str(front.get("description"));
        if (description == null || description.isBlank()) {
            throw new ParserException("description 缺失: " + filePath);
        }

        String model = str(front.get("model"));
        if (model != null && !VALID_MODELS.contains(model.strip())) {
            System.err.printf("subagent \"%s\": unknown model \"%s\", fallback to inherit (%s)%n",
                    name, model, filePath);
            model = null;
        }
        model = model == null || model.isBlank() ? "inherit" : model.strip();

        int maxTurns = intOf(front.get("maxTurns"));
        if (maxTurns < 0) {
            System.err.printf("subagent \"%s\": 非法 maxTurns \"%s\", fallback 到全局默认 (%s)%n",
                    name, front.get("maxTurns"), filePath);
            maxTurns = 0;
        }

        boolean dontAsk = false;
        Mode mode = Mode.DEFAULT;
        String modeText = str(front.get("permissionMode"));
        if (modeText != null && !modeText.isBlank()) {
            if ("dontAsk".equalsIgnoreCase(modeText.strip())) {
                dontAsk = true;
            } else {
                Optional<Mode> parsed = Mode.parse(modeText);
                if (parsed.isPresent()) {
                    mode = parsed.get();
                } else {
                    System.err.printf("subagent \"%s\": unknown permissionMode \"%s\", fallback to default (%s)%n",
                            name, modeText, filePath);
                }
            }
        }

        boolean background = Boolean.TRUE.equals(front.get("background"));

        String isolation = str(front.get("isolation"));
        if (isolation != null && !isolation.isBlank()) {
            isolation = isolation.strip();
            if (!isolation.equals(Definition.ISOLATION_WORKTREE)) {
                System.err.printf("subagent \"%s\": unknown isolation \"%s\", fallback to none (%s)%n",
                        name, isolation, filePath);
                isolation = "";
            }
        } else {
            isolation = "";
        }

        return new Definition(name, description.strip(),
                strList(front.get("tools")), strList(front.get("disallowedTools")),
                model, maxTurns, mode, dontAsk, background,
                isolation, body, filePath, source);
    }

    private static String str(Object v) {
        return v instanceof String s ? s : null;
    }

    private static int intOf(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return v == null ? 0 : -1;
    }

    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s.strip());
            }
        }
        return List.copyOf(out);
    }
}
