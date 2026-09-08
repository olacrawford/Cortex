# SubAgent 机制 Plan

## 架构概览

本章实现拆为四个层次：

1. **subagent 包**（新增,核心数据层）——定义 Agent 角色的数据结构、Markdown+YAML 解析、Catalog 多来源加载、内置角色 classpath resource
2. **task 包**（新增,后台运行层）——`task.Manager` 管理后台任务生命周期,4 个内置工具(TaskList/TaskGet/TaskStop/SendMessage)
3. **agent 包扩展**——新增 `runToCompletion` 方法、5 个新 Builder 选项、Fork 路径辅助函数 `buildForkedMessages`、子 Agent 权限升级 callback
4. **工具与 TUI 集成层**——Agent 工具实现、工具过滤多层防线常量、TUI 接入 task notification、ESC 切后台、Skill fork 改造为复用 SubAgent 底座

模块构成：

- `subagent.Definition` / `subagent.Catalog` / `subagent.Source` — 数据结构与三层加载
- `subagent/builtin/*.md` — 内置 3 个角色文件,放在 `src/main/resources/subagent/builtin/`,通过 classpath resource 读取
- `task.Manager` / `task.BackgroundTask` — 后台任务管理与生命周期
- `task` 包内 4 个 `Tool` 实现 — 注册到 `ToolRegistry`
- `Agent.runToCompletion` / `Agent.Builder.systemPrompt / provider / maxTurns / permissionMode / approvalUpgrader` — Agent 包扩展
- `agent.Fork` — `buildForkedMessages`、Fork Boilerplate 常量
- `agent.AgentTool` — Agent 工具实现
- `tool.Filter` — `ALL_AGENT_DISALLOWED_TOOLS` / `ASYNC_AGENT_ALLOWED_TOOLS` 常量与过滤函数
- `tui` 改动 — TaskManager 注入、ESC 切后台、`<task-notification>` 注入、子 Agent 审批弹窗
- `tui/SkillFork.java` 改造 — 复用 `subagent.LaunchFork`

## 核心数据结构

### subagent.Definition

```java
package com.mewcode.subagent;

import com.mewcode.permission.PermissionMode;

/**
 * Definition 是一个 Agent 角色的完整定义,从 Markdown + YAML frontmatter 解析。
 * 字段对齐 spec F4。
 */
public record Definition(
        String name,             // frontmatter.name (-> agentType)
        String description,      // frontmatter.description (-> whenToUse)
        java.util.List<String> tools,             // frontmatter.tools 白名单;空表示不收窄
        java.util.List<String> disallowedTools,   // frontmatter.disallowedTools 黑名单
        String model,            // "haiku" | "sonnet" | "opus" | "inherit";缺省 "inherit"
        int maxTurns,            // 0 表示沿用全局默认 (25)
        PermissionMode permissionMode, // "dontAsk" 单独处理(见 dontAsk 字段)
        boolean dontAsk,         // 是否启用"绕过 Ask"的子 Agent 兜底模式
        boolean background,      // 强制后台
        String systemPrompt,     // Markdown body(去 frontmatter 后的全文)
        String filePath,         // 定义文件绝对路径 / classpath uri(用于调试)
        Source source            // Source.BUILTIN / USER / PROJECT / PLUGIN
) {
    public boolean isFork() {
        return "__fork__".equals(name);
    }
}

public enum Source {
    BUILTIN, USER, PROJECT, PLUGIN; // 占位

    @Override
    public String toString() {
        return switch (this) {
            case BUILTIN -> "builtin";
            case USER    -> "user";
            case PROJECT -> "project";
            case PLUGIN  -> "plugin";
        };
    }
}
```

### subagent.Catalog

```java
package com.mewcode.subagent;

public final class Catalog {
    private final Object lock = new Object();
    private final java.util.Map<String, Definition> defs = new java.util.HashMap<>();
    private final java.util.EnumMap<Source, java.util.List<Definition>> bySource = new java.util.EnumMap<>(Source.class);

    /**
     * 顺序加载:builtin -> user -> project,优先级高的覆盖低的;
     * 解析错误走 stderr 警告并跳过;返回非 null Catalog 即使无任何定义。
     */
    public static Catalog load(java.nio.file.Path root) { ... }

    public java.util.Optional<Definition> resolve(String name) { ... }
    public java.util.List<Definition> list() { ... } // 按 name 排序
    public java.util.List<Definition> listBySource(Source s) { ... }

    /**
     * forkDefinition 返回一个"Fork 路径"用的临时 Definition——name="__fork__",systemPrompt="" (子 Agent 走继承的系统提示),
     * 但 disallowedTools 不应包含 Agent 工具(Fork 子 Agent 工具集保留 Agent,靠 QuerySource 阻断)。
     */
    public Definition forkDefinition() { ... }
}
```

### task.Manager 与 BackgroundTask

