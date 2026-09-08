package com.cortex.mcp;

import com.cortex.tool.Result;
import com.cortex.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 远端 MCP 工具 → 内置 {@link Tool} 抽象的适配器（F7/F8）。
 * 命名统一为 mcp__&lt;server&gt;__&lt;tool&gt;（命名空间隔离 + 来源可追溯）；
 * 只读性严格只信远端 annotations.readOnlyHint==true（缺失/非法一律按有副作用，N2）；
 * 协议错/超时一律转成 isError 的结构化结果回灌，绝不向 Agent Loop 抛异常。
 */
final class McpTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");
    /** 非 text 内容块的"只告警一次"池（按工具全名）。 */
    private static final ConcurrentHashMap<String, Boolean> NON_TEXT_WARNED = new ConcurrentHashMap<>();
    /** 调用超时（默认 30s，内置不可配；volatile 仅为单测注入短超时）。 */
    static volatile long callTimeoutMs = 30_000L;

    /** McpTool 依赖的最小会话能力（生产实现包装 SDK 同步客户端；单测注入 stub）。 */
    interface CallerSession {
        McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) throws Exception;
    }

    private final String fullName;      // mcp__<server>__<tool>
    private final String remoteName;    // server 上的原始工具名
    private final String description;
    private final Map<String, Object> schema;
    private final boolean readOnly;
    private final CallerSession session;

    McpTool(String fullName, String remoteName, String description,
            Map<String, Object> schema, boolean readOnly, CallerSession session) {
        this.fullName = fullName;
        this.remoteName = remoteName;
        this.description = description;
        this.schema = schema;
        this.readOnly = readOnly;
        this.session = session;
    }

    /**
     * 把 SDK 返回的远端工具包装为 McpTool；名字含 LLM 工具名禁用字符时跳过（F8/AC7）。
     */
    static Optional<McpTool> adaptTool(String serverName, McpSchema.Tool t, CallerSession cs) {
        String fullName = "mcp__" + serverName + "__" + t.name();
        if (!VALID_NAME.matcher(fullName).matches()) {
            java.lang.System.err.printf("[mcp] warn: skip tool %s: name contains illegal characters%n", fullName);
            return Optional.empty();
        }
        String descr = (t.description() == null || t.description().isBlank())
                ? "来自 MCP server " + serverName + " 的工具 " + t.name()
                : t.description();
        Map<String, Object> schema;
        try {
            schema = MAPPER.convertValue(t.inputSchema(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            schema = null;
        }
        if (schema == null || schema.isEmpty()) {
            schema = Map.of("type", "object");
        }
        boolean readOnly = t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint());
        return Optional.of(new McpTool(fullName, t.name(), descr, schema, readOnly, cs));
    }

    @Override
    public String name() {
        return fullName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return schema;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public Result execute(String argsJson) {
        Map<String, Object> argMap;
        try {
            var node = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            argMap = MAPPER.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return Result.error("参数解析失败: " + e.getMessage());
        }

        McpSchema.CallToolResult res;
        try {
            res = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return session.callTool(remoteName, argMap);
                        } catch (Exception e) {
                            throw new java.util.concurrent.CompletionException(e);
                        }
                    }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                    .orTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                    .get();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Result.error("MCP 工具调用失败: " + cause.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : res.content() == null ? List.<McpSchema.Content>of() : res.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                sb.append(tc.text()).append('\n');
            } else if (NON_TEXT_WARNED.putIfAbsent(fullName, Boolean.TRUE) == null) {
                java.lang.System.err.printf("[mcp] warn: tool %s returned non-text content blocks (dropped)%n", fullName);
            }
        }
        return new Result(sb.toString().stripTrailing(), Boolean.TRUE.equals(res.isError()));
    }
}
