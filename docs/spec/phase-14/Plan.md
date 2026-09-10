# Agent Team Plan

## 技术栈

- 语言:Java 21(LTS;virtual thread、record、sealed interface、pattern matching)
- 构建:Gradle(`build.gradle.kts`,目标 JDK 21)
- TUI:JLine/tui.tea(`org.jline:jline`)沿用 ch02 风格
- LLM SDK:`com.anthropic:anthropic-java` + `com.openai:openai-java`(沿用 ch02 / ch13)
- 配置解析:SnakeYAML Engine(`org.yaml:snakeyaml`)
- JSON:Jackson Databind(`com.fasterxml.jackson.core:jackson-databind`)做 `config.json` / `tasks.json` / mailbox 的序列化反序列化
- 进程间唤醒:`Runtime.exec(["tmux", ...])` / `Runtime.exec(["it2", ...])` 调 CLI;Pane 子进程内置 stdin scanner virtual thread
- CLI 解析:`info.picocli:picocli`(用于 `--team-member` 子进程命令行)
- 并发:Java 21 virtual thread + `ReentrantLock` + `LinkedBlockingQueue`;mailbox / tasks 文件锁用 `Files.newOutputStream(..., StandardOpenOption.CREATE_NEW)` 实现 `O_EXCL` 语义

## 架构概览

本章引入 `com.cortex.teams` 顶层包,把 ch13 SubAgent 的「子 Agent」扩展为「Team 队员」。整体分四层:

1. **数据模型层**(`team/Team.java` + `team/TeamManager.java` + `team/persistence/`)——Team、TeammateInfo 数据结构与持久化
2. **后端层**(`team/backend/`)——`Backend` 接口与三种实现 tmux / iterm2 / inprocess,屏蔽 spawn 差异
3. **协作层**(`team/mailbox/`、`team/registry/`、`team/tasks/`)——邮箱(含文件锁)、AgentNameRegistry、共享任务列表
4. **工具与集成层**(`team/tools/` + `agent` 包扩展 + `coordinator` 包)——5 个协作工具 + `Agent` 工具的 `teamName` 分支 + Coordinator Mode

Lead 仍是 `CortexModel.mainAgent()`——本期 Lead 没有独立类型,通过 `Coordinator.isEnabled(cfg)` 在启动时收窄其工具集即可。

依赖方向(单向):
```
tui  ──→  agent  ──→  team  ──→  team/{backend,mailbox,registry,tasks,tools}
                       └──→  worktree(ch14)、task(ch13)、session(ch12)、subagent(ch13)
```
`team` 不反向依赖 `agent` 包(避免环);`agent` 通过新增的 `TeamHook` 接口注入 team 行为。

## 核心数据结构

### `com.cortex.teams.Team`

```java
package com.cortex.teams;

public final class Team {
    private final ReentrantLock lock = new ReentrantLock();

    private final String name;          // 用户给的原始名
    private final String sanitizedName; // 经 sanitize 后用于路径,Team 主键
    private final String leadAgentId;   // 固定 "lead"(本期 Lead = 主 Agent)
    private BackendType backend;        // 全 team 默认后端;可被 member 覆盖
    private String description;
    private final Instant createdAt;
    private final List<TeammateInfo> members = new ArrayList<>();

    // 派生路径(不持久化)
    private final Path configDir;
    private final Path configPath;   // <configDir>/config.json
    private final Path tasksPath;    // <configDir>/tasks.json
    private final Path mailboxDir;   // <configDir>/mailbox/

    public boolean addMember(TeammateInfo info);
    public boolean setMemberActive(String name, boolean active);
    public boolean removeMember(String name);
    public Optional<TeammateInfo> memberByName(String name);
    public Optional<TeammateInfo> memberByAgentId(String id);
    // ... getters
}
```

### `com.cortex.teams.TeammateInfo`