```java
package com.mewcode.task;

/**
 * BackgroundTask 是一个后台子 Agent 的完整状态快照。
 */
public final class BackgroundTask {
    final String id;                 // manager 生成,如 "task_<8 字节十六进制>"
    final String name;               // F1 中 Agent 工具 name 参数,可空
    final com.mewcode.agent.Agent subAgent;
    final com.mewcode.conversation.ConversationManager conv;
    final String task;               // 初始任务文本(SendMessage 不更新此字段)
    volatile Status status;          // RUNNING / COMPLETED / FAILED / CANCELLED
    volatile String result;          // 跑完的最终文本
    volatile Throwable err;
    final java.time.Instant startTime;
    volatile java.time.Instant endTime;
    final java.util.concurrent.atomic.AtomicBoolean cancelFlag;
    volatile Usage usage;            // 累计 token
    final java.util.concurrent.atomic.AtomicInteger toolCount;
    volatile String lastActivity;    // 最近一次工具名
    // 省略 getters
}

public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

public record Usage(long input, long output, long cacheWrite, long cacheRead) {}

/**
 * Manager 管理后台任务。线程安全。
 */
public final class Manager {
    private final Object mu = new Object();
    private final java.util.Map<String, BackgroundTask> tasks = new java.util.HashMap<>();
    private final java.util.Map<String, String> byName = new java.util.HashMap<>(); // name -> id,弱引用,后启动的覆盖
    private final java.util.concurrent.BlockingQueue<String> donePub =
            new java.util.concurrent.BlockingQueue<>(); // 缓冲 32

    public Manager() { /* BlockingQueue 构造时设置 maxBufferCapacity */ }

    /**
     * launch 起一个后台 virtual thread 跑 agent.runToCompletion;conv 应该是已经装填了消息的子对话。
     * 返回 ID;virtual thread 内部跑完后写 status/result + submit 到 donePub。
     */
    public String launch(java.util.concurrent.atomic.AtomicBoolean parentCancel,
                         com.mewcode.agent.Agent ag,
                         com.mewcode.conversation.ConversationManager conv,
                         String name, String task) { ... }

    /**
     * adoptRunning 把一个正在前台跑的 agent 移交到后台。
     * 调用方应已经把"用户的 ESC / 120 秒超时"对应的 cancelFlag 准备好,
     * 并把已 partial 收集的事件吐到 partial 内。Manager 接管 eventSub 事件流继续消费,直到 Done 或 Err。
     */
    public String adoptRunning(java.util.concurrent.atomic.AtomicBoolean parentCancel,
                               com.mewcode.agent.Agent ag,
                               com.mewcode.conversation.ConversationManager conv,
                               String name,
                               java.util.concurrent.Flow.Subscription eventSub,
                               java.util.concurrent.atomic.AtomicBoolean cancelFlag,
                               PartialState partial) { ... }

    /** PartialState 是前台→后台移交时已收集的中间状态。 */
    public record PartialState(String lastAssistantText, int toolCount, String lastActivity, Usage usage) {}

    public java.util.Optional<BackgroundTask> get(String id) { ... }
    public java.util.List<BackgroundTask> list() { ... } // 按 startTime 升序
    public boolean stop(String id) { ... }

    /**
     * subscribeDone 返回 publisher;TUI 订阅,收到 id 后调 get 拿状态,
     * 把 <task-notification> 拼到 runtime.pendingReminders。
     */
    public java.util.concurrent.BlockingQueue<String> subscribeDone() { return donePub; }

    /**
     * sendMessage 给一个仍存活的后台 Agent 续派任务。
     * 找不到 name -> 抛 TaskNotFoundException;status != COMPLETED -> 抛 TaskBusyException。
     * 成功时把 message 加到 conv,重新 launch 一个新轮(返回同 id,状态从 COMPLETED 重置回 RUNNING)。
     */
    public String sendMessage(java.util.concurrent.atomic.AtomicBoolean parentCancel,
                              String name, String message) throws TaskNotFoundException, TaskBusyException { ... }
}
```

### agent 包扩展

```java
package com.mewcode.agent;

public final class Agent {

    // ───── 新增方法 ─────

    /**
     * runToCompletion 执行子 Agent 的"跑到底"循环。
     * 复用主 run 的几乎所有逻辑(streamOnce / executeBatched / 权限判定),区别:
     *   - 不通过 publisher 返回事件(内部消费),最终返回 finalText
     *   - maxTurns 由 a.maxTurns 决定(若 0 则用 MAX_ITERATIONS)
     *   - 不触发 memory update / 不触发 compact reminder 等主对话专属逻辑(子 Agent 上下文短,
     *     不需要;但内部依然走 manageContextAuto 防止超长)
     *   - 接受一个可选的 events publisher,把内部事件(text/tool/approval)转发出去——
     *     TaskManager 借此聚合 toolCount/lastActivity,TUI 借此渲染前台子 Agent 的进度
     */
    public String runToCompletion(java.util.concurrent.atomic.AtomicBoolean cancelFlag,
                                  com.mewcode.conversation.ConversationManager conv,
                                  String task,
                                  java.util.concurrent.BlockingQueue<AgentEvent> events) throws Exception { ... }

    // ───── 新增 Builder 选项 ─────

    public static final class Builder {
        // ... 已有
        public Builder systemPrompt(String text) { ... }           // 子 Agent 角色 prompt
        public Builder provider(com.mewcode.llm.Provider p) { ... }
        public Builder maxTurns(int n) { ... }
        public Builder permissionMode(com.mewcode.permission.PermissionMode m) { ... }
        public Builder dontAsk(boolean enabled) { ... }            // 子 Agent dontAsk 模式
        public Builder approvalUpgrader(ApprovalUpgrader fn) { ... } // 升级到父 TUI 的 callback
        public Builder parentRegistry(com.mewcode.tool.ToolToolRegistry registry) { ... } // 暂时与 registry 等价,显式区分语义
    }

    /**
     * ApprovalUpgrader 是子 Agent 把审批请求升级到父 TUI 的回调。
     * 实现方:TaskManager 把请求转发到主 TUI 的事件流;前台 inline 模式直接复用现有 Approval 路径。
     * 返回 Optional.empty() 表示不接管,调用方继续走默认路径。
     */
    @FunctionalInterface
    public interface ApprovalUpgrader {
        java.util.Optional<com.mewcode.permission.Outcome> upgrade(
                java.util.concurrent.atomic.AtomicBoolean cancelFlag,
                ApprovalRequest req);
    }
}
```

