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
import com.cortex.skill.InstallSkillTool;
import com.cortex.skill.SkillCatalog;
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

            // ch10：技能编目（两层扫描）+ 启动期 allowed_tools 校验（不通过的技能打警告并移除）
            SkillCatalog skillCatalog = new SkillCatalog();
            skillCatalog.loadCatalog(root);
            for (String bad : skillCatalog.validateTools(registry)) {
                System.err.println("[skills] warn: 技能 " + bad + " 的 allowed_tools 引用了未注册工具,已跳过加载");
                skillCatalog.remove(bad);
            }

            // ch11：Hook 引擎（hooks.yaml 双层加载，加载错误只 stderr 不阻断启动）
            com.cortex.hook.HookEngine hookEngine = com.cortex.hook.HookLoader.load(root);

            // ch12：SubAgent 角色编目 + 后台任务管理器 + 5 个新工具（Agent/TaskList/TaskGet/TaskStop/SendMessage）
            com.cortex.subagent.Catalog subAgentCatalog = com.cortex.subagent.Catalog.load(root);
            com.cortex.task.Manager taskMgr = new com.cortex.task.Manager();
            registry.register(new com.cortex.task.TaskListTool(taskMgr));
            registry.register(new com.cortex.task.TaskGetTool(taskMgr));
            registry.register(new com.cortex.task.TaskStopTool(taskMgr));
            registry.register(new com.cortex.task.SendMessageTool(taskMgr));

            // ch13：Worktree 管理器（非 git 仓库降级为「未启用」，F5/F35）+ 后台过期清理（F34）
            com.cortex.worktree.WorktreeManager worktreeMgr;
            try {
                worktreeMgr = new com.cortex.worktree.WorktreeManager(root);
                final com.cortex.worktree.WorktreeManager mgr = worktreeMgr;
                Thread.ofVirtual().name("worktree-sweeper").start(() ->
                        mgr.sweepStale(java.time.Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS)));
            } catch (Exception werr) {
                System.err.println("[worktree] warn: 管理器未启用（" + werr.getMessage() + "）");
                worktreeMgr = null;
            }
            com.cortex.agent.AgentTool agentTool = new com.cortex.agent.AgentTool(
                    subAgentCatalog, taskMgr, config.effectiveEnableSubAgentBackground(), worktreeMgr);
            registry.register(agentTool);

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
                    writer, memMgr, instructionText, memoryText, root.resolve(".cortex/sessions"),
                    skillCatalog, hookEngine, taskMgr, agentTool, worktreeMgr);
            // ch10：远程安装工具 → 装完 reload catalog 并重新注册斜杠命令，无需重启
            registry.register(new InstallSkillTool(skillCatalog, root,
                    Path.of(System.getProperty("user.home"), ".cortex", "skills"),
                    model::wireSkillsToAgent));
            Program program = new Program(model);
            model.attach(program);
            program.run();
            // ch11：SessionEnd 兜底（ctrl+c 等任意退出路径都 emit；F9/T22）
            if (hookEngine != null) {
                hookEngine.dispatch(com.cortex.hook.Event.SESSION_END,
                        new com.cortex.hook.Payload(new java.util.TreeMap<>(Map.of(
                                "event", com.cortex.hook.Event.SESSION_END.wireName(),
                                "session_id", runtime.session != null ? runtime.session.sessionId() : "",
                                "cwd", root.toString(),
                                "mode", "default"))));
            }
        } catch (ConfigException e) {
            System.err.println("配置错误: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