```java
package com.cortex.teams;

public record TeammateInfo(
    @JsonProperty("name")             String name,
    @JsonProperty("agentId")          String agentId,
    @JsonProperty("agentType")        String agentType,   // "" 表 Fork
    @JsonProperty("model")            String model,       // "" 表 inherit
    @JsonProperty("worktreePath")     String worktreePath,// 绝对路径
    @JsonProperty("branch")           String branch,
    @JsonProperty("backendType")      BackendType backendType,
    @JsonProperty("paneId")           String paneId,      // tmux pane id / iterm2 split id / "" for in-process
    @JsonProperty("isActive")         Boolean isActive,   // null/true 活跃,false 空闲;不存在视为终止
    @JsonProperty("planModeRequired") boolean planModeRequired,
    @JsonProperty("sessionDir")       String sessionDir   // 绝对路径
) {}
```

### `com.cortex.teams.TeamManager`

```java
package com.cortex.teams;

public final class TeamManager {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Team> teams = new HashMap<>();  // 按 sanitizedName 索引
    private final Path homeDir;
    private final WorktreeManager worktreeManager;
    private final TaskManager taskManager;
    private final AgentNameToolRegistry registry;

    public TeamManager(Path homeDir, WorktreeManager wt, TaskManager taskMgr, AgentNameRegistry reg) throws IOException;
    public Team create(String name, String description) throws IOException;
    public Optional<Team> get(String name);
    public List<Team> list();
    public void delete(String name, boolean force) throws IOException;
}
```

### `com.cortex.teams.BackendType`

```java
public enum BackendType {
    TMUX("tmux"), ITERM2("iterm2"), IN_PROCESS("in-process");
    private final String wire;
    BackendType(String w) { this.wire = w; }
    public String wireValue() { return wire; }
    @JsonCreator public static BackendType fromWire(String s) { ... }
}
```

### `com.cortex.teams.backend.Backend`

```java
package com.cortex.teams.backend;

public interface Backend {
    BackendType type();
    SpawnResult spawn(SpawnRequest req) throws IOException;
    void wake(String paneId, String agentId) throws IOException;
    void kill(String paneId, String agentId) throws IOException;
}

public record SpawnResult(String paneId, String agentId) {}

public record SpawnRequest(
    String teamName,
    String memberName,
    String agentId,
    String worktreePath,
    String sessionDir,
    String agentType,
    String model,
    String initialPrompt,
    boolean planModeRequired,
    // in-process 专用——同进程后端直接复用这三个对象;用 Object 避免 backend 反向依赖 agent 包
    Object subAgent,
    Object conv,
    Object taskManager
) {}
```

### `com.cortex.teams.mailbox.Message` / `Mailbox`

```java
package com.cortex.teams.mailbox;

public enum MessageType {
    TEXT("text"),
    SHUTDOWN_REQUEST("shutdown_request"),
    SHUTDOWN_RESPONSE("shutdown_response"),
    PLAN_APPROVAL_RESPONSE("plan_approval_response");
    // wireValue + @JsonCreator 同 BackendType
}

public record Message(
    @JsonProperty("from")      String from,
    @JsonProperty("to")        String to,
    @JsonProperty("type")      MessageType type,
    @JsonProperty("summary")   String summary,
    @JsonProperty("content")   String content,
    @JsonProperty("payload")   Map<String, Object> payload,
    @JsonProperty("timestamp") long timestamp,
    @JsonProperty("read")      boolean read
) {}

public final class Mailbox {
    private final Path dir;  // <teamConfigDir>/mailbox/

    public Mailbox(Path dir) throws IOException { ... }
    public void write(String agentId, Message msg) throws IOException;
    public List<Message> read(String agentId) throws IOException;
    public ReadUnreadResult readUnread(String agentId) throws IOException; // record { List<Integer> indices, List<Message> msgs }
    public void markRead(String agentId, List<Integer> indices) throws IOException;
}
```

文件锁机制由 `com.cortex.teams.filelock.FileLock` 提供,所有公开方法都走锁。

### `com.cortex.teams.registry.AgentNameRegistry`

```java
package com.cortex.teams.registry;

public final class AgentNameRegistry {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, String> byName = new HashMap<>();  // name → agentId
    private final Map<String, String> byId   = new HashMap<>();  // agentId → name

    public void register(String name, String agentId);
    public void unregister(String name);
    public void unregisterByAgentId(String agentId);
    public Optional<String> resolve(String nameOrId);
    public Optional<String> nameOf(String agentId);
    public Map<String, String> snapshot();
}
```