`Agent` 类新增字段:
- `systemPrompt`(String) — 非空时 `buildEnvText` / `buildSystemPrompt` 阶段用此覆盖默认
- `maxTurns`(int) — 0 表示用全局 `MAX_ITERATIONS`
- `permissionMode`(PermissionMode) — 子 Agent 启动模式(主 Agent 用 TUI 的运行时 mode)
- `dontAsk`(boolean)
- `approvalUpgrader`(ApprovalUpgrader)

### Fork.java 内容

```java
package com.mewcode.agent;

public final class Fork {

    public static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

    /** ForkBoilerplate 是 Fork 子 Agent 首条 user 消息的前缀,约束其行为。 */
    public static final String FORK_BOILERPLATE = """
            <fork_boilerplate>
            你是一个 Fork 出来的工作进程。你不是主 Agent。
            规则(不可协商):
            1. 不能再 Fork(调用 Agent 工具会被拦截)。
            2. 不要对话、不要提问、不要请求确认。
            3. 直接使用工具:读文件、搜索代码、做修改。
            4. 严格限制在你被分配的任务范围内。
            5. 最终报告以 "Scope:" 开头,500 字以内。
            </fork_boilerplate>

            """;

    /**
     * buildForkedMessages 把父对话克隆到 Fork 子对话,处理悬空 tool_use,追加 Boilerplate+task。
     * 行为:
     *   1. 深拷贝 parentMsgs(所有 Message + 内部 toolCalls/toolResults List)
     *   2. 扫描末尾 assistant 消息的 toolCalls,如果对应的 ROLE_TOOL 消息缺失,
     *      生成一条 placeholder ROLE_TOOL 消息(每个 ID 对一条"[forked, skipped]" 错误内容)
     *   3. 追加 user 消息 = FORK_BOILERPLATE + task
     * 返回新消息列表,直接用 Conversation.fromMessages 装载即可。
     */
    public static java.util.List<com.mewcode.llm.Message> buildForkedMessages(
            java.util.List<com.mewcode.llm.Message> parentMsgs, String task) { ... }

    /**
     * isForkContext 判定一个 conversation 的消息历史是否来自 Fork(用 FORK_BOILERPLATE_TAG 扫描)。
     * QuerySource 检测的兜底机制——caller 链丢失时靠这个。
     */
    public static boolean isForkContext(java.util.List<com.mewcode.llm.Message> msgs) { ... }
}
```

### Agent 工具

`dev/mewcode/agent/AgentTool.java`：

```java
package com.mewcode.agent;

/**
 * AgentTool 是注册到 ToolRegistry 的统一 Agent 工具。
 */
public final class AgentTool implements com.mewcode.tool.Tool {
    private final AgentCatalogPort catalog;     // 接口,避免反向依赖 subagent 包
    private final TaskManagerPort taskMgr;
    private volatile Agent parentAgent;         // 取 provider/registry/eng/runtime 等
    private final boolean bgEnabled;            // N6 配置开关

    public AgentTool(AgentCatalogPort catalog, TaskManagerPort mgr, Agent parent, boolean bgEnabled) { ... }
    public void setParent(Agent a) { this.parentAgent = a; }

    @Override public String name() { return "Agent"; }
    @Override public boolean readOnly() { return false; }  // 子 Agent 可能做任何事
    @Override public String description() {
        // 列出已知的 subagent_type 名,从 catalog.list() 渲染
        ...
    }

    /**
     * execute 主流程:
     *   1. 解析 args -> AgentArgs(prompt, description, subagentType, model, runInBackground, name)
     *   2. 校验:prompt 非空、description 非空
     *   3. 检测嵌套:从 ctx 取 ParentInfo,若 parent 已是子 Agent 或对话历史含 fork tag -> 返回错误
     *   4. resolve 定义:subagentType 非空走 catalog.resolve,空走 catalog.forkDefinition
     *   5. 决定 background:def.background || args.runInBackground || (是 fork)
     *   6. 应用工具过滤多层防线 Filter.applyAgentToolFilter,得到 allowed List<String>
     *   7. 选 provider:args.model 非空 -> 切;否则 def.model 非 inherit -> 切;否则用 parent
     *   8. 构造子 Agent + 子 Conversation(空白或 Fork 路径装填消息)
     *   9. 前台路径:开 cancelFlag + ScheduledFuture(120s 超时),跑 runToCompletion;
     *      - 完成 → 返回 finalText
     *      - 超时/ESC → adoptRunning,返回 {task_id, status:"timed_out_to_background"}
     *  10. 后台路径:launch,返回 {task_id, status:"async_launched"}
     */
    @Override
    public com.mewcode.tool.ToolResult execute(com.mewcode.tool.ExecutionContext ctx, com.fasterxml.jackson.databind.JsonNode args) { ... }
}
```

