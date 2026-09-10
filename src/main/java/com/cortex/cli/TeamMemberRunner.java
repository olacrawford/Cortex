package com.cortex.cli;

import com.cortex.agent.Agent;
import com.cortex.agent.AgentEvent;
import com.cortex.agent.CancelToken;
import com.cortex.agent.SessionRuntime;
import com.cortex.agent.TeammateContext;
import com.cortex.conversation.ConversationManager;
import com.cortex.hook.HookLoader;
import com.cortex.instructions.Loader;
import com.cortex.llm.LlmClient;

import com.cortex.permission.PermissionEngine;
import com.cortex.prompt.Prompt;
import com.cortex.session.Writer;
import com.cortex.subagent.Catalog;
import com.cortex.subagent.Definition;

import com.cortex.team.AgentNameRegistry;
import com.cortex.team.Mailbox;
import com.cortex.team.Message;
import com.cortex.team.MessageType;
import com.cortex.team.Team;
import com.cortex.team.TeamManager;
import com.cortex.tool.Filter;
import com.cortex.tool.ToolContext;
import com.cortex.worktree.WorktreeManager;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * 队员子进程自治循环（F19a/F19b/T29）：Pane 后端 spawn 的 cortex 子进程不启动 TUI，
 * 跑只读「日志流」循环——读邮箱 → 分流消息（text=任务 / plan 审批 / shutdown）→
 * runToCompletion → 汇报 Lead 空闲 → 等待唤醒。stdin 回车 = 即时轮询信号；
 * mailbox 目录消失（/team delete）→ 优雅退出。
 */
public final class TeamMemberRunner implements Runnable {

    @CommandLine.Command(name = "cortex", mixinStandardHelpOptions = true)
    public static final class Args implements Runnable {

        /** picocli populate 后直接进入自治循环（F19a）。 */
        @Override
        public void run() {
            TeamMemberRunner.run(this);
        }


        @CommandLine.Option(names = "--team-member") boolean teamMember;
        @CommandLine.Option(names = "--team", required = true) String team;
        @CommandLine.Option(names = "--member", required = true) String member;
        @CommandLine.Option(names = "--agent-id", required = true) String agentId;
        @CommandLine.Option(names = "--session-dir", required = true) String sessionDir;
        @CommandLine.Option(names = "--worktree", required = true) String worktree;
        @CommandLine.Option(names = "--agent-type") String agentType;
        @CommandLine.Option(names = "--model") String model;
        @CommandLine.Option(names = "--plan-mode") boolean planMode;
    }

    private final Args args;
    private final Path worktree;
    private final SynchronousQueue<Object> wakeQueue = new SynchronousQueue<>();

    public TeamMemberRunner(Args args) {
        this.args = args;
        this.worktree = Path.of(args.worktree).toAbsolutePath().normalize();
    }

    public static void run(Args args) {
        new TeamMemberRunner(args).run();
    }

    @Override
    public void run() {
        try {
            runLoop();
        } catch (Exception e) {
            System.err.println("[team-member] fatal: " + (e.getMessage() != null ? e.getMessage() : e));
        }
    }