注意:本章把 `TaskManager.byName` 替换/委托给这套 registry——`TaskManager` 改为持一个 `AgentNameRegistry` 引用。

### `com.cortex.teams.tasks.Store`

```java
package com.cortex.teams.tasks;

public enum Status {
    PENDING("pending"), IN_PROGRESS("in_progress"),
    COMPLETED("completed"), BLOCKED("blocked");
    // wireValue + @JsonCreator 同上
}

public record Task(
    @JsonProperty("id")          String id,
    @JsonProperty("title")       String title,
    @JsonProperty("description") String description,
    @JsonProperty("status")      Status status,
    @JsonProperty("assignee")    String assignee,
    @JsonProperty("blockedBy")   List<String> blockedBy,
    @JsonProperty("blocks")      List<String> blocks,
    @JsonProperty("createdAt")   long createdAt,
    @JsonProperty("updatedAt")   long updatedAt
) {}

public record Filter(Optional<Status> status) {}
public record Patch(
    Optional<String> title,
    Optional<String> description,
    Optional<Status> status,
    Optional<String> assignee,
    List<String> addBlocks,
    List<String> addBlockedBy,
    List<String> removeBlocks,
    List<String> removeBlockedBy
) {}

public final class Store {
    private final Path path;
    private final ReentrantLock lock = new ReentrantLock();

    public Store(Path path);
    public String create(Task t) throws IOException;
    public Optional<Task> get(String id) throws IOException;
    public List<Task> list(Filter f) throws IOException;
    public void update(String id, Patch p) throws IOException;
}
```

### `com.cortex.teams` 包

```java
package com.cortex.teams;

public final class Coordinator {
    public static boolean isEnabled(AppConfig cfg);
    public static List<String> allowedTools();
    public static String systemPromptSuffix();
}
```

仅 3 个纯静态方法,无状态。

## 模块设计

### `com.cortex.teams`(顶层)

**职责:** Team / TeammateInfo / TeamManager 数据结构与持久化,跨子包的协调入口。
**对外接口:** `new TeamManager(...)`、`TeamManager.create/get/list/delete`、`Team.addMember/setMemberActive/removeMember`
**依赖:** `worktree`、`task`、`session`、`team.backend`、`team.mailbox`、`team.registry`、`team.tasks`

### `com.cortex.teams.backend`

**职责:** 屏蔽 tmux / iterm2 / in-process spawn 差异。
**对外接口:** `Backend` 接口、`Backend.detect()`、`BackendFactory.create(BackendType, Deps)`
**依赖:** `team`(取常量)、`agent` 与 `task`(in-process 实现用)

注意:`backend` 包反向依赖 `agent` 会成环。解决:`in-process` 实现走「接口适配」——`backend.spawn` 接收 `SpawnRequest` 中的 `subAgent Object`(声明为 `Object`),由调用方(`team` 包)预先构造好;`backend` 包只做调度,不知道 `Agent` 类型。或者把 `in-process` 实现单独提到 `team.backend.inprocess` 子包,允许它依赖 `agent`,而 `team.backend.tmux` / `iterm2` 不依赖。

**采用方案:** 三种后端各一个子包(`tmux/` / `iterm2/` / `inprocess/`),每个独立实现 `Backend` 接口,工厂方法 `create(...)` 接收所需依赖。`inprocess` 子包依赖 `agent` 包没问题(`agent` 在更低层)。

### `com.cortex.teams.mailbox`

**职责:** 邮箱文件 + 文件锁的读写。
**对外接口:** `Mailbox.write/read/readUnread/markRead`、`Message` 类型
**依赖:** 仅 JDK + Jackson(`java.nio.file`、`com.fasterxml.jackson.databind`)

### `com.cortex.teams.registry`

**职责:** Agent name ↔ agentId 双向映射。
**对外接口:** `register/unregister/resolve/nameOf`
**依赖:** 仅 JDK

