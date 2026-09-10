# Worktree 隔离 Plan

## 架构概览

新建 `com.cortex.worktree` 包,集中放 `WorktreeManager`、`Worktree`、`WorktreeSession`、Slug 校验、创建后设置、自动清理、过期清理。其余包按以下方式接入:

- **`com.cortex.tool`**:新增 ``(`withCwd` / `cwd()` / `resolvePath(...)`);改造 6 个核心工具用 `ctx.resolvePath(...)`
- **`com.cortex.subagent`**:`Definition` 加 `isolation` 字段,`Parser` 解析 `isolation:` frontmatter
- **`com.cortex.agent`**:`AgentTool#execute` 加 `executeWithWorktree` 分支,启动时通过 ctx 注入 cwd
- **`com.cortex.command`**:新增 `WorktreeCommand` 内置命令,提供 `/worktree` 一级命令与子命令(create/list/enter/exit/remove)
- **`com.cortex.tui`**:在 `CortexModel` 字段加 `WorktreeManager worktreeMgr`、`Path activeCwd`;主 Agent 每次 `run` 前用 `ctx.withCwd(activeCwd)` 注入 ctx
- **`com.cortex.Cortex`**:`new WorktreeManager(root)` 落在 `subagentCatalog = SubagentCatalog.load(root)` 之后;失败降级为 null(可选);把 manager 传给 `CortexModel` 和 `AgentTool` 构造
- **`.gitignore`**:追加 `.cortex/worktrees/` 与 `.cortex/worktree_session.json`

## 核心数据结构

### `com.cortex.worktree.Worktree`

```java
public record Worktree(
        String name,         // 原始 slug(可能含 /)
        Path path,           // 绝对路径
        String branch,       // worktree-<flatSlug>
        String basedOn,      // 创建时的 base 引用(HEAD / SHA)
        String headCommit,   // 创建时的 commit SHA
        Instant created,
        boolean manual       // true=用户手动创建(/worktree create 路径)
) {}
```

### `com.cortex.worktree.WorktreeSession`

```java
public record WorktreeSession(
        @JsonProperty("original_cwd")         String originalCwd,
        @JsonProperty("worktree_path")        String worktreePath,
        @JsonProperty("worktree_name")        String worktreeName,
        @JsonProperty("original_branch")      String originalBranch,
        @JsonProperty("original_head_commit") String originalHeadCommit,
        @JsonProperty("session_id")           String sessionId,
        @JsonProperty("hook_based")           boolean hookBased
) {}
```

### `com.cortex.worktree.WorktreeManager`

```java
public final class WorktreeManager {
    private final Path repoRoot;
    private final Path worktreeDir;
    private final Path sessionFile;
    private final List<String> symlinkDirs;            // 默认 [node_modules, .venv, vendor]
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Worktree> active = new HashMap<>();
    private WorktreeSession currentSession;

    public WorktreeManager(Path repoRoot) throws IOException;

    public Worktree create(String name, String baseRef, boolean manual) throws IOException;
    public WorktreeSession enter(String name) throws IOException;
    public ExitReport exit(String name, ExitAction action, ExitOptions opts) throws IOException;
    public void remove(String name, ExitOptions opts) throws IOException;
    public AutoCleanupReport autoCleanup(String name) throws IOException;
    public List<String> sweepStale(Instant cutoff);

    public List<Worktree> list();
    public Optional<Worktree> get(String name);
    public WorktreeSession currentSession();
}
```

### `com.cortex.worktree` 辅助类型

```java
public enum ExitAction { KEEP, REMOVE }

public record ExitOptions(boolean discardChanges) {}
public record ExitReport(boolean removed, String path, String branch) {}
public record AutoCleanupReport(boolean kept, String path, String branch) {}

public final class WorktreeHasChangesException extends IOException {
    public WorktreeHasChangesException() {
        super("worktree has uncommitted changes or new commits");
    }
}
```

