package com.cortex.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bash（F2-执行）：按平台选 shell 执行命令，合并 stdout/stderr，带超时保护（N1）；
 * 返回输出与退出码。非零退出不视为工具错误（结果原样回灌，由模型判断）。
 */
public final class BashTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_OUTPUT_BYTES = 30000;

    private final Duration timeout;

    /** 默认 30s 超时。 */
    public BashTool() {
        this(ToolRegistry.DEFAULT_TIMEOUT);
    }

    /** 供测试注入极短超时。 */
    BashTool(Duration timeout) {
        this.timeout = timeout;
    }

    private record BashArgs(String command) {}

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在工作目录下执行一条 shell 命令，返回标准输出/标准错误与退出码；受超时约束。"
                + "读文件、找文件、搜内容请优先用 read_file/glob/grep，不要用 bash 拼凑。";
    }

    @Override
    public boolean readOnly() {
        return false; // bash 可执行任意副作用命令，保守归为有副作用、串行执行
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "string");
        command.put("description", "要执行的 shell 命令（支持管道与重定向）");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", command);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("command"));
        return schema;
    }

    @Override
    public Result execute(String argsJson) {
        BashArgs args;
        try {
            args = MAPPER.readValue(argsJson, BashArgs.class);
        } catch (Exception e) {
            return Result.error("参数解析失败: " + e.getMessage());
        }
        if (args.command() == null || args.command().isBlank()) {
            return Result.error("缺少必填参数: command");
        }
        try {
            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = windows
                    ? new ProcessBuilder("cmd", "/C", args.command())
                    : new ProcessBuilder("sh", "-c", args.command());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 虚拟线程异步读输出，避免管道缓冲区写满导致命令卡死
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var in = process.getInputStream()) {
                    in.transferTo(out);
                } catch (Exception ignored) {
                }
            });

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                return Result.error("命令超时（超过 %ds），已终止".formatted(timeout.toSeconds()));
            }
            reader.join(5000);

            String output = Truncate.byLinesAndBytes(out.toString(StandardCharsets.UTF_8).stripTrailing(),
                    2000, MAX_OUTPUT_BYTES);
            int exitCode = process.exitValue();
            return Result.ok(output + "\n[exit_code: " + exitCode + "]");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("命令被中断");
        } catch (Exception e) {
            return Result.error("命令执行失败: " + e.getMessage());
        }
    }
}