### `com.cortex.teams.tasks`

**职责:** 共享任务列表的 CRUD + 依赖图维护。
**对外接口:** `Store.create/get/list/update`、`Task`、`Filter`、`Patch` 类型
**依赖:** 仅 JDK + Jackson + `team.filelock`

### `com.cortex.teams.tools`

**职责:** 5 个协作工具实现(TaskCreate、TaskGet、TaskList、TaskUpdate、SendMessage)+ 2 个 Team 管理工具(TeamCreate、TeamDelete)。
**对外接口:** 每个工具一个构造函数 `new XxxTool(teamManager)` 实现 `Tool` 接口
**依赖:** `tool`、`team`、`team.{mailbox,registry,tasks}`

### `com.cortex.teams`

**职责:** Coordinator Mode 的开关检测、工具白名单、系统提示词。
**对外接口:** `isEnabled(cfg)`、`allowedTools()`、`systemPromptSuffix()`
**依赖:** `config`(读 feature flag)

### `agent` 包扩展

- 新增 `agent.TeamHook` 接口:
  ```java
  public interface TeamHook {
      // spawnTeammate 让 Agent 工具委托给 TeamManager 处理 teamName 分支。
      // 返回 finalText(立即返回 taskId JSON 描述)。
      String spawnTeammate(TeamSpawnRequest req) throws IOException;
      // teammateContextOf 判断当前调用 ctx 是否在某队员的执行上下文中(用于拦截嵌套 spawn)。
      Optional<TeammateContextInfo> teammateContextOf(InvocationContext ctx);

      record TeammateContextInfo(String memberName, String teamName, BackendType backendType) {}
  }
  ```
- `AgentTool` 持一个 `TeamHook teamHook` 字段(可选,null 时降级为 ch13 行为)
- `Agent.execute` 在 `teamName != null && !teamName.isBlank()` 时调 `teamHook.spawnTeammate`

### `task` 包扩展

- `TaskManager` 持一个 `AgentNameRegistry` 引用(原 `byName` 字段废弃,改委托)
- `TaskManager.sendMessage` 复用——Team 模块续派直接调它

### `tui` 包扩展

- `CortexModel` 新增字段 `TeamManager teamManager`
- 注入 `/team` 系列 slash 命令(`com.cortex.command.builtin.BuiltinTeam`)
- 状态栏新增 `[COORDINATOR]` 标签(若 `Coordinator.isEnabled(cfg)`)

## 模块交互

### TeamCreate 调用路径

```
LLM 调 TeamCreate(teamName="demo")
  ↓
TeamCreateTool.execute
  ↓
TeamManager.create("demo", "")
  ↓
1. sanitize("demo") → "demo"
2. Backend.detect() → TMUX
3. Files.createDirectories(~/.cortex/teams/demo/)
4. Files.createDirectories(~/.cortex/teams/demo/mailbox/)
5. 写 config.json(原子;.tmp + ATOMIC_MOVE)
6. team.members = [new TeammateInfo("lead","lead", ..., null)]
7. teams.put("demo", team)
  ↓
返回 {"teamName":"demo","backend":"tmux","configPath":"..."}
```

### Agent(teamName=...) spawn 路径

```
LLM 调 Agent(teamName="demo", subagentType="general-purpose", name="alice", prompt="...")
  ↓
agent.AgentTool.execute
  ↓
判断 teamName != null → 委托给 teamHook.spawnTeammate
  ↓
TeamManager.spawnTeammate(req)
  ↓
1. TeamManager.get("demo") 取 Team
2. 校验调用者权限(in-process 队员不许 spawn,Pane 队员可以但 teamName 屏蔽)
3. Catalog.resolve(agentType) 取 SubAgentDefinition
4. memberName = req.name()(或自动 alice/agent-a1b2c3)
5. worktreeManager.create("team-demo/"+memberName, "HEAD", false) → worktree
6. 申请 sessionDir(util 方法,沿用 ch12 格式)
7. 构造 SpawnRequest
8. 若 backend=IN_PROCESS:
   - 构造 subAgent(SessionRuntime + withCwd + withAllowedTools 含协作工具)
   - 构造 subConv(NewFromMessages 走 Fork 路径,或空 Conv 走定义式)
   - 注入 <team-context> reminder
   - 注入 systemPrompt 附录(F39)
   - SpawnRequest 的 subAgent / conv / taskManager 填好
9. backend.spawn(req) → SpawnResult(paneId, agentId)
10. registry.register(memberName, agentId)
11. team.addMember(new TeammateInfo(...))
  ↓
返回 {"memberName":"alice","agentId":"...","worktree":"...","backend":"tmux"}
```