### `com.cortex.tool.`

```java
public record (Optional<Path> cwd /* 其他既有 ctx 字段也合并到这里 */) {
    public static  root() { return new (Optional.empty()); }
    public  withCwd(Path dir) { return new (Optional.of(dir.toAbsolutePath())); }
    public Path resolvePath(String p) {
        if (p == null || p.isEmpty()) {
            return cwd.orElseGet(() -> Path.of("").toAbsolutePath());
        }
        Path raw = Path.of(p);
        if (raw.isAbsolute()) return raw;
        Path base = cwd.orElseGet(() -> Path.of("").toAbsolutePath());
        return base.resolve(raw).normalize();
    }
}
```

### `com.cortex.subagent.Definition` 扩展

```java
public record Definition(
        // ... 既有字段 ...
        String isolation   // "" 或 "worktree"
) {}
```

## 模块设计

### `com.cortex.worktree`(新包)

**职责:** Worktree 完整生命周期管理 + Slug 校验 + 后台清理。
**对外接口:** `WorktreeManager` (含上面所列方法) + `WorktreeSlug.validate(...)` + `WorktreeHasChangesException` 等导出类型。
**依赖:** JDK 标准库 + `ProcessBuilder` 调 git;JSON 用 Jackson(项目已通过 SDK 间接引入)。
**关键内部函数:**
- `WorktreeSlug.validate(name)`
- `WorktreeSlug.flatten(name)` (`/` → `+`)
- `PostCreationSetup.run(repoRoot, wtPath, symlinkDirs)`
- `GitHelper.hasWorktreeChanges(wtPath, baseCommit)` (fail-closed)
- `GitHelper.resolveHeadShaFromFS(wtPath)` (快速恢复)
- `WorktreeInclude.read(repoRoot)`
- `GitHelper.listIgnoredFiles(repoRoot)`
- `GitHelper.gitProcess(workDir, args...)` (统一 env: `GIT_TERMINAL_PROMPT=0`, `GIT_ASKPASS=""`,stdin closed)
- `WorktreeNaming.randomAgentName()` (用于 SubAgent 临时 worktree 名)

**文件:**
- `WorktreeManager.java` — Manager 类型 + 主要方法骨架
- `WorktreeCreate.java` — `create` + 快速恢复 + 创建后设置(可作为 `WorktreeManager` 内部 helper class)
- `WorktreeLifecycle.java` — `enter` / `exit` / `remove` / `autoCleanup`(同上)
- `WorktreeSweep.java` — `sweepStale`
- `WorktreeSlug.java` — `validate` + `flatten`
- `WorktreeSession.java` — record + JSON 持久化辅助
- `SessionStore.java` — 原子读写 JSON
- `GitHelper.java` — `gitProcess` / `hasWorktreeChanges` / `resolveHeadShaFromFS`
- `WorktreeNaming.java` — 随机命名
- `PostCreationSetup.java` — 创建后 A/B/C/D 步骤
- `Worktree.java` / `WorktreeHasChangesException.java` / `ExitAction.java` / `ExitOptions.java` / `ExitReport.java` / `AutoCleanupReport.java`
- `*Test.java` — JUnit 5 单测

### `com.cortex.tool` 改造

**职责:** 增加 ctx cwd 传递机制,改造 6 个工具用 `ctx.resolvePath` / `ProcessBuilder.directory()`。
**对外接口:** ``(`withCwd` / `cwd()` / `resolvePath`)新增;6 个工具 `execute` 行为变更但 schema 不变。
**依赖:** 无新增。

**文件改动:**
- `ReadFileTool.java` / `WriteFileTool.java` / `EditFileTool.java` — `Files.readAllBytes`/`Files.write` 前用 `ctx.resolvePath(args.path())`
- `GlobTool.java` — root 解析改 `ctx.resolvePath`
- `GrepTool.java` — 同 `GlobTool`
- `BashTool.java` — `pb.directory(ctx.resolvePath("").toFile())` (即 cwd 本身,空字符串绝对路径化)

