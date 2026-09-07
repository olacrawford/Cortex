package com.cortex.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * read_file（F2-读）：读文件返回带行号的文本（便于模型引用行号），
 * 超过 2000 行 / 256KB 截断（N5）；不存在 / 是目录 / 无权限返回结构化错误。
 */
public final class ReadFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LINES = 2000;
    private static final int MAX_BYTES = 256 * 1024;

    private record ReadFileArgs(String path) {}

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取指定路径的文本文件内容，返回带行号的文本（行号可用于后续编辑时引用）。";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "要读取的文件路径");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", path);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("path"));
        return schema;
    }

    @Override
    public Result execute(String argsJson) {
        ReadFileArgs args;
        try {
            args = MAPPER.readValue(argsJson, ReadFileArgs.class);
        } catch (Exception e) {
            return Result.error("参数解析失败: " + e.getMessage());
        }
        if (args.path() == null || args.path().isBlank()) {
            return Result.error("缺少必填参数: path");
        }
        Path p;
        try {
            p = Path.of(args.path());
        } catch (Exception e) {
            return Result.error("路径非法: " + args.path());
        }
        if (Files.isDirectory(p)) {
            return Result.error("路径是目录而非文件: " + args.path());
        }
        try {
            String content = Files.readString(p);
            StringBuilder sb = new StringBuilder();
            String[] lines = content.split("\n", -1);
            int n = lines.length;
            if (n > 0 && lines[n - 1].isEmpty()) {
                n--; // 文件以换行结尾时 split 尾部多出的空串不算一行
            }
            for (int i = 0; i < n; i++) {
                sb.append(String.format("%6d\t%s", i + 1, lines[i])).append('\n');
            }
            return Result.ok(Truncate.byLinesAndBytes(sb.toString(), MAX_LINES, MAX_BYTES));
        } catch (NoSuchFileException e) {
            return Result.error("文件不存在: " + args.path());
        } catch (AccessDeniedException e) {
            return Result.error("没有读取权限: " + args.path());
        } catch (Exception e) {
            return Result.error("读取失败: " + e.getMessage());
        }
    }
}