### 工具过滤多层防线

`dev/mewcode/tool/Filter.java`:

```java
package com.mewcode.tool;

public final class Filter {

    /**
     * ALL_AGENT_DISALLOWED_TOOLS 是任何子 Agent 永远不能用的工具名列表。
     * 本期最小列表:Agent。后续可扩展 AskUserQuestion / TaskStop / 系统级敏感工具。
     */
    public static final java.util.List<String> ALL_AGENT_DISALLOWED_TOOLS = java.util.List.of("Agent");

    /**
     * CUSTOM_AGENT_DISALLOWED_TOOLS 是自定义(user / project / plugin 来源)Agent 比内置 Agent 多禁用的工具。
     * 本期为空。
     */
    public static final java.util.List<String> CUSTOM_AGENT_DISALLOWED_TOOLS = java.util.List.of();

    /**
     * ASYNC_AGENT_ALLOWED_TOOLS 是后台 Agent 工具白名单。
     * 不含 Agent / TaskStop / SendMessage / TaskList / TaskGet 等任何元工具。
     */
    public static final java.util.List<String> ASYNC_AGENT_ALLOWED_TOOLS = java.util.List.of(
            "ReadFile", "WriteFile", "EditFile",
            "Glob", "Grep",
            "Bash",
            "load_skill", "install_skill"
    );
    // MCP 工具与 Skill 工具按工具命名约定动态识别(以 "mcp__" 起头 / 来自 registerSkillTool),
    // 通过 isAllowedInBackground 函数走另一条分支判定。

    /** FilterParams 是过滤一个 Agent 的工具列表的参数。 */
    public record FilterParams(
            java.util.List<String> all,        // registry 的全部工具名(按注册顺序)
            int source,                        // 1=builtin, 2=user, 3=project, 4=plugin
            boolean background,
            java.util.List<String> allowed,    // Agent 定义 frontmatter.tools 白名单
            java.util.List<String> disallowed  // Agent 定义 frontmatter.disallowedTools 黑名单
    ) {}

    /**
     * applyAgentToolFilter 按 spec F30 顺序过滤。
     * 返回最终 allowed 列表(传给 Agent.Builder.allowedTools)。
     */
    public static java.util.List<String> applyAgentToolFilter(FilterParams p) { ... }
}
```

### TUI 集成层

`dev/mewcode/tui/MewCodeModel.java` 改动：
- `TuiParams` record 加 `Manager taskMgr / Catalog subAgentCatalog`(由 Main 注入)
- `MewCodeModel` 持有 `taskMgr` / `subAgentCatalog`
- `init()` 末尾启动一个 virtual thread 订阅 `taskMgr.subscribeDone()`,把 `<task-notification>` 拼成 reminder 推到 `runtime.appendReminders`
- 主对话 Agent 通过 `Agent.Builder.approvalUpgrader(taskMgr::upgradeApproval)` 让子 Agent 审批升级回主 TUI

`dev/mewcode/tui/Stream.java` 改动：
- `updateStreaming` 监听 ESC 键(JLine/tui.tea `KeyType.Escape`):若 `state==STREAMING` 且当前有运行中的 SubAgent → 调 `taskMgr.adoptRunning`,切回 IDLE 态
- 监听 SubAgent ApprovalRequest 转发——TaskManager 通过 events publisher 转回主 TUI 走现有 Approval 路径

`dev/mewcode/tui/SkillFork.java` 改造：
- 删除现有 `runSubAgent` 内的零散逻辑
- 改为调 `subagent.LaunchFork.launch(ctx, host, opts, conv)`,host 持有 `taskMgr` / `runtime` / `engine` 等

## 模块设计

### 模块 A:subagent 包

**职责:**
- 数据结构 `Definition`
- Markdown + YAML 解析(复用 `skills.Parser` 的 `parseFrontmatterAndBody`——抽到 `util.markdown` 让两方共用 OR skills 与 subagent 都各自有一份)
- 三层 + 内置 classpath resource 加载

**对外接口:**
- `Catalog.load(Path root) -> Catalog`
- `Catalog.resolve(name)` / `list()` / `forkDefinition()`

