package com.cortex.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * edit_file（F2-改）：对 old_string 做唯一匹配替换；匹配 0 处或多于 1 处时
 * 返回含匹配数的可区分错误（AC4），让模型据此调整重试。
 */
public final class EditFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record EditFileArgs(
            String path,
            @JsonProperty("old_string") String oldString,
            @JsonProperty("new_string") String newString) {}

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "把文件中唯一出现的 old_string 替换为 new_string；old_string 必须在文件中恰好出现一次，"
                + "匹配多次时请提供更长上下文。编辑前请先用 read_file 读取目标文件，确认 old_string 唯一。";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "要修改的文件路径");
        Map<String, Object> oldString = new LinkedHashMap<>();
        oldString.put("type", "string");
        oldString.put("description", "要被替换的原文片段（必须在文件中唯一出现）");
        Map<String, Object> newString = new LinkedHashMap<>();
        newString.put("type", "string");
        newString.put("description", "替换后的新文片段");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", path);
        properties.put("old_string", oldString);
        properties.put("new_string", newString);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("path", "old_string", "new_string"));
        return schema;
    }

    @Override
    public Result execute(String argsJson) {
        return execute(ToolContext.EMPTY, argsJson);
    }

    @Override
    public Result execute(ToolContext ctx, String argsJson) {
        EditFileArgs args;
        try {
            args = MAPPER.readValue(argsJson, EditFileArgs.class);
        } catch (Exception e) {
            return Result.error("参数解析失败: " + e.getMessage());
        }
        if (args.path() == null || args.path().isBlank()) {
            return Result.error("缺少必填参数: path");
        }
        if (args.oldString() == null || args.oldString().isEmpty()) {
            return Result.error("old_string 不能为空");
        }
        if (args.newString() == null) {
            return Result.error("缺少必填参数: new_string");
        }
        String content;
        try {
            content = Files.readString(ctx.resolvePath(args.path())); // 阶段13：explicit cwd 优先（F17）
        } catch (IOException e) {
            return Result.error("读取失败: " + e.getMessage());
        }
        int n = content.split(Pattern.quote(args.oldString()), -1).length - 1;
        if (n == 0) {
            return Result.error("未找到匹配的内容");
        }
        if (n > 1) {
            return Result.error("匹配到 %d 处，old_string 不唯一，请提供更长上下文使其唯一".formatted(n));
        }
        try {
            Files.writeString(ctx.resolvePath(args.path()), content.replace(args.oldString(), args.newString()));
        } catch (IOException e) {
            return Result.error("写入失败: " + e.getMessage());
        }
        return Result.ok("已修改 " + args.path());
    }
}
