package com.cortex.mcp;

import com.cortex.tool.Result;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpToolTest {

    private static final McpSchema.JsonSchema SCHEMA =
            new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);

    private static McpSchema.Tool tool(String name, String description,
                                       McpSchema.ToolAnnotations annotations) {
        // 组件序：name, title, description, inputSchema, outputSchema, annotations, meta
        return new McpSchema.Tool(name, null, description, SCHEMA, null, annotations, null);
    }

    private static McpSchema.CallToolResult result(String text, boolean isError) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), isError, null, null);
    }

    @Test
    void adaptTool_命名拼接与字段透传() {
        McpSchema.Tool t = tool("echo", "回显工具", null);
        Optional<McpTool> adapted = McpTool.adaptTool("demo", t, (n, a) -> result("ok", false));
        assertTrue(adapted.isPresent());
        McpTool m = adapted.get();
        assertEquals("mcp__demo__echo", m.name());
        assertEquals("回显工具", m.description());
        assertEquals("object", m.inputSchema().get("type")); // schema 透传（SDK 已含空 properties/required）
        assertFalse(m.readOnly()); // annotations 为 null → 非只读（N2）
    }

    @Test
    void adaptTool_名字含禁用字符被跳过() {
        McpSchema.Tool t = tool("echo.x@1", "x", null);
        assertTrue(McpTool.adaptTool("demo", t, (n, a) -> result("ok", false)).isEmpty());
    }

    @Test
    void adaptTool_空描述给兜底文案() {
        McpSchema.Tool t = tool("echo", "  ", null);
        McpTool m = McpTool.adaptTool("demo", t, (n, a) -> result("ok", false)).orElseThrow();
        assertTrue(m.description().contains("MCP server demo"));
        assertTrue(m.description().contains("echo"));
    }

    @Test
    void adaptTool_readOnlyHint严格只信true() {
        McpSchema.Tool hinted = tool("echo", "d", new McpSchema.ToolAnnotations(null, true, null, null, null, null));
        assertTrue(McpTool.adaptTool("demo", hinted, (n, a) -> result("ok", false)).orElseThrow().readOnly());
        McpSchema.Tool hintedFalse = tool("echo", "d", new McpSchema.ToolAnnotations(null, false, null, null, null, null));
        assertFalse(McpTool.adaptTool("demo", hintedFalse, (n, a) -> result("ok", false)).orElseThrow().readOnly());
    }

    @Test
    void execute_多text块拼接() {
        McpSchema.CallToolResult res = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("第一行"), new McpSchema.TextContent("第二行")),
                false, null, null);
        AtomicReference<String> received = new AtomicReference<>();
        McpTool m = McpTool.adaptTool("demo", tool("echo", "d", null), (n, a) -> {
            received.set(n);
            return res;
        }).orElseThrow();
        Result r = m.execute("{\"text\":\"hi\"}");
        assertFalse(r.isError());
        assertEquals("第一行\n第二行", r.content());
        assertEquals("echo", received.get()); // 调用远端时用原始工具名（不带命名空间前缀）
    }

    @Test
    void execute_远端isError映射() {
        McpTool m = McpTool.adaptTool("demo", tool("boom", "d", null),
                (n, a) -> result("出错了", true)).orElseThrow();
        Result r = m.execute("{}");
        assertTrue(r.isError());
        assertEquals("出错了", r.content());
    }

    @Test
    void execute_协议异常转isError() {
        McpTool m = McpTool.adaptTool("demo", tool("broken", "d", null),
                (n, a) -> { throw new IllegalStateException("连接断了"); }).orElseThrow();
        Result r = m.execute("{}");
        assertTrue(r.isError());
        assertTrue(r.content().contains("MCP 工具调用失败"));
        assertTrue(r.content().contains("连接断了"));
    }

    @Test
    void execute_超时转isError() throws Exception {
        McpTool m = McpTool.adaptTool("demo", tool("slow", "d", null), (n, a) -> {
            Thread.sleep(60_000);
            return result("不会返回", false);
        }).orElseThrow();
        // 把调用超时临时改成 200ms
        long savedTimeout = McpTool.callTimeoutMs;
        McpTool.callTimeoutMs = 200;
        try {
            Result r = m.execute("{}");
            assertTrue(r.isError());
            assertTrue(r.content().contains("MCP 工具调用失败"));
        } finally {
            McpTool.callTimeoutMs = savedTimeout;
        }
    }

    @Test
    void execute_非text块静默丢弃() {
        McpSchema.CallToolResult res = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("文字"), new McpSchema.ImageContent(null, "base64data", "image/png")),
                false, null, null);
        McpTool m = McpTool.adaptTool("demo", tool("mixed", "d", null), (n, a) -> res).orElseThrow();
        Result r = m.execute("{}");
        assertEquals("文字", r.content());
    }

    @Test
    void execute_参数不可解析转isError() {
        McpTool m = McpTool.adaptTool("demo", tool("echo", "d", null), (n, a) -> result("ok", false)).orElseThrow();
        Result r = m.execute("不是 JSON");
        assertTrue(r.isError());
        assertTrue(r.content().contains("参数解析失败"));
    }
}