**依赖:**
- `com.mewcode.permission`(解析 `permissionMode` 字段)
- `org.yaml:snakeyaml`
- JDK `java.nio.file`、`java.lang.Class#getResourceAsStream`

**关键设计:**
- Markdown 解析复用 `skills.Parser` 的 `parseFrontmatterAndBody`——抽到 `subagent.Parser` 独立实现一份(避免互相依赖),内容几乎一致
- 内置文件 `subagent/builtin/general-purpose.md` / `explore.md` / `plan.md` 放在 `src/main/resources/subagent/builtin/`,通过 `Catalog.class.getResourceAsStream("/subagent/builtin/<name>.md")` 加载
- 加载错误统一 stderr `System.err.printf("subagent %s: ... skipped%n", ...)`

### 模块 B:task 包

**职责:**
- 后台任务生命周期管理
- 4 个内置工具(TaskList/TaskGet/TaskStop/SendMessage)

**对外接口:**
- `new Manager()`
- `launch / adoptRunning / get / list / stop / sendMessage / subscribeDone`
- `new TaskListTool(Manager m)` 等四个工厂

**依赖:**
- `com.mewcode.agent`(Agent)
- `com.mewcode.conversation`
- `com.mewcode.tool`
- `com.mewcode.llm`

**关键设计:**
- `donePub` 是 `BlockingQueue<String>`,`maxBufferCapacity` 设为 32 够大,正常场景不可能填满;真满时 `offer` 返回负数 → 走 stderr 警告(主 TUI 漏一条通知不致命)
- `launch` virtual thread 包 try/catch:`Throwable t` → status=FAILED,err=t
- `stop` 把 `task.cancelFlag` 置 true;`Agent.runToCompletion` 每轮检查
- `sendMessage`:仅当 `status == COMPLETED` 时允许;否则 `TaskBusyException`。重新 launch 时用 <em>*同 id*</em>,status 从 COMPLETED 重置回 RUNNING

### 模块 C:agent 包扩展

**职责:**
- 新增 `runToCompletion` 方法
- 新增 5 个 Builder 选项
- Fork 路径辅助

**对外新增接口:**
- `Agent.runToCompletion(cancelFlag, conv, task, events) -> String`
- `Agent.Builder.systemPrompt / provider / maxTurns / permissionMode / dontAsk / approvalUpgrader`
- `Fork.buildForkedMessages`
- `Fork.isForkContext`

**关键设计:**
- `runToCompletion` 与 `run` 共用 `streamOnce` / `executeBatched` / `manageContextAuto` /
  `recordReadFileIfApplicable`,通过抽公共 helper 实现共享(把 `run` 的循环体抽到
  `runIter(cancelFlag, conv, mode, iter, ...)`,`run` 与 `runToCompletion` 都调它)
- 子 Agent 的 `permissionMode` + `dontAsk` 决策点在 `executeBatched` 的 `runGuarded` 内多一层短路:
  ```java
  if (a.dontAsk) {
      // 角色定义 dontAsk:走 sandbox/黑名单/规则后,默认 Allow 而非 Ask
      if (d == Decision.ASK) d = Decision.ALLOW;
  }
  ```
- 升级到父 TUI 的 callback 在 `requestApproval` 里调:
  ```java
  if (a.approvalUpgrader != null) {
      var maybe = a.approvalUpgrader.upgrade(cancelFlag, req);
      if (maybe.isPresent()) return maybe.get();
  }
  // 否则走默认 emit Approval event 路径(主 Agent inline 子 Agent 路径)
  ```

**Fork Boilerplate 注入策略:**
- `Fork.buildForkedMessages` 把 Boilerplate 写在 user 消息开头(与 ch13 README 一致)
- `Fork.isForkContext` 用 `String.contains` 扫描 <em>*所有*</em> 历史 user 消息内容寻找 `<fork_boilerplate>`(QuerySource 兜底)

### 模块 D:Agent 工具与 TUI 集成

**职责:**
- 把 Agent 工具注册到 registry
- TUI 接入 task notification
- 改造 Skill fork

**对外接口:**
- `new AgentTool(catalog, taskMgr, parentAgent, bgEnabled)`
- `subagent.LaunchFork.launch(ctx, host, opts)` 公共 Fork 启动函数(Skill fork 与 Agent 工具都调)

**关键设计:**
- `AgentTool.execute` 在前台 inline 路径返回结果时要小心:
  - 前台跑完返回 finalText 作为 tool_result content
  - 中途超时切后台 → 返回 JSON `{"task_id":"...","status":"timed_out_to_background"}`
- 嵌套阻断:`AgentTool.execute` 入口检查 `ctx` 是否携带 `PARENT_AGENT_KEY`(子 Agent 启动时塞入);若有 → 返回结构化错误
  - 不依赖 ctx 单值:也扫 conv 历史是否含 Fork tag(`Fork.isForkContext`)
- TUI 的 task notification 注入:
  - `init()` 启动 `Thread.startVirtualThread(this::consumeTaskDone)`
  - `consumeTaskDone` 订阅 `donePub`,`get` 拿状态,渲染成 `<task-notification>` 块,调 `runtime.appendReminders` 推入
  - 主对话下一次 `run` 自动拿到(已有机制)