### SendMessage 调用路径

```
LLM 调 SendMessage(to="alice", summary="hi", message="hello")
  ↓
SendMessageTool.execute
  ↓
1. 取调用者所属 Team(从 invocation ctx 中的 TeammateContext 取,或主 Agent 走 active team)
2. resolve to:
   - "*" → 广播
   - 否则 registry.resolve(to) → agentId
3. 校验消息类型权限(plan_approval_response 仅 Lead,shutdown_response 仅发给 Lead)
4. 对每个目标 agentId:
   - mailbox.write(agentId, msg)
   - 取 TeammateInfo.paneId 与 backendType
   - 若 Pane 后端:backend.wake(paneId, agentId)
   - 若目标已 stop(in-process,taskManager.get(agentId).status() != RUNNING):
     - 从 sessionDir 恢复 Conv
     - taskManager.sendMessage(parentCtx, name, message) 续派
5. 返回 {"deliveredTo":["agent-xxx"],"timestamp":...}
```

### 队员 Loop 内邮箱注入

```
队员的 Agent.run 每轮迭代开头(在调 LLM 前):
  ↓
读 invocation ctx 中的 TeammateContext(包含 Mailbox 闭包、agentId)
  ↓
ReadUnreadResult ur = mailbox.readUnread(agentId)
  ↓
若 !ur.indices().isEmpty():
  reminder = buildIncomingMessagesReminder(ur.msgs())
  把 reminder 加入本轮 systemReminders
  mailbox.markRead(agentId, ur.indices())
```

`Agent` 已有 systemReminders 注入机制(ch05 / ch07 plan reminder 走同一通道);新增一种 reminder 来源即可。

### 队员 runToCompletion 结束的通知

```
TaskManager.runTask virtual thread 结束(完成 / 失败 / 取消)
  ↓
若该 task 关联到 Team 队员(通过 registry.nameOf(agentId) 反查 name → 查 team)
  ↓
team.setMemberActive(memberName, false)
mailbox.write(leadAgentId, new Message("...idle", ...))
backend.wake(leadPaneId, leadAgentId)  // 若 Lead 是 Pane 后端
```

需要在 `TaskManager.runTask` 的 finally 中加 hook,或者在 `team` 包注册一个回调到 task 包(走依赖反转)。**采用方案:** 在 `TaskManager` 新增 `onTaskDone(Consumer<String> cb)` 回调注册接口,`team` 包初始化时注册。

### Coordinator Mode 启用路径

```
Cortex.java 启动时,在构造主 Agent 后:
  ↓
if (Coordinator.isEnabled(cfg)) {
    mainAgent.setAllowedTools(Coordinator.allowedTools());
    mainAgent.appendSystemPrompt(Coordinator.systemPromptSuffix());
    tuiParams.coordinatorMode(true);
}
```

TUI 渲染 statusbar 时检测 `coordinatorMode` 添加 `[COORDINATOR]` 标签。

## 文件组织

