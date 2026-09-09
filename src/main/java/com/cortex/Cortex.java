package com.cortex;

import com.cortex.agent.SessionRuntime;
import com.cortex.compact.Recovery;
import com.cortex.compact.state.AutoCompactTrackingState;
import com.cortex.compact.state.ContentReplacementState;
import com.cortex.compact.state.SessionContext;
import com.cortex.config.AppConfig;
import com.cortex.config.ConfigException;
import com.cortex.config.ConfigLoader;
import com.cortex.instructions.Loader;
import com.cortex.mcp.McpConfig;
import com.cortex.mcp.McpConfigLoader;
import com.cortex.mcp.McpManager;
import com.cortex.memory.Manager;
import com.cortex.permission.PermissionEngine;
import com.cortex.prompt.Prompt;
import com.cortex.session.SessionCleaner;
import com.cortex.session.Writer;
import com.cortex.tool.Tool;
import com.cortex.tool.ToolRegistry;
import com.cortex.tui.CortexModel;
import com.cortex.tui.tea.Program;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cortex 入口：加载配置，构造工具注册中心（内置 + MCP）、权限引擎，启动 TUI。
 * ch09：加载项目指令（MEWCODE.md）、初始化记忆管理器、后台清理过期会话、
 * 用 session Writer 挂接对话 JSONL 回调，并把指令 / 记忆文本注入系统提示。
 */
public class Cortex {

    public static void main(String[] args) {
        String configPath = ".cortex/config.yaml";
        try {
            Path root = Path.of("").toAbsolutePath();
            AppConfig config = ConfigLoader.load(configPath);
            ToolRegistry registry = ToolRegistry.createDefault();

            // ch09：项目指令（三层 MEWCODE.md）与记忆索引，注入系统提示
            Loader loader = new Loader(root);
            String instructionText = loader.load();
            Manager memMgr = new Manager(root, Path.of(System.getProperty("user.home")), null, "");
            String memoryText = memMgr.loadIndex();

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
            // ch08：会话级上下文管理状态（决策账本 / 文件追踪 / 熔断计数 / 会话目录），跨 run 持有
            SessionContext session = SessionContext.create(root);
            SessionRuntime runtime = new SessionRuntime(
                    new ContentReplacementState(), new Recovery.RecoveryState(),
                    new AutoCompactTrackingState(), session, 0);
            // ch09：JSONL 会话存档 Writer
            Writer writer = Writer.create(session.sessionDir());
            // ch09：后台清理过期会话（不阻塞启动）
            Thread.ofVirtual().name("session-cleaner").start(() ->
                    SessionCleaner.cleanExpired(root.resolve(".cortex/sessions"), Duration.ofDays(30)));

            CortexModel model = new CortexModel(config.getProviders(), registry, engine, runtime,
                    writer, memMgr, instructionText, memoryText, root.resolve(".cortex/sessions"));
            Program program = new Program(model);
            model.attach(program);
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
