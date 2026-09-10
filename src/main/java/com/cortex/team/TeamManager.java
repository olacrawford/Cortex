package com.cortex.team;

import com.cortex.agent.Agent;
import com.cortex.agent.SessionRuntime;
import com.cortex.agent.TeamHook;
import com.cortex.agent.TeammateContext;
import com.cortex.tool.ToolContext;
import com.cortex.conversation.ConversationManager;
import com.cortex.llm.ToolDef;
import com.cortex.permission.Mode;
import com.cortex.subagent.Catalog;
import com.cortex.subagent.Definition;
import com.cortex.task.Manager;
import com.cortex.tool.Filter;
import com.cortex.worktree.Worktree;
import com.cortex.worktree.WorktreeManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Team 管理器（F3-F7/G1）：单进程内管理多个 Team；队员 spawn（实现 agent.TeamHook）；
 * 队员空闲通知（T30）与 Lead 邮箱轮询（F41a）。Pane 后端的队员子进程自治模式
 * （--team-member）本期未实现，spawn 时 pane 后端报错（检测与命令构造已完成并单测）。
 */
public final class TeamManager implements TeamHook, com.cortex.task.TeamCollaboration {

    private final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Lead 装配环境（主 Agent 构建完成后注入）：队员子 Agent 的构造来源。 */
    public record LeadEnv(
            com.cortex.llm.LlmClient client,
            com.cortex.tool.ToolRegistry registry,
            String version,
            com.cortex.permission.PermissionEngine engine,
            int contextWindow,
            Path projectRoot,
            com.cortex.hook.HookEngine hookEngine) {}

    private final Path teamsDir; // <home>/.cortex/teams
    private final Path projectRoot;
    private final WorktreeManager worktreeManager; // 可空（非 git 仓库）
    private final Manager taskManager;
    private final AgentNameRegistry registry;
    private final BackendFactory backendFactory;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Team> teams = new LinkedHashMap<>(); // sanitized → Team
    private volatile LeadEnv leadEnv;
    private final Catalog agentCatalog;
    private final BackendDetector.Env env;

    public TeamManager(Path homeDir, Path projectRoot, WorktreeManager worktreeManager,
                       Manager taskManager, AgentNameRegistry registry,
                       Catalog agentCatalog, String cortexJar) throws IOException {
        this(homeDir, projectRoot, worktreeManager, taskManager, registry, agentCatalog,
                cortexJar, System::getenv);
    }

    /** Env 注入构造（T11：测试可控后端检测）。 */
    public TeamManager(Path homeDir, Path projectRoot, WorktreeManager worktreeManager,
                       Manager taskManager, AgentNameRegistry registry,
                       Catalog agentCatalog, String cortexJar,
                       BackendDetector.Env env) throws IOException {
        this.teamsDir = homeDir.resolve(".cortex").resolve("teams");
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.worktreeManager = worktreeManager;
        this.taskManager = taskManager;
        this.registry = registry;
        this.agentCatalog = agentCatalog;
        this.env = env;
        this.backendFactory = new BackendFactory(cortexJar, taskManager);
        Files.createDirectories(teamsDir);
        restoreFromDisk();
    }

    /** Lead 装配环境（主 Agent activate 后回填）。 */
    public void setLeadEnv(LeadEnv env) {
        this.leadEnv = env;
    }

    // ─── 查询 ───

    public Optional<Team> get(String name) {
        String sanitized = Persistence.sanitize(name);
        lock.lock();
        try {
            return Optional.ofNullable(teams.get(sanitized));
        } finally {
            lock.unlock();
        }
    }

    public List<Team> list() {
        lock.lock();
        try {
            List<Team> all = new ArrayList<>(teams.values());
            all.sort(Comparator.comparing(Team::createdAt));
            return List.copyOf(all);
        } finally {
            lock.unlock();
        }
    }

    // ─── 创建（F5/AC2/AC3）───