### `com.cortex.subagent` 改造

**职责:** `Definition` 加 `isolation` 字段;`Parser` 解析。
**改动:**
- `Parser.java` — frontmatter Map 增加 `isolation` 字段提取,合法值 `""` / `"worktree"`,其他值 stderr 警告回落空
- `Definition.java` — record 加 `String isolation`(放参数末尾)

### `com.cortex.agent` 改造

**职责:** `AgentTool` 增加 worktree 分支,接受 manager。
**改动:**
- `AgentTool.java`:
  - 字段加 `WorktreeManager wtMgr`
  - 构造器 `new AgentTool(..., WorktreeManager wtMgr)`(签名末尾追加)
  - `execute` 内 `def.isolation().equals("worktree")` 时走 `executeWithWorktree(...)`
- 新增 `AgentWorktreeRunner.java`:
  - `executeWithWorktree(ctx, def, subAgent, subConv, prompt, events)` 实现
  - `buildWorktreeNotice(parentCwd, wtPath)` 文案
  - `randomAgentName()` 委托 `WorktreeNaming.randomAgentName()`

### `com.cortex.command` 新增

**职责:** `/worktree` 一级命令 + 子命令解析。
**改动:**
- `Builtins.java` 增加 `register(new WorktreeCommand())`
- 新增 `WorktreeCommand.java`(`handle` 内自己 split 子命令 + 参数)
- `Ui.java` 加 UI 接口方法 `WorktreeAccessor worktreeAccessor()`(返回一个轻量接口,屏蔽 worktree 包反向依赖)

**UI 接口扩展:**

```java
// command/WorktreeAccessor.java
public interface WorktreeAccessor {
    CreateResult create(String name) throws IOException;
    List<WorktreeSummary> list();
    void enter(String name) throws IOException;
    boolean exit(String action, boolean discard) throws IOException;
    void remove(String name, boolean discard) throws IOException;

    record CreateResult(String path, String branch) {}
}

public record WorktreeSummary(String name, String path, String branch, boolean active, boolean manual) {}

public interface Ui {
    // ... 既有方法 ...
    WorktreeAccessor worktreeAccessor();   // 可返回 null 表示未启用
}
```

### `com.cortex.tui` 改造

**职责:** 持有 manager 引用,把 activeCwd 注入主 Agent ctx。
**改动:**
- `CortexModel.java` 字段加 `WorktreeManager worktreeMgr`、`Path activeCwd`(null 表示 JVM 当前目录)
- 构造器接收 `WorktreeManager worktreeMgr`(`CortexModel.Builder` 加方法)
- 在主 Agent `run` 入口前注入 `ctx = ctx.withCwd(effectiveCwd())`,其中 `effectiveCwd()` 返回 `activeCwd` 或 `Path.of("").toAbsolutePath()`
- 实现 `WorktreeAccessor` 接口的适配器类 `TuiWorktreeAccessor`(内部持 `CortexModel` + `WorktreeManager`)
- 启动时若 manager 的 `currentSession()` 非 null,把 `activeCwd = Path.of(session.worktreePath())`

### `com.cortex.Cortex` 改造

```java
// 紧跟 var subagentCatalog = SubagentCatalog.load(root); 之后
WorktreeManager worktreeMgr;
try {
    worktreeMgr = new WorktreeManager(root);
    Thread.startVirtualThread(() -> worktreeMgr.sweepStale(Instant.now().minus(24, ChronoUnit.HOURS)));
} catch (IOException werr) {
    System.err.println("Worktree 管理器降级: " + werr.getMessage());
    worktreeMgr = null;      // 后续 AgentTool / TUI 容忍 null
}

var agentTool = new AgentTool(subagentCatalog, taskMgr, null, cfg.effectiveEnableSubAgentBackground(), worktreeMgr);

var tui = new CortexModel(
        // ... 既有字段 ...
        .worktreeMgr(worktreeMgr)
        .build();
```