```
src/main/java/com/cortex/
├── team/
│   ├── package-info.java                 — 包文档
│   ├── Team.java                         — Team 类
│   ├── TeammateInfo.java                 — record
│   ├── TeamManager.java                  — create/get/delete/...
│   ├── BackendType.java                  — 枚举
│   ├── Persistence.java                  — sanitize + atomicWriteJson + readJson
│   ├── SpawnTeammate.java                — TeamManager.spawnTeammate 主流程拆分
│   ├── Feature.java                      — FORK_TEAMMATE flag 读取
│   ├── exceptions/
│   │   ├── TeamNotFoundException.java
│   │   ├── TeamHasActiveMembersException.java
│   │   ├── MemberExistsException.java
│   │   ├── MemberNotFoundException.java
│   │   └── InProcessTeammateNoSpawnException.java
│   ├── filelock/
│   │   └── FileLock.java                 — 共用文件锁(O_EXCL + 抖动 + stale)
│   ├── backend/
│   │   ├── Backend.java                  — 接口 + SpawnRequest / SpawnResult
│   │   ├── BackendFactory.java
│   │   ├── BackendDetector.java          — Backend.detect()
│   │   ├── tmux/TmuxBackend.java
│   │   ├── iterm2/Iterm2Backend.java
│   │   └── inprocess/InProcessBackend.java
│   ├── mailbox/
│   │   ├── MessageType.java
│   │   ├── Message.java                  — record
│   │   ├── Mailbox.java                  — write/read/readUnread/markRead
│   │   └── ReadUnreadResult.java         — record
│   ├── registry/
│   │   └── AgentNameRegistry.java
│   ├── tasks/
│   │   ├── Status.java
│   │   ├── Task.java                     — record
│   │   ├── Filter.java                   — record
│   │   ├── Patch.java                    — record
│   │   └── Store.java
│   └── tools/
│       ├── TeamCreateTool.java
│       ├── TeamDeleteTool.java
│       ├── TaskCreateTool.java
│       ├── TaskGetTool.java
│       ├── TaskListTool.java
│       ├── TaskUpdateTool.java
│       ├── SendMessageTool.java
│       └── TeammateToolFilter.java       — 队员专属工具白名单(注入到 applyAgentToolFilter)
│
├── coordinator/
│   └── Coordinator.java                  — isEnabled/allowedTools/systemPromptSuffix
│
├── agent/
│   ├── AgentTool.java                    — 修改:增加 teamName 参数与 TeamHook 委托
│   ├── TeamHook.java                     — 新建:接口 + TeammateContext
│   ├── TeammateContext.java              — 新建:闭包式 Mailbox + agentId 等
│   ├── TeamMailboxIngestor.java          — 新建:Loop 头部读 mailbox 注入 reminder
│   └── ... (其他 ch13 文件)
│
├── task/
│   └── TaskManager.java                  — 修改:onTaskDone 回调;改用 AgentNameRegistry
│
├── command/builtin/
│   └── BuiltinTeam.java                  — 新建:/team list/info/delete/kill 4 个命令
│
├── tui/
│   ├── CortexModel.java                       — 修改:接收 teamManager;启动时检测 Coordinator
│   ├── Statusbar.java                    — 修改:渲染 [COORDINATOR] 标签
│   ├── LeadMailWatcher.java              — 新建:每秒轮询 lead mailbox
│   └── LeadMailWaiter.java               — 新建:阻塞等 leadMailQueue 信号
│
├── config/
│   └── AppConfig.java                    — 修改:新增 FeaturesConfig(coordinatorMode + forkTeammate)
│
├── cli/
│   └── TeamMemberRunner.java             — 新建:--team-member 子进程自治循环入口
│
└── Cortex.java                             — 修改:wire TeamManager,注册 7 个新工具,接入 Coordinator,
                                            --team-member 分支
```

