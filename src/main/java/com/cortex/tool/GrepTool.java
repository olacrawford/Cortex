package com.cortex.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * grep（F2-搜）：在工作目录内按正则搜文件内容，返回 file:line:content 命中列表
 * （≤100 条）；支持按文件名 glob 过滤；超长行（>1MB）跳过并标注，避免假「无命中」。
 */
public final class GrepTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MATCHES = 100;
    private static final int MAX_LINE_CHARS = 1024 * 1024;

    private record GrepArgs(String pattern, String path, String glob) {}

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在文件内容中按正则搜索，返回 file:line:content 命中列表（最多 100 条）。"
                + "pattern 为 Java 正则语法；可选按文件名 glob 过滤（如 *.java）。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("type", "string");
        pattern.put("description", "搜索正则（Java Pattern 语法）");
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "起始目录（可选，默认当前目录）");
        Map<String, Object> glob = new LinkedHashMap<>();
        glob.put("type", "string");
        glob.put("description", "文件名过滤 glob（可选，如 *.java）");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", pattern);
        properties.put("path", path);
        properties.put("glob", glob);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @Override
    public Result execute(String argsJson) {
        GrepArgs args;
        try {
            args = MAPPER.readValue(argsJson, GrepArgs.class);
        } catch (Exception e) {
            return Result.error("参数解析失败: " + e.getMessage());
        }
        if (args.pattern() == null || args.pattern().isBlank()) {
            return Result.error("缺少必填参数: pattern");
        }
        Pattern regex;
        try {
            regex = Pattern.compile(args.pattern());
        } catch (PatternSyntaxException e) {
            return Result.error("正则非法: " + e.getMessage());
        }
        Path root = Path.of(args.path() == null || args.path().isBlank() ? "." : args.path());
        if (!Files.isDirectory(root)) {
            return Result.error("路径不存在或不是目录: " + root);
        }
        PathMatcher fileFilter = args.glob() == null || args.glob().isBlank()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + args.glob());

        List<String> hits = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.walk(root)) {
            var it = stream.filter(Files::isRegularFile).iterator();
            while (it.hasNext() && hits.size() < MAX_MATCHES) {
                Path p = it.next();
                if (fileFilter != null && !fileFilter.matches(p.getFileName())) {
                    continue;
                }
                scanFile(p, root, regex, hits);
            }
        } catch (IOException e) {
            return Result.error("grep 失败: " + e.getMessage());
        }
        if (hits.size() >= MAX_MATCHES) {
            truncated = true;
        }
        if (hits.isEmpty()) {
            return Result.ok("无命中");
        }
        String joined = String.join("\n", hits);
        if (truncated) {
            joined += "\n[truncated]";
        }
        return Result.ok(joined);
    }

    /** 扫描单个文件，把命中行以 file:line:content 追加到 hits。 */
    private void scanFile(Path p, Path root, Pattern regex, List<String> hits) {
        try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            int lineNo = 0;
            int overflowLines = 0;
            while (hits.size() < MAX_MATCHES) {
                lineNo++;
                boolean[] overflow = {false};
                String line = readLineCapped(reader, MAX_LINE_CHARS, overflow);
                if (line == null) {
                    break;
                }
                if (overflow[0]) {
                    overflowLines++; // 超长行不搜索，避免误报无命中
                    continue;
                }
                if (regex.matcher(line).find()) {
                    hits.add(rel + ":" + lineNo + ":" + line);
                }
            }
            if (overflowLines > 0) {
                hits.add(rel + "：（跳过 " + overflowLines + " 行超长内容）");
            }
        } catch (Exception ignored) {
            // 单个文件读不了（权限/编码等）不影响整体搜索
        }
    }

    /** 读一行，超过 maxChars 截断（仍消费完整行）；文件结束返回 null。 */
    private static String readLineCapped(BufferedReader reader, int maxChars, boolean[] overflow) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c = reader.read();
        if (c == -1) {
            return null;
        }
        while (c != -1 && c != '\n') {
            if (sb.length() < maxChars) {
                sb.append((char) c);
            } else {
                overflow[0] = true;
            }
            c = reader.read();
        }
        return sb.toString();
    }
}