## 模块交互

### 启动期 wiring

```
MewCode.java
  ├── new ToolRegistry()       → registry
  ├── new PermissionEngine(root) → engine
  ├── new SessionRuntime        → runtime
  ├── skills.Catalog.load      → skillCatalog
  ├── hook.HookEngine.load      → hookEngine
  ├── subagent.Catalog.load     → subagentCatalog          ← 新增
  ├── new task.Manager()        → taskMgr                  ← 新增
  ├── registry.register(new task.TaskListTool(taskMgr))    ← 新增
  ├── registry.register(new task.TaskGetTool(taskMgr))     ← 新增
  ├── registry.register(new task.TaskStopTool(taskMgr))    ← 新增
  ├── registry.register(new task.SendMessageTool(taskMgr)) ← 新增
  ├── new MewCodeModel(..., TuiParams(taskMgr, subagentCatalog, ...))
  │     │
  │     └── 在 MewCodeModel 构造内:Agent 工具的注册被推迟到主 Agent 构造后(因为要把 parentAgent 注入),
  │         或者 Agent 工具 lazy 拿:把 catalog/taskMgr 写死,parentAgent 通过 setParent 注入
```

**简化方案:** Agent 工具在 `MewCode.java` 注册,`parentAgent` 字段在 `new MewCodeModel(...)` 后回填:
```java
AgentTool agentTool = new AgentTool(subagentCatalog, taskMgr, null, cfg.enableSubAgentBackground());
registry.register(agentTool);
// 再 new MewCodeModel(...)
// 再 agentTool.setParent(tuiApp.mainAgent());
```

### 运行时:主 Agent 调 Agent 工具(前台,定义式)

```
LLM 流式产出 tool_use:{name:"Agent",input:{prompt:"...",subagent_type:"Explore"}}
    ↓
Agent.executeBatched → 路由到 AgentTool.execute(ctx, args)
    ↓
AgentTool.execute:
    1. 解析参数 -> AgentArgs
    2. 防嵌套:检测 ctx / conv 是否来自 Fork → 否
    3. resolve("Explore") → def
    4. background = def.background() || args.runInBackground() → false
    5. Filter.applyAgentToolFilter -> allowed
    6. provider = "haiku".equals(def.model()) ? llm.create(haiku) : parent.provider()
    7. SessionRuntime subRuntime = new SessionRuntime(200_000);
    8. Agent subAgent = Agent.builder()
           .provider(provider).registry(registry).version(version).engine(engine)
           .runtime(subRuntime)
           .allowedTools(allowed)
           .systemPrompt(def.systemPrompt())          // 新
           .maxTurns(def.maxTurns())
           .permissionMode(def.permissionMode())
           .dontAsk(def.dontAsk())
           .approvalUpgrader(parent.taskMgr()::upgradeApproval)
           .hookEngine(parent.hookEngine())
           .build();
    9. Conversation subConv = new Conversation();
    10. AtomicBoolean cancelFlag = new AtomicBoolean();
        ScheduledFuture<?> timeoutHandle = scheduler.schedule(() -> cancelFlag.set(true), 120, SECONDS);
        BlockingQueue<AgentEvent> events = new BlockingQueue<>();
        // 前台路径:把 events 转发到主 TUI(可选,本期暂不渲染前台子进度,只在状态行显示一条 "● subAgent 跑中")
        Thread.startVirtualThread(() -> events.subscribe(uiSink));
        String finalText = subAgent.runToCompletion(cancelFlag, subConv, args.prompt(), events);
    11. 是否被超时触发?
         - 是 → adoptRunning(cancelFlag(新), subAgent, subConv, args.name(), eventSub, cancel, partial)
              → 返回 JSON {"task_id": "task_xxx", "status": "timed_out_to_background"}
         - 否 → 返回 finalText 作为 tool_result content
```

### 运行时:主 Agent 调 Agent 工具(后台,显式)

```
AgentTool.execute:
    ...
    10. String taskId = taskMgr.launch(cancelFlag, subAgent, subConv, args.name(), args.prompt());
    11. 返回 JSON {"task_id": "task_xxx", "status": "async_launched"}
```

### 后台任务完成通知

```
taskMgr.launch virtual thread:
    String text = subAgent.runToCompletion(cancelFlag, conv, task, null);
    task.result = text;
    task.err = err;
    task.status = COMPLETED (or FAILED/CANCELLED);
    if (!donePub.offer(taskId, 0, TimeUnit.MILLISECONDS, ...)) {
        // 缓冲满,丢弃 + stderr 警告
    }
    ↓
MewCodeModel.consumeTaskDone (virtual thread,donePub.subscribe):
    onNext(taskId):
        var t = taskMgr.get(taskId);
        String notification = buildTaskNotification(t);  // <task-notification>...</task-notification>
        runtime.appendReminders(List.of(notification));
        // 不主动唤醒主对话:等主 Agent 下次 run 自然 take reminder
    ↓
下一次 beginTurn → agent.run → buildReminder takes pendingReminders → 注入 reminder 区
```

