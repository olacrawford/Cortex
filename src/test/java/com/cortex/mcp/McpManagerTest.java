package com.cortex.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void 空配置_无工具且close立即返回() throws Exception {
        McpManager mgr = McpManager.start(new McpConfig(java.util.Map.of()), "test");
        assertTrue(mgr.tools().isEmpty());
        long start = System.currentTimeMillis();
        mgr.close();
        assertTrue(System.currentTimeMillis() - start < 2000, "空管理器 close 应立即返回");
    }

    @Test
    void 失败隔离_连不上的server只跳过自身() throws Exception {
        // 命令不存在的 stdio server → 连接失败被跳过，只留 stderr 告警
        McpConfig cfg = new McpConfig(Map.of(
                "broken", new ServerConfig("stdio", "/nonexistent/mcp-cmd-xyz",
                        List.of(), Map.of(), null, Map.of())));
        McpManager mgr = McpManager.start(cfg, "test");
        assertTrue(mgr.tools().isEmpty(), "失败的 server 不应产生工具");
        mgr.close(); // 不死锁
    }

    @Test
    void 连接卡住_超时后跳过() throws Exception {
        // sleep 永不退出也不说 MCP 协议 → initialize 超时（测试注入 1s）
        long saved = McpManager.connectTimeoutSec;
        McpManager.connectTimeoutSec = 1;
        try {
            McpConfig cfg = new McpConfig(Map.of(
                    "hang", new ServerConfig("stdio", "sleep",
                            List.of("60"), Map.of(), null, Map.of())));
            McpManager mgr = McpManager.start(cfg, "test");
            assertTrue(mgr.tools().isEmpty());
            mgr.close();
        } finally {
            McpManager.connectTimeoutSec = saved;
        }
    }

    @Test
    void close对卡死的会话有总兜底() throws Exception {
        long saved = McpManager.closeTimeoutSec;
        McpManager.closeTimeoutSec = 1;
        try {
            // 用失败 server 造 manager（无真实会话），close 仍应在兜底时间内返回
            McpConfig cfg = new McpConfig(Map.of(
                    "broken", new ServerConfig("stdio", "/nonexistent/mcp-cmd-xyz",
                            List.of(), Map.of(), null, Map.of())));
            McpManager mgr = McpManager.start(cfg, "test");
            long start = System.currentTimeMillis();
            mgr.close();
            assertTrue(System.currentTimeMillis() - start < 5000, "close 不应长时间阻塞");
        } finally {
            McpManager.closeTimeoutSec = saved;
        }
    }

    @Test
    void 项目级配置加载_缺失文件得空() {
        assertTrue(McpConfigLoader.loadConfig(tempDir).servers().isEmpty());
    }

    @Test
    void 配置文件字段校验与展开链路() throws Exception {
        Files.createDirectories(tempDir.resolve(".cortex"));
        Files.writeString(tempDir.resolve(".cortex/mcp.yaml"), """
                mcp_servers:
                  bad:
                    type: stdio
                  good:
                    type: http
                    url: "http://localhost:12345/mcp"
                    headers:
                      Authorization: "Bearer ${UNDEFINED_TOKEN_XYZ}"
                """);
        McpConfig cfg = McpConfigLoader.loadConfig(tempDir);
        assertEquals(1, cfg.servers().size()); // bad 被剔除
        ServerConfig good = cfg.servers().get("good");
        assertEquals("http", good.type());
        assertEquals("Bearer ", good.headers().get("Authorization")); // 未定义变量展开为空串
    }
}