    public Team create(String name, String description) throws IOException {
        String sanitized = Persistence.sanitize(name);
        if (sanitized.isEmpty()) {
            throw new TeamException("团队名清洗后为空: " + name);
        }
        lock.lock();
        try {
            String unique = sanitized;
            int suffix = 2;
            while (teams.containsKey(unique)) { // AC3：同名自动 -2/-3
                unique = sanitized + "-" + suffix++;
            }
            BackendType backend = BackendDetector.detect(env);
            Path configDir = teamsDir.resolve(unique);
            Files.createDirectories(configDir);
            Files.createDirectories(configDir.resolve("mailbox"));
            Team team = new Team(name, unique, "lead", backend, description, Instant.now(), configDir);
            // F5-6：Lead 注册为第一个成员（SendMessage 的 to=lead 寻址依赖此条目）
            team.addMember(new TeammateInfo("lead", "lead", "", "",
                    "", "", backend, "", null, false, ""));
            Persistence.atomicWriteJson(team.configPath(), new Persistence.TeamSnapshot(
                    name, unique, "lead", backend, team.description(),
                    team.createdAt().getEpochSecond(), team.members()));
            teams.put(unique, team);
            return team;
        } finally {
            lock.unlock();
        }
    }

    // ─── 删除（F7/F66/AC4/AC5）───

    public void delete(String name, boolean force) throws IOException {
        Team team;
        lock.lock();
        try {
            team = teams.get(Persistence.sanitize(name));
        } finally {
            lock.unlock();
        }
        if (team == null) {
            throw new TeamNotFoundException(name);
        }
        boolean hasActive = team.members().stream().anyMatch(TeammateInfo::active);
        if (!force && hasActive) {
            throw new TeamHasActiveMembersException(team.sanitizedName());
        }
        // 杀 pane / cancel（best-effort）
        for (TeammateInfo m : team.members()) {
            if (m.name().equals("lead")) {
                continue;
            }
            try {
                backendFactory.create(m.backendType()).kill(m.paneId(), m.agentId());
            } catch (Exception e) {
                System.err.printf("[team] warn: kill 队员 %s 失败: %s%n", m.name(), e.getMessage());
            }
        }
        // 删 worktree 与 session（best-effort）
        for (TeammateInfo m : team.members()) {
            if (worktreeManager != null && !m.worktreePath().isEmpty()) {
                try {
                    worktreeManager.remove(m.name(), new com.cortex.worktree.ExitOptions(true));
                } catch (Exception e) {
                    System.err.printf("[team] warn: 删队员 worktree %s 失败: %s%n", m.name(), e.getMessage());
                }
            }
            if (!m.sessionDir().isEmpty()) {
                deleteRecursively(Path.of(m.sessionDir()));
            }
        }
        for (TeammateInfo m : team.members()) {
            registry.unregister(m.name());
        }
        deleteRecursively(team.configDir());
        lock.lock();
        try {
            teams.remove(team.sanitizedName());
        } finally {
            lock.unlock();
        }
    }

    // ─── 派队员（F25/T18，实现 TeamHook）───

