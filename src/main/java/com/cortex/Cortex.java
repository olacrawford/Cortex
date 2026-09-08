package com.cortex;

import com.cortex.config.AppConfig;
import com.cortex.config.ConfigException;
import com.cortex.config.ConfigLoader;
import com.cortex.mcp.McpConfig;
import com.cortex.mcp.McpConfigLoader;
import com.cortex.mcp.McpManager;
import com.cortex.permission.PermissionEngine;
import com.cortex.prompt.Prompt;
import com.cortex.tool.Tool;
import com.cortex.tool.ToolRegistry;
import com.cortex.tui.CortexModel;
import com.cortex.tui.tea.Program;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cortex 入口：加载配置，构造工具注册中心（内置 + MCP）、权限引擎，启动 TUI。
 */
public class Cortex {

    public static void main(String[] args) {
        String configPath = ".cortex/config.yaml";
        try {
            Path root = Path.of("").toAbsolutePath();
            AppConfig config = ConfigLoader.load(configPath);
            ToolRegistry registry = ToolRegistry.createDefault();

            // MCP 自动发现（F9）：进 TUI 前同步完成连接 + 握手 + 列工具；失败 server 仅跳过
            McpConfig mcpCfg = McpConfigLoader.loadConfig(root);
            McpManager mcpManager = McpManager.start(mcpCfg, Prompt.VERSION);
            Runtime.getRuntime().addShutdownHook(new Thread(mcpManager::close, "mcp-shutdown"));
            // 同 server 自报同名工具的边界情形：后注册者保留（F8）
            Map<String, Tool> mcpTools = new LinkedHashMap<>();
            for (Tool t : mcpManager.tools()) {
                mcpTools.put(t.name(), t);
            }
            for (Tool t : mcpTools.values()) {
                try {
                    registry.register(t);
                } catch (IllegalArgumentException e) {
                    System.err.println("[mcp] warn: 跳过重复工具 " + t.name() + ": " + e.getMessage());
                }
            }

            PermissionEngine engine = PermissionEngine.create(root);
            CortexModel model = new CortexModel(config.getProviders(), registry, engine);
            Program program = new Program(model);
            program.run();
        } catch (ConfigException e) {
            System.err.println("配置错误: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