## 模块交互

**SubAgent + Worktree 启动链路:**

```
主 Agent 调 Agent 工具
  ↓
AgentTool#execute
  ↓
def.isolation().equals("worktree")?
  ↓ yes
executeWithWorktree:
  1. name = "agent-a" + randomHex(7)
  2. wt = worktreeMgr.create(name, "HEAD", false)
  3. notice = buildWorktreeNotice(parentCwd, wt.path())
  4. taskText = notice + "\n\n" + prompt
  5. ctx = ctx.withCwd(wt.path())
  6. finalText = subAgent.runToCompletion(ctx, subConv, taskText, events)
  7. report = worktreeMgr.autoCleanup(name)
  8. if report.kept(): finalText += "\n[Worktree 保留: " + report.path() + "]"
  9. return finalText
```

**工具调用的 cwd 解析链路:**

```
模型调 read_file(path="server.py")
  ↓
agent.execute → // 工具执行走 StreamingExecutor.executeAll(ctx, "ReadFile", args)
  ↓
ReadFileTool#execute(ctx, args)
  ↓
abs = ctx.resolvePath("server.py")
  ↓
ctx 有 cwd → abs = cwd.resolve("server.py").normalize()
ctx 无 cwd → abs = Path.of("").toAbsolutePath().resolve("server.py").normalize()
  ↓
Files.readAllBytes(abs)
```

**TUI 主 Agent Run 入口:**

```
CortexModel.runOnce(ctx):
  if (activeCwd != null) {
      ctx = ctx.withCwd(activeCwd);
  }
  events = agent.run(ctx, conv, mode);
```

## 文件组织