    @Override
    public String spawnTeammate(TeamSpawnRequest req) throws IOException {
        LeadEnv env = leadEnv;
        if (env == null) {
            throw new TeamException("Team 装配未就绪（主 Agent 尚未初始化）");
        }
        Team team = get(req.teamName()).orElseThrow(() -> new TeamNotFoundException(req.teamName()));
        String memberName = req.memberName() == null || req.memberName().isBlank()
                ? "member-" + String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000))
                : req.memberName().strip();
        if (team.memberByName(memberName).isPresent()) {
            throw new MemberExistsException(team.sanitizedName(), memberName);
        }

        // 角色定义（F25-3）：留空回落 general-purpose
        String typeName = req.subagentType() == null || req.subagentType().isBlank()
                ? "general-purpose" : req.subagentType();
        Definition def = agentCatalog.resolve(typeName).orElseThrow(() ->
                new TeamException("未知 subagent_type: " + typeName));

        // Worktree 隔离（slug 形式 team-<sanitized>/<member>，ch13 嵌套 slug 复用）
        if (worktreeManager == null) {
            throw new TeamException("Worktree 管理器未启用（当前目录不是 git 仓库），无法隔离队员");
        }
        Worktree wt = worktreeManager.create("team-" + team.sanitizedName() + "/" + memberName, "HEAD", false);

        // session 目录（F65：沿用 ch12 session 布局）
        Path sessionDir = com.cortex.compact.state.SessionContext.create(projectRoot).sessionDir();

        // 工具过滤（teammate=true 豁免协作工具，G6）
        List<String> allNames = env.registry().definitions().stream().map(ToolDef::name).toList();
        List<String> allowed = Filter.applyAgentToolFilter(new Filter.FilterParams(
                allNames, def.source().ordinal() + 1, false, false,
                def.tools(), def.disallowedTools(), true));

        // 子 Agent：dontAsk 强制（F39a）+ worktree cwd + 角色提示 + 团队附录（F39）
        SessionRuntime subRuntime = SessionRuntime.empty(env.contextWindow());
        subRuntime.hookEngine = env.hookEngine();
        String base = def.systemPrompt() == null ? "" : def.systemPrompt();
        String systemPrompt = (base.isBlank() ? "" : base + "\n\n") + teamSystemPromptSuffix();
        boolean planRequired = Boolean.TRUE.equals(req.planModeRequired());
        Mode startMode = planRequired ? Mode.PLAN : def.permissionMode();
        Agent subAgent = Agent.builder(env.client(), env.registry(), env.version(), env.engine(), subRuntime)
                .allowedTools(java.util.Set.copyOf(allowed))
                .systemPrompt(systemPrompt)
                .maxTurns(def.maxTurns())
                .permissionMode(startMode)
                .dontAsk(true) // F39a：覆盖角色定义，Ask 工具无人应答会永久阻塞
                .forkContext(true)
                .build();
        // <team-context> reminder（F40）：首轮请求注入
        subRuntime.appendReminders(List.of(buildTeamContextReminder(team, memberName, wt.path())));

        // 邮箱闭包（T16/T20）：agentId 由 launch 生成，闭包经 holder 延迟解析（spawn 线程回填）
        Mailbox mailbox = new Mailbox(team.mailboxDir());
        java.util.concurrent.atomic.AtomicReference<String> agentIdRef = new java.util.concurrent.atomic.AtomicReference<>("");
        TeammateContext tc = new TeammateContext(team.sanitizedName(), memberName, "",
                () -> {
                    try {
                        var r = mailbox.readUnread(agentIdRef.get());
                        return new TeammateContext.ReadUnreadView(r.indices(),
                                r.messages().stream().map(TeamManager::toIncoming).toList());
                    } catch (IOException e) {
                        return new TeammateContext.ReadUnreadView(List.of(), List.of());
                    }
                },
                indices -> {
                    try {
                        mailbox.markRead(agentIdRef.get(), indices);
                    } catch (IOException ignored) {
                    }
                });
        String agentId = team.backend().isPane()
                ? String.format("agent-%014x", ThreadLocalRandom.current().nextLong()) : "";
        agentIdRef.set(agentId);
        String taskText = req.prompt() == null ? "" : req.prompt();

        String paneId = "";
        if (team.backend().isPane()) {
            // F13/F25-9：Pane 后端 initialPrompt 不走命令行——预写队员邮箱，子进程启动后自然读到
            mailbox.write(agentId, new Message("lead", agentId, MessageType.TEXT,
                    truncateForSummary(taskText), taskText, null, 0, false));
            paneId = backendFactory.create(team.backend()).spawn(new Backend.SpawnRequest(
                    team.sanitizedName(), memberName, agentId, wt.path().toString(),
                    sessionDir.toString(), typeName, req.model(), "",
                    planRequired, null, null, null)).paneId();
        } else {
            // in-process：构造子 Agent + 空白对话，taskText 直接作为 launch 任务
            subAgent.setTeammateContext(tc);
            subAgent.setToolContext(ToolContext.EMPTY.withCwd(wt.path()).withTeammate(tc));
            var conv = new ConversationManager();
            agentId = backendFactory.create(team.backend()).spawn(new Backend.SpawnRequest(
                    team.sanitizedName(), memberName, agentId, wt.path().toString(),
                    sessionDir.toString(), typeName, req.model(), taskText,
                    planRequired, subAgent, conv, taskManager)).agentId();
            agentIdRef.set(agentId);
        }

        registry.register(memberName, agentId);
        team.addMember(new TeammateInfo(memberName, agentId, typeName,
                req.model() == null ? "" : req.model(),
                wt.path().toString(), wt.branch(), team.backend(), paneId,
                true, planRequired, sessionDir.toString()));

        return "{\"memberName\":\"" + memberName + "\",\"agentId\":\"" + agentId
                + "\",\"worktree\":\"" + wt.path().toString().replace("\\", "\\\\")
                + "\",\"backend\":\"" + team.backend().wireValue()
                + "\",\"paneId\":\"" + paneId + "\"}";
    }

    // ─── 队员空闲通知（T30/F45/AC17）───

    /** TaskManager 完成回调：队员自然结束 → isActive=false + Lead 邮箱 idle 消息。 */
    public void handleTaskDone(String agentId) {
        for (Team team : list()) {
            Optional<TeammateInfo> member = team.memberByAgentId(agentId);
            if (member.isEmpty()) {
                continue;
            }
            try {
                team.setMemberActive(member.get().name(), false);
                Mailbox mailbox = new Mailbox(team.mailboxDir());
                mailbox.write(team.leadAgentId(), new Message(member.get().name(), team.leadAgentId(),
                        MessageType.TEXT, member.get().name() + " idle",
                        "agent " + agentId + " finished work, available for new tasks",
                        null, 0, false));
            } catch (IOException e) {
                System.err.printf("[team] warn: 队员 %s 空闲通知失败: %s%n",
                        member.get().name(), e.getMessage());
            }
            return;
        }
    }

    // ─── Lead 邮箱轮询（F41a/T30b）───

    /** Lead 未读消息（跨全部 Team）。 */
    public record LeadMessage(String teamName, String from, MessageType type,
                              String summary, String content, long timestamp) {}

    /** 轮询全部 Team 的 lead 邮箱，取未读并标 read。 */
    public List<LeadMessage> pollLeadMailboxes() {
        List<LeadMessage> out = new ArrayList<>();
        for (Team team : list()) {
            try {
                Mailbox mailbox = new Mailbox(team.mailboxDir());
                Mailbox.ReadUnreadResult unread = mailbox.readUnread(team.leadAgentId());
                for (Message m : unread.messages()) {
                    out.add(new LeadMessage(team.sanitizedName(), m.from(),
                            m.type(), m.summary(), m.content(), m.timestamp()));
                }
                mailbox.markRead(team.leadAgentId(), unread.indices());
            } catch (IOException e) {
                System.err.printf("[team] warn: Lead 邮箱轮询失败(%s): %s%n",
                        team.sanitizedName(), e.getMessage());
            }
        }
        return out;
    }

    // ─── 协作端口实现（T22/T31，队员上下文分派）───

    private Team teamOf(String teamName) {
        return get(teamName).orElseThrow(() -> new TeamNotFoundException(teamName));
    }

    @Override
    public String sendMessage(String teamName, String fromMember, String to,
                              String summary, String message, String type,
                              Boolean approve, String feedback) throws IOException {
        Team team = teamOf(teamName);
        MessageType msgType = type == null || type.isBlank() ? MessageType.TEXT : MessageType.fromWire(type);
        if (msgType == MessageType.PLAN_APPROVAL_RESPONSE && !"lead".equals(fromMember)) {
            throw new TeamException("plan_approval_response 仅 Lead 可发送");
        }
        if (msgType == MessageType.SHUTDOWN_RESPONSE && !"lead".equals(to)) {
            throw new TeamException("shutdown_response 只能发给 Lead");
        }
        Message.Payload payload = msgType == MessageType.PLAN_APPROVAL_RESPONSE || msgType == MessageType.SHUTDOWN_RESPONSE
                ? new Message.Payload(approve, feedback) : null;
        List<String> delivered = new ArrayList<>();
        if ("*".equals(to)) { // 广播：除发件人外所有成员各一份（F33/AC13）
            for (TeammateInfo m : team.members()) {
                if (m.name().equals(fromMember)) {
                    continue;
                }
                new Mailbox(team.mailboxDir()).write(m.agentId(), new Message(
                        fromMember, m.name(), msgType, summary, message, payload, 0, false));
                delivered.add(m.agentId());
            }
        } else if ("lead".equalsIgnoreCase(to.strip())) {
            // Lead 不在注册表（无 BackgroundTask）：直接写 leadAgentId 邮箱（G7）
            new Mailbox(team.mailboxDir()).write(team.leadAgentId(), new Message(
                    fromMember, to, msgType, summary, message, payload, 0, false));
            delivered.add(team.leadAgentId());
        } else {
            String agentId = registry.resolve(to)
                    .orElseThrow(() -> new TeamException("收件人不存在: " + to));
            TeammateInfo target = team.memberByAgentId(agentId).orElse(null);
            new Mailbox(team.mailboxDir()).write(agentId, new Message(
                    fromMember, to, msgType, summary, message, payload, 0, false));
            delivered.add(agentId);
            // Pane 后端：send-keys 唤醒（in-process 下一轮 Loop 自然读取）
            if (target != null && target.backendType().isPane()) {
                try {
                    backendFactory.create(target.backendType()).wake(target.paneId(), agentId);
                } catch (IOException e) {
                    System.err.printf("[team] warn: 唤醒 %s 失败: %s%n", to, e.getMessage());
                }
            }
        }
        return "{\"deliveredTo\":" + delivered.stream()
                .map(d -> "\"" + d + "\"").reduce((a, b) -> a + "," + b).map(s -> "[" + s + "]").orElse("[]")
                + ",\"timestamp\":" + java.time.Instant.now().getEpochSecond() + "}";
    }

    @Override
    public String taskCreate(String teamName, String title, String description,
                             String assignee, List<String> blockedBy) throws IOException {
        Team team = teamOf(teamName);
        String id = new TaskStore(team.tasksPath()).create(title, description, assignee, blockedBy);
        return "{\"taskId\":\"" + id + "\"}";
    }

    @Override
    public String taskGet(String teamName, String taskId) throws IOException {
        Team team = teamOf(teamName);
        TeamTask t = new TaskStore(team.tasksPath()).get(taskId)
                .orElseThrow(() -> new TeamException("未找到任务: " + taskId));
        return MAPPER.writeValueAsString(t);
    }

    @Override
    public String taskList(String teamName, String statusFilter) throws IOException {
        Team team = teamOf(teamName);
        List<TaskStore.TaskView> views = new TaskStore(team.tasksPath()).list(statusFilter);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (TaskStore.TaskView v : views) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(MAPPER.writeValueAsString(v.task()).replaceAll("\\}$", ""))
                    .append(",\"isReady\":").append(v.isReady()).append("}");
        }
        return sb.append("]").toString();
    }

    @Override
    public String taskUpdate(String teamName, String taskId, String title, String description,
                             String status, String assignee, List<String> addBlocks,
                             List<String> addBlockedBy, List<String> removeBlocks,
                             List<String> removeBlockedBy) throws IOException {
        Team team = teamOf(teamName);
        new TaskStore(team.tasksPath()).update(taskId, new TaskPatch(
                title, description, status, assignee,
                addBlocks, addBlockedBy, removeBlocks, removeBlockedBy));
        return "{\"taskId\":\"" + taskId + "\",\"status\":\"updated\"}";
    }

    // ─── helpers ───

    /** 初始任务的 mailbox summary（F13）：取前 8 个词。 */
    public static String truncateForSummary(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "new task";
        }
        String[] words = prompt.strip().split("\\s+");
        return words.length <= 8 ? prompt.strip()
                : String.join(" ", List.of(words).subList(0, 8)) + "…";
    }

    /** 队员系统提示附录（F39）。 */
    public static String teamSystemPromptSuffix() {
        return """
                IMPORTANT: You are running as an agent in a team.
                Just writing a response in text is not visible to others
                on your team - you MUST use the SendMessage tool.
                The user interacts primarily with the team lead.
                Your work is coordinated through the task system
                and teammate messaging.""";
    }

    /** <team-context> 首条 reminder（F40）。 */
    public static String buildTeamContextReminder(Team team, String memberName, Path worktree) {
        StringBuilder members = new StringBuilder();
        for (TeammateInfo m : team.members()) {
            if (members.length() > 0) {
                members.append(", ");
            }
            members.append(m.name()).append(m.name().equals("lead") ? "(lead)" : "(member)");
        }
        if (members.length() > 0) {
            members.append(", ");
        }
        members.append(memberName).append("(member)");
        return """
                <team-context>
                team: %s
                你的成员名: %s
                worktree 目录: %s
                当前团队成员: %s
                </team-context>""".formatted(team.sanitizedName(), memberName, worktree, members);
    }

    /** Message → 队员闭包轻量消息（cli.TeamMemberRunner 复用）。 */
    public static TeammateContext.IncomingMessage toIncoming(Message m) {
        return new TeammateContext.IncomingMessage(m.from(), m.type().wire(),
                m.summary(), m.content(),
                m.payload() == null ? null : m.payload().approve(),
                m.payload() == null ? null : m.payload().feedback());
    }

    private void restoreFromDisk() {
        try (var dirs = Files.list(teamsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try {
                    Optional<Persistence.TeamSnapshot> snap =
                            Persistence.readJson(dir.resolve("config.json"), Persistence.TeamSnapshot.class);
                    if (snap.isEmpty()) {
                        return;
                    }
                    Persistence.TeamSnapshot s = snap.get();
                    Team team = new Team(s.name(), s.sanitizedName(), s.leadAgentId(), s.backend(),
                            s.description(), Instant.ofEpochSecond(s.createdAt()), dir);
                    team.loadFromSnapshotLocked(s);
                    teams.put(s.sanitizedName(), team);
                } catch (Exception e) {
                    System.err.printf("[team] warn: 团队目录 %s 加载失败，已跳过: %s%n",
                            dir.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("[team] warn: teams 目录扫描失败: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