    private void runLoop() throws Exception {
        // F19a-2：进程内一切以 worktree 为根（显式 cwd；真实进程目录不切换）
        System.setProperty("user.dir", worktree.toString());
        Path home = Path.of(System.getProperty("user.home"));

        // F19a-3：独立装配（复用 Lead wire 形态，无 TUI）
        var config = com.cortex.config.ConfigLoader.load(
                worktree.resolve(".cortex/config.yaml").toString());
        var provider = config.getProviders().get(0);
        LlmClient client = LlmClient.create(provider);
        var registry = new com.cortex.tool.ToolRegistry();
        registry.register(new com.cortex.tool.ReadFileTool());
        registry.register(new com.cortex.tool.WriteFileTool());
        registry.register(new com.cortex.tool.EditFileTool());
        registry.register(new com.cortex.tool.BashTool());
        registry.register(new com.cortex.tool.GlobTool());
        registry.register(new com.cortex.tool.GrepTool());
                // 协作工具在队员注册之后装配（需要 teamMgr 端口），见下方 teamMgr 创建
        PermissionEngine engine = PermissionEngine.create(worktree);
        var hookEngine = HookLoader.load(worktree);
        var runtime = SessionRuntime.empty(provider.effectiveContextWindow());
        runtime.hookEngine = hookEngine;
        var taskMgr = new com.cortex.task.Manager();
        var nameReg = new AgentNameRegistry();
        taskMgr.setNameRegistry(nameReg);
        WorktreeManager wtMgr = null;
        try {
            wtMgr = new WorktreeManager(worktree);
        } catch (Exception ignored) {
        }
        TeamManager teamMgr = new TeamManager(home, worktree, wtMgr, taskMgr, nameReg,
                Catalog.load(worktree), "cortex.jar");
        teamMgr.setLeadEnv(new TeamManager.LeadEnv(client, registry, Prompt.VERSION, engine,
                provider.effectiveContextWindow(), worktree, hookEngine));
        // 队员协作工具（TaskCreate/TaskUpdate/TaskList/TaskGet/SendMessage 的 Team 语义）
        registry.register(new com.cortex.task.TaskCreateTool(teamMgr));
        registry.register(new com.cortex.task.TaskUpdateTool(teamMgr));
        registry.register(new com.cortex.task.TaskListTool(null, teamMgr));
        registry.register(new com.cortex.task.TaskGetTool(null, teamMgr));
        registry.register(new com.cortex.task.SendMessageTool(null, teamMgr));
        Team team = teamMgr.get(args.team).orElseThrow(
                () -> new IllegalStateException("团队不存在: " + args.team));
        team.reloadFromDisk();

        String instructionText = "";
        String memoryText = "";
        try {
            instructionText = new Loader(worktree).load();
        } catch (Exception ignored) {
        }
        try {
            memoryText = new com.cortex.memory.Manager(worktree, home, null, "").loadIndex();
        } catch (Exception ignored) {
        }

        // 队员 Agent（F19a-4）：dontAsk 强制、角色提示 + 团队附录、worktree cwd、teammate 闭包
        String typeName = args.agentType == null || args.agentType.isBlank()
                ? "general-purpose" : args.agentType;
        Definition def = Catalog.load(worktree).resolve(typeName).orElseThrow(
                () -> new IllegalStateException("未知 agent-type: " + typeName));
        SessionRuntime subRuntime = SessionRuntime.empty(provider.effectiveContextWindow());
        subRuntime.hookEngine = hookEngine;
        String sys = (def.systemPrompt().isBlank() ? "" : def.systemPrompt() + "\n\n")
                + TeamManager.teamSystemPromptSuffix();
        Agent agent = Agent.builder(client, registry, Prompt.VERSION, engine, subRuntime)
                .allowedTools(java.util.Set.copyOf(allowedTools(registry, def)))
                .systemPrompt(sys)
                .maxTurns(def.maxTurns())
                .permissionMode(args.planMode
                        ? com.cortex.permission.Mode.PLAN : def.permissionMode())
                .dontAsk(true)
                .forkContext(true)
                .build();

        Mailbox mailbox = new Mailbox(team.mailboxDir());
        agent.setTeammateContext(new TeammateContext(team.sanitizedName(), args.member, args.agentId,
                () -> {
                    try {
                        var r = readUnread(mailbox);
                        return new TeammateContext.ReadUnreadView(r.indices(),
                                r.messages().stream().map(TeamManager::toIncoming).toList());
                    } catch (IOException e) {
                        return new TeammateContext.ReadUnreadView(List.of(), List.of());
                    }
                },
                indices -> {
                    try {
                        mailbox.markRead(args.agentId, indices);
                    } catch (IOException ignored) {
                    }
                }));
        agent.setToolContext(ToolContext.EMPTY.withCwd(worktree).withTeammate(
                agent.teammateContext()));

        var writer = Writer.create(Path.of(args.sessionDir)); // F65：队员对话持久化
        var conv = new ConversationManager(writer::onAppend, writer::onReplace);
        subRuntime.appendReminders(List.of(
                TeamManager.buildTeamContextReminder(team, args.member, worktree)));

        // F19a-5：stdin scanner——send-keys 回车 → 即时轮询信号
        Thread.ofVirtual().name("stdin-scanner").start(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in))) {
                while (reader.readLine() != null) {
                    wakeQueue.offer(new Object());
                }
            } catch (Exception ignored) {
            }
        });

        System.out.println("[team-member] " + args.member + " 就绪（team=" + args.team
                + "，worktree=" + worktree + "）");

        // F19a-6：主循环
        while (true) {
            if (!mailbox.exists()) {
                System.out.println("[team-member] mailbox 已删除，优雅退出");
                return;
            }
            var unread = readUnread(mailbox);
            String task = null;
            boolean shutdown = false;
            for (Message m : unread.messages()) {
                if (m.type() == MessageType.SHUTDOWN_REQUEST) {
                    mailbox.write(team.leadAgentId(), new Message(args.member, "lead",
                            MessageType.SHUTDOWN_RESPONSE, "shutdown approved", "",
                            new Message.Payload(true, ""), 0, false));
                    shutdown = true;
                    break;
                }
                if (m.type() == MessageType.PLAN_APPROVAL_RESPONSE) {
                    boolean ok = m.payload() != null && Boolean.TRUE.equals(m.payload().approve());
                    if (ok) {
                        agent.setPermissionMode(com.cortex.permission.Mode.DEFAULT);
                        task = "Lead 已批准计划，权限模式已切到 default，可执行计划。";
                    } else {
                        task = "Lead 驳回了计划，反馈：" + m.payload().feedback()
                                + "。请调整后重新用 SendMessage 提交。";
                    }
                    continue;
                }
                task = m.content(); // TEXT：作为本轮任务
            }
            if (!unread.indices().isEmpty()) {
                mailbox.markRead(args.agentId, unread.indices());
            }
            if (shutdown) {
                return;
            }
            if (task != null && !task.isBlank()) {
                runOne(agent, conv, task);
                mailbox.write(team.leadAgentId(), new Message(args.member, team.leadAgentId(),
                        MessageType.TEXT, args.member + " idle",
                        "agent " + args.agentId + " finished work, available for new tasks",
                        null, 0, false));
                team.setMemberActive(args.member, false);
            }
            wakeQueue.poll(2, TimeUnit.SECONDS); // 兜底轮询
        }
    }

    // ─── 单轮执行 + 日志流（F19b）───

    private void runOne(Agent agent, ConversationManager conv, String task) {
        var events = new LinkedBlockingQueue<AgentEvent>();
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread.ofVirtual().name("event-printer").start(() -> {
            while (running.get()) {
                try {
                    AgentEvent e = events.poll(200, TimeUnit.MILLISECONDS);
                    if (e == null) {
                        continue;
                    }
                    switch (e) {
                        case AgentEvent.Text t -> System.out.println(t.delta());
                        case AgentEvent.Tool t -> System.out.println("● " + t.event().name()
                                + "(" + t.event().args() + ")"
                                + (t.event().phase() == com.cortex.agent.Phase.END
                                ? " → " + (t.event().isError() ? "[error] " : "")
                                + abbreviate(t.event().result()) : ""));
                        case AgentEvent.Done d -> System.out.println("──────────────");
                        case AgentEvent.Failed f -> System.err.println("✖ " + f.message());
                        default -> {
                        }
                    }
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });
        try {
            agent.runToCompletion(new CancelToken(), conv, task, events);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("✖ " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            running.set(false);
        }
    }

    private Mailbox.ReadUnreadResult readUnread(Mailbox mailbox) throws IOException {
        return mailbox.readUnread(args.agentId);
    }

    private List<String> allowedTools(com.cortex.tool.ToolRegistry registry, Definition def) {
        List<String> all = registry.definitions().stream()
                .map(com.cortex.llm.ToolDef::name).toList();
        return Filter.applyAgentToolFilter(new Filter.FilterParams(
                all, def.source().ordinal() + 1, false, false,
                def.tools(), def.disallowedTools(), true));
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