```
src/main/java/com/cortex/worktree/   — 新包
├── WorktreeManager.java              — Manager 类型 + 构造
├── WorktreeCreate.java               — create + 快速恢复 + post-creation setup
├── WorktreeLifecycle.java            — enter / exit / remove / autoCleanup
├── WorktreeSweep.java                — sweepStale
├── WorktreeSlug.java                 — validate + flatten
├── WorktreeSession.java              — record + Jackson tag
├── SessionStore.java                 — JSON 原子读写
├── GitHelper.java                    — gitProcess / hasWorktreeChanges / resolveHeadShaFromFS
├── WorktreeNaming.java               — randomAgentName / ephemeral 正则
├── PostCreationSetup.java            — A/B/C/D 子步骤
├── Worktree.java
├── ExitAction.java / ExitOptions.java / ExitReport.java / AutoCleanupReport.java
└── WorktreeHasChangesException.java

src/test/java/dev/cortex/worktree/
├── WorktreeSlugTest.java
├── WorktreeManagerTest.java
├── WorktreeCreateTest.java
├── WorktreeLifecycleTest.java
├── WorktreeSweepTest.java
└── GitHelperTest.java

src/main/java/com/cortex/tool/
├── BashTool.java                     — 改造:pb.directory(ctx.resolvePath("").toFile())
├── ReadFileTool.java                 — 改造:用 ctx.resolvePath
├── WriteFileTool.java                — 改造:用 ctx.resolvePath
├── EditFileTool.java                 — 改造:用 ctx.resolvePath
├── GlobTool.java                     — 改造:用 ctx.resolvePath
└── GrepTool.java                     — 改造:用 ctx.resolvePath

src/test/java/dev/cortex/tool/
└── Test.java

src/main/java/com/cortex/subagent/
├── Definition.java                   — 加 isolation 字段
└── Parser.java                       — 解析 isolation:

src/test/java/dev/cortex/subagent/
└── ParserTest.java                   — 增加 isolation 用例

src/main/java/com/cortex/agent/
├── AgentTool.java                    — execute 加 isolation 分支
└── AgentWorktreeRunner.java          — 新增:executeWithWorktree + notice

src/test/java/dev/cortex/agent/
└── AgentWorktreeRunnerTest.java

src/main/java/com/cortex/command/
├── WorktreeCommand.java              — 新增:/worktree handler
├── Builtins.java                     — 增加 register
├── Ui.java                           — 加 worktreeAccessor()
└── WorktreeAccessor.java             — 接口 + WorktreeSummary

src/main/java/com/cortex/tui/
├── CortexModel.java                       — 加 worktreeMgr / activeCwd / cwd 注入
└── TuiWorktreeAccessor.java          — 实现 WorktreeAccessor(适配 WorktreeManager)

src/main/java/com/cortex/Cortex.java   — 接入

.gitignore                            — 追加两行
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| cwd 传递方式 | `` record 携带 `Optional<Path>` | JVM 没有 per-thread cwd,显式上下文最干净;Tool 接口签名不变,prompt cache 不抖动 |
| Worktree 目录位置 | `.cortex/worktrees/<flatSlug>/` | README 既定方案;仓库内 + .gitignore 不追踪 |
| 嵌套 slug `/` 处理 | 替换为 `+`(flatten)做文件系统/分支名 | Git 分支的 `/` 是命名空间分隔符,会导致 `worktree-team/alice` 与 `worktree-team` 的 D/F 冲突 |
| Manager 构造失败处理 | 抛 `IOException`,Main 降级 `worktreeMgr=null` | 不阻塞 cortex 启动;后续 isolation:worktree 调用回错误信息 |
| 快速恢复 | 纯 fs 读,不调 git | README 说明大仓库 git fetch 6-8s,fs read 3ms;场景:同一 SubAgent 反复进同 worktree |
| 创建后设置失败处理 | 仅 stderr 警告 | 都是 best-effort,失败 ≠ 不可用 |
| `-B` vs `-b` | `-B`(重置) | 上次残留的孤儿分支不会让 create 失败 |
| `Thread.sleep(100)` 在 remove | 保留 | README 指出 git lockfile 竞态;100ms 是经验值 |
| 进程 cwd 处理 | 不使用进程级 chdir,全部 explicit cwd | JVM 标准 API 不支持 chdir;`ProcessBuilder.directory()` 已能解决子进程 cwd,避免进程级 cwd 成为同步点 |
| 后台清理触发时机 | cortex 启动时跑一次,虚拟线程异步 | 不阻塞主流程;ch11 已有 `SessionStore.cleanExpired` 同样做法 |
| `.worktreeinclude` 缺失行为 | 跳过 D 步骤,不报错 | 大多数项目没这文件 |
| `subagent.isolation` 默认值 | `""`(无隔离) | 不破坏 ch13 既有定义文件 |
| 临时 worktree 命名 | `agent-a<7hex>` | README 既定;`sweepStale` 正则匹配 |
| Manager 用 `ReentrantLock` 而非 `ReadWriteLock` | 操作粒度大、争用低,简单互斥足够 | 避免读写锁的额外复杂度 |
| `WorktreeAccessor` 接口在 command 包 | 隔离 worktree 包反向依赖 | command 包不应直接导入 worktree;TUI 提供适配器即可 |
| TUI `activeCwd` 字段 | `Path` 字段,null = JVM 当前目录 | 简单直接,与既有 `cwd` 字段并存避免改 schema |
| `--resume` 与 worktree session | `WorktreeManager` 构造内统一处理 | 启动时自动读 session,session 失效自动清空 |
| Linux/macOS 跨平台 | symlink 用 `Files.createSymbolicLink` | POSIX 平台一致;Windows 失败时 best-effort 跳过 |
| JSON 序列化 | Jackson(已被 anthropic-java / openai-java 间接引入) | 不新增依赖;`@JsonProperty` 完成小写下划线绑定 |