### Fork 路径

```
AgentTool.execute (subagentType 空):
    1. def = catalog.forkDefinition()  // name="__fork__"
    2. background = true (Fork 强制)
    3. allowed = Filter.applyAgentToolFilter(...)
       注意:这里 def.disallowedTools() 不含 "Agent" → Fork 子 Agent 工具集保留 Agent
    4. forkedMsgs = Fork.buildForkedMessages(parentConv.messages(), args.prompt())
    5. Conversation subConv = Conversation.fromMessages(forkedMsgs, ...);
    6. Agent subAgent = Agent.builder().allowedTools(allowed).systemPrompt("") ...build(); // 继承主系统提示
    7. String taskId = taskMgr.launch(cancelFlag, subAgent, subConv, args.name(), args.prompt());
    8. 返回 {"task_id": "...", "status": "async_launched"}
```

### Fork 子 Agent 调 Agent 工具被阻断

```
Fork 子 Agent 跑动中,LLM 又产 tool_use:{name:"Agent", input:{...}}
    ↓
subAgent.executeBatched → AgentTool.execute(subCtx, args)
    ↓
AgentTool.execute:
    检测:Fork.isForkContext(subConv.messages()) → true(消息中含 <fork_boilerplate>)
    → 返回 Result(isError=true, content="Fork 子 Agent 不能再启动 Agent(检测到 fork boilerplate)")
```

注:由于 `ALL_AGENT_DISALLOWED_TOOLS=["Agent"]` 已经把 Agent 工具从子 Agent 工具列表里剔除,理论上 Fork 子 Agent 的 LLM 看不到 Agent 工具。但 Fork 路径**故意保留**(为了 prompt cache 一致性),靠 QuerySource + Boilerplate 兜底拦截。

**结论:** Fork 子 Agent 工具列表 = 父工具列表 - disallowedTools - 后台白名单交集 - 但不去除 Agent 工具。

### Skill fork 改造

```
MewCodeModel.execute("/foo") → skills.Executor.execute → fork closure runSubAgent
    ↓ (改造后)
runSubAgent(cancelFlag, conv, opts):
    return subagent.LaunchFork.launch(cancelFlag, LaunchFork.fromTui(this), new ForkLaunchOpts(
        opts.allowedTools(),
        opts.model(),
        conv,                       // skills 已构造好的 forkConv
        "",                         // 走继承
        false,                      // skills 仍走前台同步(返回 finalText 给 host)
        null                        // eventsSink
    ));
```

`subagent.LaunchFork.launch` 内部:做与 `AgentTool.execute` 前台路径相同的 wiring,只是不读 catalog Definition。

## 文件组织