测试目录镜像 `src/test/java/dev/cortex/team/...`,所有公开类都有 JUnit 5 测试。

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| Team 包归属 | `com.cortex.teams` 顶层 | 与 ch13 `subagent`、ch14 `worktree` 平级,职责清晰 |
| 后端三选一时机 | `Backend.detect()` 在 `TeamCreate` 时一次性决定 | 与 README 一致:不做运行时回退,行为可预测 |
| 后端实现拆分 | 各一个子包 `tmux/iterm2/inprocess` | `inprocess` 需要依赖 `agent` 包,拆开避免污染其他 backend |
| Backend 接口 | 三方法 `spawn/wake/kill` | 最小集;不引入 pause/resume(本期不做) |
| Lead 表示 | 不引入独立类型,Lead = `CortexModel.mainAgent()` | 收窄改动;Coordinator Mode 在工具集层面区分 |
| 邮箱实现 | `<teamConfigDir>/mailbox/<agentId>.json` + 同名 `.lock` | 跨进程通信现成方案;in-process 与 Pane 共用一套 |
| 锁文件参数 | `StandardOpenOption.CREATE_NEW`,5-100ms 抖动 10 次,>10s 视 stale | README 明定;避免雪崩 |
| 任务存储 | `<teamConfigDir>/tasks.json` 单文件 | Team 内任务量小(几十条),无需 DB;原子写 + 文件锁 |
| AgentNameRegistry 归属 | 独立 `team.registry` 包,`TaskManager` 委托 | 解耦;消除 ch13 `TaskManager.byName` 的局部状态 |
| `TaskManager` 改造 | 加 `onTaskDone` 回调,Team 注册 | 依赖反转,避免 task 包反向依赖 team |
| Team 持久化原子性 | `<file>.tmp` + `Files.move(...,ATOMIC_MOVE)` | 与 ch14 worktree session、ch12 session 一致 |
| Worktree 命名 | `team-<sanitizedTeam>/<member>`(嵌套 slug,`/` → `+`) | 复用 ch14 嵌套 slug 能力;不污染顶层 worktree 命名空间 |
| Member sessionDir | 沿用 ch12 `<root>/.cortex/sessions/<id>/` 格式 | 复用 `session.Writer`,无需新机制;Team 删除时一并清理 |
| Coordinator 开启检测 | `Feature.has("COORDINATOR_MODE", cfg) && envTruthy(env)` | README 明定双锁;一次决定不允许运行时改 |
| Coordinator 工具白名单 | 硬编码常量,启动时直接 `setAllowedTools` | LLM 无法解锁,安全边界清晰 |
| Plan 审批本期形态 | 文本 Plan + Lead 用 `plan_approval_response` 回复 | 不强制结构化 Plan 类型,降低实现成本 |
| Fork 队员 | 受 `FORK_TEAMMATE` flag 控制,默认关 | README 明定;避免默认带满上下文 |
| 收敛 merge | 不提供专用工具,Lead 用 Bash 自主跑 git | README 明定;LLM 解冲突 = 语义理解,这是 LLM 优势 |
| `Agent` 工具的 `teamName` 在 in-process 队员处可见性 | 参数对模型可见,但调用时拦截抛 exception | 与其在 schema 层动态裁剪不如统一 schema + 运行时校验,缓存友好 |
| 队员 Loop 邮箱注入 | 复用 `Agent` 既有 systemReminders 通道,新增一种 reminder 来源 | 不改 Loop 主流程,改动最小 |
| TUI Coordinator 标签 | 状态栏静态渲染 | 视觉提示,运行时不可改 |
| 多 Team 并存 | `TeamManager.teams` map 支持,但 spawn 时按 `teamName` 显式选 | 灵活;典型场景同一时刻一个活跃 Team |
| Team 删除时 Worktree 处理 | 调 `worktreeManager.remove(name, new RemoveOptions(true))`,失败只警告 | 与 ch14 退出语义一致;`force=true` 才放行,无 force 时有活跃成员就拒删,有变更也保留(自动 cleanup 已处理) |
| 错误命名 | 自定义异常类 `TeamHasActiveMembersException` / `InProcessTeammateNoSpawnException` 等 | 调用方可 `catch` 判别;Java 用受检异常或 RuntimeException 子类按场景选 |
| Pane 子进程 CLI 解析 | picocli(`info.picocli:picocli`) | 注解式声明 flag,生成 usage 友好;比 commons-cli 更现代 |
| JSON 库 | Jackson Databind | 与 ch04 ConfigLoader 之外的 JSON 全部统一;`@JsonCreator` / `@JsonProperty` 直接读写 record |
| virtual thread vs platform thread | 邮箱 watcher、stdin scanner、in-process spawn 全部 `Thread.startVirtualThread` | I/O 密集,virtual thread 调度成本极低;不阻塞 carrier thread |