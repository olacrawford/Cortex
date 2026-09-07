package com.cortex.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * write_file（F2-写）：覆盖写文件；父目录不存在时自动创建（AC3）。
 */
public final class WriteFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record WriteFileArgs(String path, String content) {}

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "把文本内容写入（覆盖）指定路径的文件；父目录不存在时自动创建。";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "要写入的文件路径");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "string");
        content.put("description", "写入的完整文本内容（覆盖原有内容）");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", path);
        properties.put("content", content);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("path", "content"));
        return schema;
    }

    @Override
    public Result execute(String argsJson) {
        WriteFileArgs args;
        try {
            args = MAPPER.readValue(argsJson, WriteFileArgs.class);
        } catch (Exception e) {
            return Result.error("参数解析失败: " + e.getMessage());
        }
        if (args.path() == null || args.path().isBlank()) {
            return Result.error("缺少必填参数: path");
        }
        if (args.content() == null) {
            return Result.error("缺少必填参数: content");
        }
        try {
            Path p = Path.of(args.path());
            Path parent = p.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(p, args.content());
            int bytes = args.content().getBytes(StandardCharsets.UTF_8).length;
            return Result.ok("已写入 " + args.path() + "（" + bytes + " 字节）");
        } catch (IOException e) {
            return Result.error("写入失败: " + e.getMessage());
        }
    }
}