```
mewcode/
├── src/main/java/com/mewcode/
│   ├── subagent/                        ← 新增包
│   │   ├── package-info.java            包注释
│   │   ├── Definition.java              Definition record / Source enum
│   │   ├── Parser.java                  parseFrontmatterAndBody + validateMeta
│   │   ├── Catalog.java                 Catalog + load / resolve / list / forkDefinition
│   │   ├── BuiltinLoader.java           classpath resource 加载 + builtinDefinitions()
│   │   └── LaunchFork.java              LaunchFork / Definition 公用 wiring 辅助
│   │
│   ├── task/                            ← 新增包
│   │   ├── package-info.java
│   │   ├── Manager.java                 Manager + BackgroundTask + launch / adopt / stop / sendMessage
│   │   ├── Status.java                  enum Status
│   │   ├── Usage.java                   record Usage
│   │   ├── PartialState.java            record PartialState
│   │   ├── TaskListTool.java            new TaskListTool / new TaskGetTool / ...
│   │   ├── TaskGetTool.java
│   │   ├── TaskStopTool.java
│   │   └── SendMessageTool.java
│   │
│   ├── agent/                           ← 现有包扩展
│   │   ├── Agent.java                   现有,加 systemPrompt/maxTurns/permissionMode/dontAsk/approvalUpgrader 字段;run 抽 runIter
│   │   ├── Agent$Builder.java(内部类)    加 systemPrompt/maxTurns/permissionMode/dontAsk/approvalUpgrader/provider 选项
│   │   ├── RunToCompletion.java         ← 新增 runToCompletion 实现(也可放回 Agent.java)
│   │   ├── Fork.java                    ← 新增 buildForkedMessages / isForkContext / FORK_BOILERPLATE
│   │   ├── AgentTool.java               ← 新增 AgentTool + execute 逻辑
│   │   ├── ApprovalUpgrader.java        ← 新增 ApprovalUpgrader 接口 + DEFAULT 实现
│   │   ├── AgentCatalogPort.java        ← 新增 接口(打破对 subagent 包的循环依赖)
│   │   ├── TaskManagerPort.java         ← 新增 接口(打破对 task 包的循环依赖)
│   │   └── ...其他不动
│   │
│   ├── tool/                            ← 现有包扩展
│   │   └── Filter.java                  ← 新增 ALL_AGENT_DISALLOWED / ASYNC_AGENT_ALLOWED / applyAgentToolFilter
│   │
│   ├── tui/                             ← 现有包改动
│   │   ├── MewCodeModel.java                  加 taskMgr / subAgentCatalog 字段 + consumeTaskDone virtual thread + AgentTool 注册
│   │   ├── Stream.java                  updateStreaming 加 ESC → adoptRunning 分支;子 Agent ApprovalRequest 转发
│   │   ├── Tasks.java                   ← 新增 consumeTaskDone + buildTaskNotification + ESC 切后台辅助
│   │   ├── SkillFork.java               ← 改造为复用 subagent.LaunchFork
│   │   └── ...
│   │
│   └── config/                          ← 现有,加配置项
│       └── Config.java                  record 加 Boolean enableSubAgentBackground(默认 true)
│
├── src/main/resources/
│   └── subagent/builtin/
│       ├── general-purpose.md
│       ├── explore.md
│       └── plan.md
│
├── src/test/java/dev/mewcode/
│   ├── subagent/ParserTest.java
│   ├── subagent/CatalogTest.java
│   ├── subagent/LaunchForkTest.java
│   ├── task/ManagerTest.java
│   ├── task/ToolsTest.java
│   ├── agent/RunToCompletionTest.java
│   ├── agent/ForkTest.java
│   ├── agent/AgentToolTest.java
│   ├── agent/AgentToolIntegrationTest.java
│   ├── tool/FilterTest.java
│   └── tui/MewCodeModelTest.java
│
└── src/main/java/com/mewcode/MewCode.java  ← 加 Catalog.load / new Manager / 4 个工具注册 / AgentTool 注册
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| `runToCompletion` 与 `run` 关系 | 共用底层 helper(`runIter` / `streamOnce`),不重新写一遍循环 | 避免两套循环逻辑漂移;主对话与子 Agent 在 ReAct 层面行为应一致 |
| 子 Agent 是否独立 `PermissionEngine` | 暂共享同一 Engine,但增加 `approvalUpgrader` 让审批升级回主 TUI | 本期权限规则全局一致;独立 Engine 是为隔离规则集准备的预留扩展点 |
| Fork 强制后台 | 是 | ch13 README 设计;Fork 上下文长,前台同步会阻塞用户;并行 Fork 才有意义 |
| 后台通知形式 | system reminder 注入(`<task-notification>`),不直接 push 到 LLM | 与 ch12 pendingReminders 一致;不打断用户当前操作;主 Agent 下次 turn 自然消费 |
| 嵌套阻断三道闸 | `ALL_AGENT_DISALLOWED_TOOLS` 全局 + Fork 路径 QuerySource + Boilerplate 标记扫描 | 单一闸门失效(对话压缩、工具列表漂移)仍能兜底;定义式靠工具过滤,Fork 靠双闸 |
| 后台白名单粒度 | 列具体工具名 + MCP/Skill 工具按命名约定动态识别 | ch13 README 同款做法;不需要为每个 MCP 工具列在白名单里 |
| `donePub` 缓冲 32 | 够大 | 正常场景一会儿不会有 32 个任务同时跑完;真满则丢弃 + stderr |
| `sendMessage` 同 id 复用 | 是 | 状态语义上是"该任务继续",而非"新任务";UI/查询体验更连贯 |
| 配置开关 `enableSubAgentBackground` | 默认 true | 后台是核心能力,默认开启;关闭后所有子 Agent 强制前台,主要供 CI / 调试用 |
| Markdown 解析器复用 | 不共享,subagent 包独立实现一份(几乎与 `skills.Parser` 一致) | 避免抽公共包导致循环依赖;两个包字段不一样,复用收益有限 |
| Agent 工具的 parent 注入时机 | `MewCode.java` 注册时为 null,`new MewCodeModel(...)` 后 `setParent` 回填 | `ToolRegistry` 在 `new MewCodeModel(...)` 之前已构造,Agent 工具的 parent 依赖 `tuiApp.mainAgent()` 反推 |
| ESC 切后台 vs Ctrl+C | ESC 切后台,Ctrl+C 仍是取消(沿用现有) | ESC 在 TUI 已经做"取消选择"用途,但流式态下 ESC 转为切后台是 ch13 README 设计 |
| 并发模型 | virtual thread + `BlockingQueue`(`BlockingQueue`)+ `AtomicBoolean` 取消 | Java 21 GA;子 Agent 长跑用 virtual thread 成本极低;`BlockingQueue` 是 JDK 原生 reactive 接口,零外部依赖;`AtomicBoolean` 等价于 Go 的 `ctx.Done()` 信号位 |
| 内置 Markdown 资源 | classpath resource(`src/main/resources/subagent/builtin/*.md`)+ `Class.getResourceAsStream` | Java 没有 `go:embed`,classpath resource 是标准做法,Gradle 打 fat jar 时自动打包 |
| 循环依赖打破 | agent 包定义 `AgentCatalogPort` / `TaskManagerPort` 接口,subagent / task 实现 | subagent / task 想引用 agent.Agent,agent.AgentTool 又想引用 subagent.Definition;通过 port 接口让 agent 包成为下游,subagent / task 单向引用 agent |