package com.cortex.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * glob（F2-找）：按 glob 模式查找文件路径（自实现 ** 跨任意目录层级匹配，
 * JDK PathMatcher 的 glob:** 只支持单层），结果 ≤100 条、排序。
 */
public final class GlobTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MATCHES = 100;

    private record GlobArgs(String pattern, String path) {}

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "按 glob 模式查找文件，返回匹配的路径列表（按字典序，最多 100 条）。支持 ** 跨目录层级。";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("type", "string");
        pattern.put("description", "glob 模式，如 **/*.java、src/**/*.kt");
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "起始目录（可选，默认当前目录）");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", pattern);
        properties.put("path", path);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @Override
    public Result execute(String argsJson) {
        return execute(ToolContext.EMPTY, argsJson);
    }

    @Override
    public Result execute(ToolContext ctx, String argsJson) {
        GlobArgs args;
        try {
            args = MAPPER.readValue(argsJson, GlobArgs.class);
        } catch (Exception e) {
            return Result.error("参数解析失败: " + e.getMessage());
        }
        if (args.pattern() == null || args.pattern().isBlank()) {
            return Result.error("缺少必填参数: pattern");
        }
        Path root = ctx.resolvePath(args.path() == null || args.path().isBlank() ? "." : args.path()); // 阶段13：explicit cwd 优先（F17）
        if (!Files.isDirectory(root)) {
            return Result.error("路径不存在或不是目录: " + root);
        }
        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> !p.equals(root))
                    .filter(p -> matchGlob(args.pattern(), root.relativize(p).toString().replace('\\', '/')))
                    .sorted()
                    .limit(MAX_MATCHES)
                    .map(Path::toString)
                    .forEach(matches::add);
        } catch (IOException e) {
            return Result.error("glob 失败: " + e.getMessage());
        }
        if (matches.isEmpty()) {
            return Result.ok("无匹配");
        }
        String joined = String.join("\n", matches);
        return Result.ok(matches.size() >= MAX_MATCHES ? joined + "\n[truncated]" : joined);
    }

    /** 把 pattern 与路径按 / 切段匹配；** 匹配零个或多个段，其余段内支持 * 与 ?。 */
    static boolean matchGlob(String pattern, String path) {
        return matchSegments(pattern.split("/"), 0, path.split("/"), 0);
    }

    private static boolean matchSegments(String[] pat, int pi, String[] seg, int si) {
        if (pi == pat.length) {
            return si == seg.length;
        }
        if (pat[pi].equals("**")) {
            for (int i = si; i <= seg.length; i++) {
                if (matchSegments(pat, pi + 1, seg, i)) {
                    return true;
                }
            }
            return false;
        }
        if (si >= seg.length) {
            return false;
        }
        return segmentMatches(pat[pi], seg[si]) && matchSegments(pat, pi + 1, seg, si + 1);
    }

    private static boolean segmentMatches(String patSeg, String s) {
        StringBuilder re = new StringBuilder();
        for (char c : patSeg.toCharArray()) {
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return s.matches(re.toString());
    }
}
