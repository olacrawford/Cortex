# Agent Team Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/cortex/team/package-info.java` | 包文档 |
| 新建 | `src/main/java/com/cortex/team/BackendType.java` | 枚举 + wireValue |
| 新建 | `src/main/java/com/cortex/team/TeammateInfo.java` | record |
| 新建 | `src/main/java/com/cortex/team/Team.java` | Team 类(含 addMember/setMemberActive/removeMember) |
| 新建 | `src/main/java/com/cortex/team/Persistence.java` | sanitize、原子写、readJson、reloadFromDiskLocked |
| 新建 | `src/main/java/com/cortex/team/TeamManager.java` | create/get/list/delete |
| 新建 | `src/main/java/com/cortex/teams/*.java` | 5 个异常类 |
| 新建 | `src/test/java/dev/cortex/team/TeamManagerTest.java` | Manager 单测 |
| 新建 | `src/main/java/com/cortex/team/SpawnTeammate.java` | SpawnTeammate 主流程 |
| 新建 | `src/test/java/dev/cortex/team/SpawnTeammateTest.java` | spawn 单测(in-process 路径) |
| 新建 | `src/main/java/com/cortex/team/Feature.java` | FORK_TEAMMATE flag 读取 |
| 新建 | `src/main/java/com/cortex/teams/Message.java` | record + MessageType 枚举 |
| 新建 | `src/main/java/com/cortex/teams/Mailbox.java` | write/read/readUnread/markRead |
| 新建 | `src/main/java/com/cortex/teams/ReadUnreadResult.java` | record |
| 新建 | `src/main/java/com/cortex/teams/MailboxLock.java` | mailbox 内部文件锁(T5 落地;T9 抽到 filelock 后委托或删除) |
| 新建 | `src/test/java/dev/cortex/teams/MailboxTest.java` | 并发与 stale 锁测试 |
| 新建 | `src/main/java/com/cortex/teams/FileLock.java` | 共用文件锁(T9 从 mailbox 抽出;O_EXCL + 抖动 + stale) |
| 新建 | `src/test/java/dev/cortex/teams/FileLockTest.java` | 锁单测 |
| 新建 | `src/main/java/com/cortex/teams/AgentNameRegistry.java` | 命名注册表 |
| 新建 | `src/test/java/dev/cortex/teams/AgentNameRegistryTest.java` | 注册/解析/反查测试 |
| 新建 | `src/main/java/com/cortex/teams/Status.java` | 枚举 |
| 新建 | `src/main/java/com/cortex/teams/Task.java` | record |
| 新建 | `src/main/java/com/cortex/teams/Filter.java` | record |
| 新建 | `src/main/java/com/cortex/teams/Patch.java` | record |
| 新建 | `src/main/java/com/cortex/teams/Store.java` | CRUD + 依赖图 |
| 新建 | `src/test/java/dev/cortex/teams/StoreTest.java` | CRUD + 依赖关系测试 |
| 新建 | `src/main/java/com/cortex/teams/Backend.java` | 接口 + SpawnRequest / SpawnResult |
| 新建 | `src/main/java/com/cortex/teams/BackendFactory.java` | 按类型分发 |
| 新建 | `src/main/java/com/cortex/teams/BackendDetector.java` | Backend.detect() |
| 新建 | `src/test/java/dev/cortex/teams/BackendDetectorTest.java` | 检测逻辑测试 |
| 新建 | `src/main/java/com/cortex/teams/tmux/TmuxBackend.java` | Tmux Backend |
| 新建 | `src/test/java/dev/cortex/teams/tmux/TmuxBackendTest.java` | tmux 命令构造测试 |
| 新建 | `src/main/java/com/cortex/teams/iterm2/Iterm2Backend.java` | iTerm2 Backend |
| 新建 | `src/test/java/dev/cortex/teams/iterm2/Iterm2BackendTest.java` | iterm2 命令构造测试 |
| 新建 | `src/main/java/com/cortex/teams/inprocess/InProcessBackend.java` | InProcess Backend |
| 新建 | `src/test/java/dev/cortex/teams/inprocess/InProcessBackendTest.java` | in-process spawn 集成测试 |
| 新建 | `src/main/java/com/cortex/teams/TeamCreateTool.java` | TeamCreate 工具 |
| 新建 | `src/main/java/com/cortex/teams/TeamDeleteTool.java` | TeamDelete 工具 |
| 新建 | `src/main/java/com/cortex/teams/TaskCreateTool.java` | TaskCreate 工具 |
| 新建 | `src/main/java/com/cortex/teams/TaskGetTool.java` | TaskGet 工具 |
| 新建 | `src/main/java/com/cortex/teams/TaskListTool.java` | TaskList 工具 |
| 新建 | `src/main/java/com/cortex/teams/TaskUpdateTool.java` | TaskUpdate 工具 |
| 新建 | `src/main/java/com/cortex/teams/SendMessageTool.java` | SendMessage 工具 |
| 新建 | `src/main/java/com/cortex/teams/TeammateToolFilter.java` | 队员专属工具白名单 |
| 新建 | `src/test/java/dev/cortex/teams/ToolsTest.java` | 工具单测 |
| 新建 | `src/main/java/com/cortex/coordinator/Coordinator.java` | isEnabled/allowedTools/systemPromptSuffix |
| 新建 | `src/test/java/dev/cortex/coordinator/CoordinatorTest.java` | 双锁测试 |
| 新建 | `src/main/java/com/cortex/agent/TeamHook.java` | 接口 + TeammateContext 嵌套 record |
| 新建 | `src/main/java/com/cortex/agent/TeammateContext.java` | 闭包式 Mailbox + agentId |
| 修改 | `src/main/java/com/cortex/agent/AgentTool.java` | 增加 teamName 参数 + TeamHook 委托 + withTeamHook 构造器 |
| 新建 | `src/main/java/com/cortex/agent/TeamMailboxIngestor.java` | Loop 头部注入 incoming-messages reminder |
| 修改 | `src/main/java/com/cortex/task/TaskManager.java` | 加 onTaskDone 回调;改用 AgentNameRegistry |
| 修改 | `src/main/java/com/cortex/tool/Filter.java` | 新增 TEAMMATE_ALLOWED_TOOLS,扩展 FilterParams 加 teammate boolean |
| 新建 | `src/main/java/com/cortex/command/builtin/BuiltinTeam.java` | /team list/info/delete/kill 4 个命令 |
| 修改 | `src/main/java/com/cortex/tui/CortexModel.java` 与相关文件 | 接收 teamManager、coordinator 标签 |
| 修改 | `src/main/java/com/cortex/config/AppConfig.java` | FeaturesConfig 字段新增 coordinatorMode 与 forkTeammate |
| 修改 | `src/main/java/com/cortex/Cortex.java` | wire TeamManager / Coordinator,注册 7 个工具 |
| 新建 | `src/main/java/com/cortex/cli/TeamMemberRunner.java` | --team-member 子进程自治循环 |
| 修改 | `.cortex/config.yaml` 示例 | 加示例 features 段(可选,不强制) |
| 修改 | `build.gradle.kts` | 加 `info.picocli:picocli` 依赖(Pane 子进程 CLI 解析) |

## T1: 基础类型 — `dev/cortex/team/BackendType.java` + `TeammateInfo.java`

**文件:** `src/main/java/com/cortex/team/BackendType.java`、`TeammateInfo.java`、`exceptions/*.java`
**依赖:** 无
**步骤:**
1. 定义 `BackendType` 枚举:`TMUX("tmux")`、`ITERM2("iterm2")`、`IN_PROCESS("in-process")`;带 `wireValue()` + `@JsonCreator` + `@JsonValue`
2. 定义 `TeammateInfo` record(F2),所有字段带 `@JsonProperty`
3. 定义 5 个异常:`TeamNotFoundException` / `TeamHasActiveMembersException` / `MemberExistsException` / `MemberNotFoundException` / `InProcessTeammateNoSpawnException`(全部继承 `RuntimeException` 或自定义 `TeamException` 基类)

**验证:** `./gradlew -q -DskipTests compile` 编译通过

## T2: sanitize 与原子写 — `dev/cortex/team/Persistence.java`

**文件:** `src/main/java/com/cortex/team/Persistence.java`
**依赖:** T1
**步骤:**
1. 实现 `static String sanitize(String name)`——只保留 `[a-zA-Z0-9._-]`,其他字符替换为 `-`,首尾去 `-`,空字符串返回 `""`(用 `Pattern.compile("[^a-zA-Z0-9._-]+").matcher(name).replaceAll("-")`)
2. 实现 `static void atomicWriteJson(Path path, Object v)`——`ObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(v)` → 写 `<path>.tmp` → `Files.move(tmp, path, ATOMIC_MOVE, REPLACE_EXISTING)`
3. 实现 `static <T> Optional<T> readJson(Path path, Class<T> type)`——`Files.readAllBytes` + `mapper.readValue`,文件不存在返回 `Optional.empty()`

**验证:** 单测断言 `Persistence.sanitize("foo bar/baz").equals("foo-bar-baz")`

## T3: TeamManager 与持久化 — `dev/cortex/team/TeamManager.java`

**文件:** `src/main/java/com/cortex/team/TeamManager.java`
**依赖:** T1, T2
**步骤:**
1. 定义 `TeamManager` 类(F3)
2. 实现构造器 `new TeamManager(Path homeDir, Path projectRoot, WorktreeManager wt, TaskManager taskMgr, AgentNameRegistry reg)`(F4)
   - 创建 `<homeDir>/.cortex/teams/` 目录(`Files.createDirectories`)
   - `Files.list` 扫描子目录,逐个 `Persistence.readJson(configPath, TeamSnapshot.class)`(失败 `System.err` 警告并跳过)
   - 反序列化后填充派生路径字段
3. 实现 `Optional<Team> get(String name)`
4. 实现 `List<Team> list()`(按 `createdAt` 排序)
5. 实现 `Team create(String name, String description)`(F5)
   - sanitize + 同名冲突 `-2`/`-3` 后缀
   - 取 `BackendDetector.detect()`(暂时硬编码 `IN_PROCESS`,后面 T12 接 detect)
   - 创建 configDir、mailboxDir
   - 注册 Lead 成员
   - `Persistence.atomicWriteJson`
6. 实现 `void delete(String name, boolean force)`(F7 + F66):
   - 持锁、找到 Team、(force=false 时)校验全员 isActive=false
   - 对每个非 lead 成员:用 `BackendFactory.create(mem.backendType(), deps)` 解析后端,调 `backend.kill(mem.paneId(), mem.agentId())`
   - 调 `cleanupMemberResources` 删 session 目录与 worktree(best-effort)
   - 递归删 `team.configDir()` 整个 Team 目录
   - 从 in-memory map 移除
   - 没注入 BackendFactory 的测试场景跳过 kill,fallback 只清磁盘资源

**验证:** 写单测覆盖 create/get/delete 基本流程;`./gradlew test -Dtest=TeamManagerTest`;tmux 实跑后 `/team delete --force` 看 pane 真的被杀(`tmux list-panes` 只剩 Lead)

## T3b: Team.Manager 跨进程并发兜底 — 继续 `TeamManager.java` + `Persistence.java`

**文件:** `Team.java` / `Persistence.java`
**依赖:** T3
**步骤:**
1. `Persistence` 增 `static void reloadFromDiskLocked(Team t)`——调用方持锁;从 `t.configPath()` `readJson`,把 `members` 字段覆盖到 in-memory(失败静默回退到内存现状)
2. `Team.addMember` 与 `Team.setMemberActive`(以及任何会修改 members 后 save 的方法)在持锁后**先**调 `Persistence.reloadFromDiskLocked(this)` 再操作内存 + save
3. 不是为了多线程并发——in-process 早就有 `lock` 保护;**是为了跨进程**:Pane 后端的 Lead 与子进程是两个独立进程,各持一份内存中的 Team。如果不 reload,会出现「子进程读 config 时 Lead 的 addMember 还没写入,子进程修改自己内存 Team 没看见自己,setMemberActive 静默 no-op」的丢更新

**验证:** 单测构造时序:t1 = readJson 得到无 alice 的 Team A;t2 在 disk 上写带 alice 的 Team B;t3 调 `team.setMemberActive("alice", false)` 应该成功(走 reload 路径)而非静默 no-op

## T4: Team 成员操作 — 继续 `Team.java`

**文件:** `Team.java`
**依赖:** T3, T3b
**步骤:**
1. 实现 `boolean addMember(TeammateInfo info)`(F8)——持锁后**先 reloadFromDiskLocked**(见 T3b),检查重名;加入 members;持久化
2. 实现 `boolean setMemberActive(String name, boolean active)`(F9)——持锁后**先 reloadFromDiskLocked**;遍历 members 找到 name 改 isActive 字段;持久化
3. 实现 `boolean removeMember(String name)`(F10)
4. 实现 `Optional<TeammateInfo> memberByName(String name)` / `memberByAgentId(String id)` 工具方法

**验证:** 单测覆盖 add → setActive → remove 三步流程,读回 config.json 校验字段

## T5: mailbox 文件锁 — `dev/cortex/teams/MailboxLock.java`

**文件:** `src/main/java/com/cortex/teams/MailboxLock.java`
**依赖:** 无
**步骤:**
1. 定义 `package com.cortex.teams.mailbox;`
2. 实现 `static AutoCloseable acquire(Path lockPath) throws IOException`——
   - 循环 10 次:`Files.newOutputStream(lockPath, CREATE_NEW, WRITE).close()` 抢锁
   - 失败时 `Files.getLastModifiedTime(lockPath)`,若 `Instant.now().minus(...).toEpochMilli() - mtime > 10000` 则 `Files.deleteIfExists(lockPath)` 后立即重试一次
   - 失败时 `Thread.sleep(ThreadLocalRandom.current().nextLong(5, 101))` 后继续
   - 返回 `AutoCloseable` 闭包:`Files.deleteIfExists(lockPath)`(配合 try-with-resources)
3. 内部常量 `LOCK_MAX_RETRIES = 10` / `LOCK_STALE_AFTER = Duration.ofSeconds(10)` / `LOCK_BACKOFF_MIN_MS = 5` / `LOCK_BACKOFF_MAX_MS = 100`

**验证:** 单测 `testAcquireLockSerial`(两次抢锁,中间 close)、`testAcquireLockStale`(故意创建 11 秒前的锁,断言能拿到)

## T6: mailbox Message 与 Mailbox — `dev/cortex/teams/Mailbox.java`

**文件:** `src/main/java/com/cortex/teams/Mailbox.java`、`Message.java`、`MessageType.java`、`ReadUnreadResult.java`
**依赖:** T5
**步骤:**
1. 定义 `MessageType` 枚举 + 4 个常量(F32)
2. 定义 `Message` record(F32),字段带 `@JsonProperty`
3. 定义 `ReadUnreadResult` record:`(List<Integer> indices, List<Message> messages)`
4. 定义 `Mailbox` 类,字段 `Path dir`
5. 实现构造器 `new Mailbox(Path dir)`——`Files.createDirectories(dir)`
6. 实现 `void write(String agentId, Message msg)`(F33)
   - lockPath = `dir.resolve(agentId + ".lock")`
   - try-with-resources `MailboxLock.acquire(lockPath)`(T9 抽到 filelock 后,这里改为 `FileLock.acquire(...)`)
   - 读 `dir.resolve(agentId + ".json")`(不存在视为 `{"messages":[]}`)
   - 追加 msg(若 `timestamp==0` 设为 `Instant.now().getEpochSecond()`)
   - atomic write(用 `Persistence.atomicWriteJson`)
7. 实现 `List<Message> read(String agentId)`
8. 实现 `ReadUnreadResult readUnread(String agentId)`——返回 unread 消息的 indices 与消息本身
9. 实现 `void markRead(String agentId, List<Integer> indices)`——按 indices 把对应消息 `read=true`

**验证:** 单测覆盖 write/read/markRead;并发测试 10 个 virtual thread 写同一 agentId,断言读回 10 条无丢失

## T7: AgentNameRegistry — `dev/cortex/teams/AgentNameRegistry.java`

**文件:** `src/main/java/com/cortex/teams/AgentNameRegistry.java`
**依赖:** 无
**步骤:**
1. 定义 `AgentNameRegistry` 类
2. 实现 `void register(String name, String agentId)`——若 name 已存在覆盖(取出旧 agentId,从 byId 删旧映射);若 agentId 已有其他 name,先反向 unregister
3. 实现 `void unregister(String name)`
4. 实现 `void unregisterByAgentId(String agentId)`
5. 实现 `Optional<String> resolve(String nameOrId)`——先按 name 查,再按 agentId 反向查
6. 实现 `Optional<String> nameOf(String agentId)`
7. 实现 `Map<String,String> snapshot()`

**验证:** 单测覆盖 register/unregister/resolve/nameOf;包括「同名覆盖」和「不同名指向同一 agentId」边界

## T8: tasks Store — `dev/cortex/teams/Store.java`

**文件:** `src/main/java/com/cortex/teams/Store.java`、`Status.java`、`Task.java`、`Filter.java`、`Patch.java`
**依赖:** T5(用 mailbox lock)
**步骤:**
1. 定义 `Status` 枚举 + 4 常量
2. 定义 `Task`、`Filter`、`Patch` record(F30)
3. 定义 `Store` 类,字段 `Path path`、`ReentrantLock lock`
4. 实现构造器 `new Store(Path path)`
5. 实现 `String create(Task t)`——生成 `task_<6位 hex>` ID(`String.format("task_%06x", ThreadLocalRandom.current().nextInt(0x1000000))`);read-modify-write `tasks.json`(用 lock 文件,路径 `path.resolveSibling(path.getFileName() + ".lock")`,复用 `MailboxLock.acquire`——下一步 T9 会把这部分抽到独立 `com.cortex.teams.filelock` 包,mailbox 与 tasks 共用)
6. 实现 `Optional<Task> get(String id)`
7. 实现 `List<Task> list(Filter f)`——按 `status` 过滤,返回时附加 `isReady` 字段(检查 blockedBy 中所有任务是否 completed);为简化可在 list 输出时计算 ready 标记,不存盘
8. 实现 `void update(String id, Patch p)`——支持 title/description/status/assignee/addBlocks/addBlockedBy/removeBlocks/removeBlockedBy 字段
9. `addBlockedBy=[X]` 同时给 X 任务 `blocks` 加上当前任务 id(双向维护)

**注意:** isReady 在 list 输出时通过 `TaskView` record 封装(`record TaskView(Task task, boolean isReady)`),不污染 disk 上的 Task。为减小循环依赖,把锁实现提到独立 `com.cortex.teams.filelock` 包(见 T9),mailbox 与 tasks 共用。

**验证:** 单测覆盖 create/get/update;特别测 addBlockedBy 的双向更新

## T9: 共用 filelock 包(从 mailbox 抽出)

**文件:** `src/main/java/com/cortex/teams/FileLock.java`(把 T5 实现迁过来)
**依赖:** 无
**步骤:**
1. 把 T5 的 `MailboxLock.acquire` 实现迁到 `package com.cortex.teams.filelock;`,类名改为 `FileLock`,方法签名保持 `static AutoCloseable acquire(Path lockPath) throws IOException`
2. 在 `dev/cortex/teams/MailboxLock.java` 改为 `import com.cortex.teams.filelock.FileLock;` 后委托给 `FileLock.acquire(...)`,或直接删 `MailboxLock` 类、把 mailbox 内调用改为 `FileLock.acquire(...)`
3. 在 `dev/cortex/teams/Store.java` 也 `import com.cortex.teams.filelock.FileLock;`,T8 中的 `MailboxLock.acquire(...)` 改为 `FileLock.acquire(...)`

**验证:** `./gradlew -q -DskipTests -pl . test-compile` 通过;`./gradlew -q test -Dtest='com.cortex.teams.**'` 全过(覆盖 mailbox 与 tasks 包)

## T10: backend 接口 — `dev/cortex/teams/Backend.java`

**文件:** `src/main/java/com/cortex/teams/Backend.java`、`SpawnRequest.java`、`SpawnResult.java`、`BackendFactory.java`
**依赖:** T1
**步骤:**
1. 定义 `Backend` 接口(F12)
2. 定义 `SpawnRequest` record(F13)——`subAgent` / `conv` / `taskManager` 字段类型为 `Object`,避免 backend 反向依赖 agent 包
3. 定义 `SpawnResult` record `(String paneId, String agentId)`
4. 定义 `BackendFactory.create(BackendType t, Deps deps)` 工厂——按类型分发(暂时只占位,具体实现在 T12-T14)

**验证:** `./gradlew -q -DskipTests compile` 通过

## T11: BackendDetector — `dev/cortex/teams/BackendDetector.java`

**文件:** `src/main/java/com/cortex/teams/BackendDetector.java`
**依赖:** T10
**步骤:**
1. 实现 `static BackendType detect()`(F14):
   - `System.getenv("TMUX") != null` → TMUX
   - `"iTerm.app".equals(System.getenv("TERM_PROGRAM"))` 且 `findOnPath("it2").isPresent()` → ITERM2
   - `findOnPath("tmux").isPresent()` → TMUX
   - 否则 IN_PROCESS
2. 内部 `findOnPath(String binary)` 用 `System.getenv("PATH").split(File.pathSeparator)` 遍历检查

**验证:** 写 test 用 `Mockito.mockStatic` 或自己注入 `EnvProvider` 接口控制环境变量,断言不同组合的返回值

## T12: tmux backend — `dev/cortex/teams/tmux/TmuxBackend.java`

**文件:** `src/main/java/com/cortex/teams/tmux/TmuxBackend.java`
**依赖:** T10
**步骤:**
1. 定义 `TmuxBackend` 类实现 `Backend`
2. 实现 `BackendType type()` 返回 `BackendType.TMUX`
3. 实现 `SpawnResult spawn(SpawnRequest req)`(F15):
   - 在 `$TMUX` 内:`tmux split-window -h -P -F "#{pane_id}" -- <cmd>`
   - 在 `$TMUX` 外但 `tmux` 二进制可用:`tmux new-session -d`(detached 新会话)走外部 session(F16)
   - `cmd` 构造:`cortex --team-member --team <teamName> --member <memberName> --agent-id <agentId> --session-dir <sessionDir> --worktree <wtPath> [--agent-type <type>] [--model <model>] [--plan-mode]`
   - `--agent-id` 必须传——子进程不需要读 Lead 还没写完的 `config.json` 找自己
   - `initialPrompt` **不**走命令行,由 `SpawnTeammate`(T18)在 backend.spawn 之前预写入 alice mailbox
   - 用 `new ProcessBuilder(...).start()` 跑 tmux,捕获 stdout 作为 paneId
4. 实现 `void wake(String paneId, String agentId)`:`tmux send-keys -t <paneId> "" Enter`(子进程 stdin scanner 读到回车,立刻去 mailbox 轮询)
5. 实现 `void kill(String paneId, String agentId)`:`tmux kill-pane -t <paneId>`,忽略 pane not found 错误

**注意:** spawn 启动的 cortex CLI 需要支持 `--team-member` flag;这部分留给 T21(Cortex.java 改造)与 T29(TeamMemberRunner)

**验证:** 单测断言命令字符串构造正确(注入一个 `Function<List<String>, ProcessOutput>` 命令执行器 fake,断言 args list);集成测试在 CI 跳过(需要 tmux)

## T13: iterm2 backend — `dev/cortex/teams/iterm2/Iterm2Backend.java`

**文件:** `src/main/java/com/cortex/teams/iterm2/Iterm2Backend.java`
**依赖:** T10
**步骤:**
1. 实现 `spawn`:`it2 split --new-pane --command "<cmd>"`(实际 it2 CLI 命令以官方为准;先按 README 描述实现,实测可能要调);`<cmd>` 同 T12 格式,含 `--agent-id`,`initialPrompt` 走 mailbox 预写
2. 实现 `wake`:`it2 send-text --pane <paneId> ""`
3. 实现 `kill`:`it2 close-pane --pane <paneId>`

**注意:** iterm2 后端无法在 CI 中实跑,实现以构造正确的命令字符串为准

**验证:** 单测断言命令构造正确

## T14: in-process backend — `dev/cortex/teams/inprocess/InProcessBackend.java`

**文件:** `src/main/java/com/cortex/teams/inprocess/InProcessBackend.java`
**依赖:** T10,需要 `agent`、`task`、`conversation` 包
**步骤:**
1. 定义 `InProcessBackend` 类,字段 `TaskManager taskManager`
2. 实现 `spawn(SpawnRequest req)`(F18):
   - 从 `req.subAgent()` / `req.conv()` 取已构造好的对象((Agent) 强转)
   - 调 `taskManager.launch(subAgent, conv, req.memberName(), req.initialPrompt())` 起 virtual thread,返回 taskId(即 agentId)
   - 返回 `new SpawnResult("", agentId)`(paneId 为空)
3. 实现 `wake`:no-op,返回 void
4. 实现 `kill`:`taskManager.stop(agentId)`

**Backend 接口签名统一为**(回 T10 调整):
```java
public interface Backend {
    BackendType type();
    SpawnResult spawn(SpawnRequest req) throws IOException;
    void wake(String paneId, String agentId) throws IOException;
    void kill(String paneId, String agentId) throws IOException;
}

public record SpawnResult(String paneId, String agentId) {}
```
Pane 后端用 paneId,in-process 用 agentId;接口统一传两者,各自取需要的。

**验证:** 单测:构造 fake TaskManager,spawn 一个 noop 子 Agent,断言 virtual thread 启动

## T15: feature flag — `dev/cortex/team/Feature.java`

**文件:** `src/main/java/com/cortex/team/Feature.java`
**依赖:** 无
**步骤:**
1. 实现 `static boolean forkTeammateEnabled(AppConfig cfg)`——读 `cfg.features().forkTeammate()`
2. 实现 `static boolean has(String key, AppConfig cfg)`——按 key 名查 features

**验证:** 单测覆盖 true/false 两种 cfg

## T16: TeammateContext — `dev/cortex/agent/TeamHook.java` + `TeammateContext.java`

**文件:** `src/main/java/com/cortex/agent/TeamHook.java`、`TeammateContext.java`
**依赖:** 无
**步骤:**
1. 定义 `TeamHook` 接口(plan.md 已给签名)
2. 定义 `TeamSpawnRequest` record(把 Agent 工具参数传过去)
3. 定义 `TeammateContext` 类——`teamName`、`memberName`、`agentId`、`mailboxDir`、`Function<String, ReadUnreadResult> readUnread` 闭包等
4. 提供 `InvocationContext.withTeammateContext(tc)` + `static Optional<TeammateContext> teammateContextFrom(InvocationContext ctx)`(用 ThreadLocal 或 invocation ctx 的 attribute map)

**验证:** `./gradlew -q -DskipTests compile` 通过

## T17: 队员专属工具白名单 — `dev/cortex/tool/Filter.java` 扩展

**文件:** `src/main/java/com/cortex/tool/Filter.java`(修改)
**依赖:** 无
**步骤:**
1. 新增常量:
   ```java
   public static final List<String> TEAMMATE_EXTRA_TOOLS = List.of(
       "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage"
   );
   ```
2. 扩展 `FilterParams` record 加 `boolean teammate` 字段
3. 在 `applyAgentToolFilter` 中:若 `teammate=true`,把 `TEAMMATE_EXTRA_TOOLS` 加到允许集合(在 disallowed 删除之前);非 teammate 时排除这些工具(主 Agent 看不到)
4. 同时增加常量 `TEAM_LEAD_DISALLOWED_TEAMMATE_TOOLS`——避免主 Agent 直接看到 TaskCreate 等(应该走 teammate=true 才能加上)

**简化策略:** TEAMMATE_EXTRA_TOOLS 不进默认 registry(由 Main 注册到 registry,但默认从 ALL 过滤集移除);teammate=true 时把它们加回。

**采用:**
- Main 把 5 个协作工具注册到 registry
- 修改默认 filter:`ALL_AGENT_DISALLOWED_TOOLS` 加上这 5 个工具(子 Agent 默认看不到)
- 新增 `TEAMMATE_ALLOWED_TOOLS = ALL_AGENT_DISALLOWED_TOOLS 中的协作工具`
- 修改 `applyAgentToolFilter`:`teammate=true` 时,这 5 个工具不被 ALL 过滤

**验证:** 单测覆盖 teammate=true / false,断言 TaskCreate 等可见性

## T18: SpawnTeammate 主流程 — `dev/cortex/team/SpawnTeammate.java`

**文件:** `src/main/java/com/cortex/team/SpawnTeammate.java`
**依赖:** T1-T17
**步骤:**
1. 定义 `TeamManager.spawnTeammate(TeamSpawnRequest req)`(也可拆到 `SpawnTeammate.java`,在 TeamManager 内委托)
2. 实现 plan.md 中描述的步骤流程:
   - 取 Team
   - 校验调用者权限(看 ctx 是否有 TeammateContext,且 backendType=IN_PROCESS 时拒绝)
   - 解析 `SubAgentDefinition`
   - `worktreeManager.create(".cortex/worktrees/team-<sanitized>+<member>", "HEAD", false)`
   - 申请 sessionDir(本期复用 ch12 格式,自己生成新 id)
   - 预生成 agentId(`String.format("agent-%014x", ThreadLocalRandom.current().nextLong())`),构造 SpawnRequest 含 agentId 字段
   - 计算 allowed = `Filter.applyAgentToolFilter(new FilterParams(true, ...))`、systemPrompt = `def.systemPrompt() + teamSystemPromptSuffix()`
   - 若 backendType=IN_PROCESS:构造 subAgent(**强制 `dontAsk=true`** F39a)+ subConv,注入 `<team-context>` reminder + `setCtxDecorator` 装 `TeammateContext{mailbox: mc}`
   - 若 backendType=TMUX/ITERM2:`new Mailbox(t.mailboxDir()).write(agentId, new Message("lead", agentId, TEXT, summary, req.prompt(), null, 0, false))` 预写初始任务(F13)
   - `backend.spawn(req)` 取 paneId
   - `registry.register(memberName, agentId)`
   - `team.addMember(...)`(调用时 `reloadFromDiskLocked` 保护跨进程并发)
   - 返回 JSON `{memberName, agentId, worktree, backend, paneId}`
3. 提供 helper `buildTeamContextReminder(team, member, agentId)` 构造 `<team-context>` reminder
4. 提供 helper `teamSystemPromptSuffix()` 返回 F39 附录;`truncateForSummary(prompt)` 给初始任务 mailbox 消息生成 summary

**验证:** 单测覆盖 in-process 后端的 spawn 全流程;Pane 后端的 spawn 用 mock backend

## T19: Agent 工具集成 — `dev/cortex/agent/AgentTool.java` 修改

**文件:** `src/main/java/com/cortex/agent/AgentTool.java`(修改)
**依赖:** T16, T18
**步骤:**
1. `AgentToolArgs` record 加 `@JsonProperty("teamName") String teamName`(可空)
2. `AgentTool` 加字段 `TeamHook teamHook`
3. 构造器加参数 `TeamHook teamHook`
4. `description()` 中说明 `teamName` 参数(可选,非空时走 Team spawn)
5. `parametersSchema()` 加 `teamName` 字段
6. `execute` 在 `args.teamName() != null && !args.teamName().isBlank()` 时:
   - 校验 `teamHook != null`,否则报错
   - 校验 ctx 不在 in-process 队员中(`teamHook.teammateContextOf(ctx)`,若是且 backendType=IN_PROCESS,抛 `InProcessTeammateNoSpawnException`)
   - 调 `teamHook.spawnTeammate(new TeamSpawnRequest(...))`
   - 返回 spawnTeammate 的结果

**验证:** 单测:不带 teamName 走 ch13 老路径;带 teamName 调 mock teamHook,断言 spawnTeammate 被调

## T20: 队员 Loop incoming-messages 注入 — `dev/cortex/agent/TeamMailboxIngestor.java`

**文件:** `src/main/java/com/cortex/agent/TeamMailboxIngestor.java`(新建)
**依赖:** T16, T6
**步骤:**
1. 在 `Agent.run` / `runToCompletion` 的迭代头部(调 LLM 前),检查 invocation ctx 中是否有 TeammateContext;实现位于 `TeamMailboxIngestor.ingest(ctx, runtime)`
2. 若有,调 `tc.readUnread()` 闭包(由 spawn 时由 team 包注入,封装 `Mailbox.readUnread(agentId)`)
3. 若有未读消息,构造 `<incoming-messages>` reminder 字符串(F42),加到 `runtime.pendingReminders`(下一轮 buildReminder 取出)
4. 调 `tc.markRead(indices)` 闭包
5. 若收到 `plan_approval_response(approve=true)`,调 `agent.setPermissionMode(Permission.Mode.DEFAULT)` 切回 default(reminder 文本也会反映这一切换)。**注意:** Pane 后端子进程的 plan_approval 由 `TeamMemberRunner` 主循环额外处理一份——它读到 plan_approval_response 时同样切模式 + 合成续派 prompt 让 runToCompletion 接着跑(F19a)

**注意:** Agent 包不直接 import mailbox(避免循环);通过 `TeammateContext` 中的闭包访问;Message 在 agent 包定义一个轻量 record `IncomingMessage(String from, String type, long timestamp, String summary, String content)`,只取需要的字段。

**采用方案:** TeammateContext 携带闭包:
```java
public record TeammateContext(
    String teamName, String memberName, String agentId,
    Supplier<ReadUnreadView> readUnread,
    Consumer<List<Integer>> markRead
) {}
public record ReadUnreadView(List<Integer> indices, List<IncomingMessage> messages) {}
```
由 team 包在 spawn 时构造闭包注入。

**验证:** 单测覆盖:fake mailbox 写入 1 条消息,启动子 `Agent.run`,断言 reminder 含 `<incoming-messages>`

## T21: TaskManager 改造 — `dev/cortex/task/TaskManager.java` 修改

**文件:** `src/main/java/com/cortex/task/TaskManager.java`(修改)
**依赖:** T7
**步骤:**
1. `TaskManager` 持一个 `AgentNameRegistry nameReg` 引用(可选 null 兜底)
2. `launch` 时:若 nameReg 非 null 且 name 非空,调 `nameReg.register(name, id)`;同时保持本地 `byName` 兜底(避免破坏 ch13 既有调用)
3. `getByName` 优先用 `nameReg.resolve` 查
4. `sendMessage(parentCtx, name, message)` 优先 `nameReg.resolve`
5. 新增 `onTaskDone(Consumer<String> cb)` 注册接口,可注册多个回调(`List<Consumer<String>> taskDoneCallbacks`)
6. `runTask` 的 finally 末尾(在 notifyDone 后)逐个调 onTaskDone 回调
7. 加 `setNameRegistry(AgentNameRegistry reg)` setter

**验证:** 单测:注册 onTaskDone,launch 一个 noop task,等完成,断言回调被触发

## T22: 协作工具实现 — `dev/cortex/teams/*Tool.java`

**文件:** `TeamCreateTool.java` / `TeamDeleteTool.java` / `TaskCreateTool.java` / `TaskGetTool.java` / `TaskListTool.java` / `TaskUpdateTool.java` / `SendMessageTool.java`
**依赖:** T3, T6, T7, T8
**步骤:**
1. 每个工具实现 `Tool` 接口(`name()` / `description()` / `parametersSchema()` / `readOnly()` / `execute(args, ctx)`)
2. `TeamCreateTool`(F21):参数 teamName + description;`execute` 调 `TeamManager.create`,返回 JSON
3. `TeamDeleteTool`(F23):参数 teamName + force;`execute` 调 `TeamManager.delete`
4. `TaskCreateTool`(F26):参数 title/description/assignee/blockedBy;从 ctx 取 TeammateContext 找当前 Team;`execute` 调 `Store.create`
5. `TaskGetTool`(F27):参数 taskId
6. `TaskListTool`(F28):参数 status 过滤;返回带 isReady 字段的 JSON 数组
7. `TaskUpdateTool`(F29):参数 taskId + 各 Patch 字段
8. `SendMessageTool`(F34):参数 to/summary/message/type/payload;`execute` 调 `Mailbox.write` + `Backend.wake` + 续派检测
9. 每个工具 `readOnly()` 返回:TeamCreate/Delete/TaskCreate/Update/SendMessage 返回 false;TaskGet/TaskList 返回 true

**验证:** 每个工具一个单测覆盖正常路径与错误路径

## T23: 协作工具白名单生效 — 验证

**文件:** `src/test/java/dev/cortex/tool/FilterTest.java`(修改)
**依赖:** T17, T22
**步骤:**
1. 在 `applyAgentToolFilter` 测试中加用例:
   - 主 Agent(`teammate=false`)调用:看不到 TaskCreate / SendMessage 等
   - 队员(`teammate=true`)调用:看到这 5 个

**验证:** 测试通过

## T24: coordinator 包 — `dev/cortex/coordinator/Coordinator.java`

**文件:** `src/main/java/com/cortex/coordinator/Coordinator.java`
**依赖:** 无
**步骤:**
1. 实现 `static boolean isEnabled(AppConfig cfg)`——`cfg.features().coordinatorMode() && envTruthy(System.getenv("MEWCODE_COORDINATOR_MODE"))`
2. 实现 `static List<String> allowedTools()`(F53)
3. 实现 `static String systemPromptSuffix()`(F55)——除四阶段框架外,**必须**包含「派完队员就停手等汇报」的纪律段:派出 Agent/SendMessage 后禁止立刻 read_file/glob/grep/bash 自己探索;禁止 sleep/TaskList 凑时间;只在 Research 首次定位 / Synthesis 读队员产出 / Verification 收敛 时才允许自己用读类工具
4. 实现 `private static boolean envTruthy(String v)`——`"1"`/`"true"`/`"yes"`(大小写不敏感)

**验证:** 单测覆盖双锁的 4 种组合(00/01/10/11),只有 11 返回 true;tmux 实跑观察 Lead 派完队员后不立刻 glob/read_file 而是「等待汇报」

## T25: config 加 Features — `dev/cortex/config/AppConfig.java` 修改

**文件:** `src/main/java/com/cortex/config/AppConfig.java`(修改)
**依赖:** 无
**步骤:**
1. 加 record `FeaturesConfig(boolean coordinatorMode, boolean forkTeammate)`,SnakeYAML 解析时手动绑定
2. `AppConfig` record 加字段 `FeaturesConfig features`
3. 默认值都为 false(`ConfigLoader` 中若 yaml 无 `features` 段,构造 `new FeaturesConfig(false, false)`)

**验证:** 单测加载 yaml 含 features 段(`features:\n  coordinator_mode: true\n  fork_teammate: false`),断言字段被读出

## T26: TUI 集成 — `dev/cortex/tui/CortexModel.java` 修改

**文件:** `CortexModel.java` 与 `Statusbar.java`(修改)、`LeadMailWatcher.java`、`LeadMailWaiter.java`(新建)
**依赖:** T3, T24
**步骤:**
1. `TuiParams` record 加 `TeamManager teamManager`、`boolean coordinatorMode`
2. `CortexModel` 加字段 `boolean coordinatorMode` 与 `LinkedBlockingQueue<Object> leadMailQueue`(capacity=1);构造时初始化 `leadMailQueue = new LinkedBlockingQueue<>(1)`
3. coordinator 应用迁到 `Cortex.java` 中的 mainAgent 上(`setAllowedTools` + `appendSystemPrompt`)——TUI 自身只负责状态栏渲染
4. 状态栏渲染时若 `coordinatorMode==true` 在 mode label 后追加 ` [COORDINATOR]`(参见 `Statusbar.render()`)
5. config 字段名是 **snake_case**(SnakeYAML 默认):`features.coordinator_mode`(不是 camelCase);CortexModel 拿到的是 record `FeaturesConfig` 的 camelCase getter `features.coordinatorMode()`

**验证:** 在 config.yaml 加 `features:\n  coordinator_mode: true`,启动时设环境变量 `MEWCODE_COORDINATOR_MODE=1`,观察状态栏出现 `[COORDINATOR]`

## T27: /team slash 命令 — `dev/cortex/command/builtin/BuiltinTeam.java`

**文件:** `src/main/java/com/cortex/command/builtin/BuiltinTeam.java`
**依赖:** T3
**步骤:**
1. 注册 4 个本地命令(`CommandKind.LOCAL`):
   - `/team list`(F59)
   - `/team info <name>`(F60)
   - `/team delete <name> [--force]`(F61)
   - `/team kill <member>`(F62)
2. 在 `BuiltinRegistry.registerAll` 或对应注册入口加入

**验证:** `/team list` 在 TUI 输出含已创建 Team

## T28: Main wire — `dev/cortex/Cortex.java` 修改

**文件:** `src/main/java/com/cortex/Cortex.java`(修改)
**依赖:** T1-T27
**步骤:**
1. 构造 `AgentNameRegistry nameReg = new AgentNameRegistry()`
2. `taskMgr.setNameRegistry(nameReg)`
3. 构造 `TeamManager teamMgr = new TeamManager(home, root, worktreeMgr, taskMgr, nameReg)`
4. 注册 7 个新工具到 registry(TeamCreate/TeamDelete/TaskCreate/TaskGet/TaskList/TaskUpdate/SendMessage)
5. `AgentTool agentTool = new AgentTool(..., teamMgr)`(把 teamMgr 作为 TeamHook 注入)
6. `TuiParams` 加 `teamManager(teamMgr)`、`coordinatorMode(Coordinator.isEnabled(cfg))`
7. 若 CLI args 含 `--team-member`:**所有依赖 wire 完成后**直接调 `TeamMemberRunner.run(ctx, teamMemberArgs)` 并 `return`,**不**构造 TUI(F19a);否则继续走 TUI 路径
8. Lead 启动时(TUI 路径)若 `Coordinator.isEnabled(cfg)`:`mainAgent.setAllowedTools(Coordinator.allowedTools())` + `mainAgent.appendSystemPrompt(Coordinator.systemPromptSuffix())`

**验证:** `./gradlew shadowJar` 通过;启动 cortex 主流程正常

## T29: --team-member 自治循环 — `dev/cortex/cli/TeamMemberRunner.java`(新文件)

**文件:** `src/main/java/com/cortex/cli/TeamMemberRunner.java`(新建)
**依赖:** T28
**步骤:**
1. 用 picocli 解析新增 CLI flags:`--team-member` / `--team` / `--member` / `--agent-id` / `--session-dir` / `--worktree` / `--agent-type` / `--model` / `--plan-mode`
2. Main 中在 `--team-member` 分支先把 `System.setProperty("user.dir", workTree)` + 后续所有 `Path` 都以 `workTree` 作根,再 wire 完所有依赖
3. 实现 `static void run(Context ctx, TeamMemberArgs args)`:
   - 从 `args.teamManager().get(teamName)` 拿 Team(已含 Lead 写入的 alice 条目,reload-from-disk 兜底)
   - 解析角色定义(`SubAgentCatalog.resolve(agentType)`),拿 systemPrompt / maxTurns / plan 等
   - 用 `Filter.applyAgentToolFilter(new FilterParams(true, ...))` 算 allowed tools
   - 构造 provider(`Providers.create`)+ `Agent`,**强制 `dontAsk(true)`**(F39a)
   - 注入 `<team-context>` reminder(F40) + `setCtxDecorator` 把 `TeammateContext{mailbox: mc}` 装进 ctx
   - 起一条 stdin scanner virtual thread:每读一行就 `wakeQueue.offer(SIGNAL)`,触发 mailbox 即时轮询
   - 进主循环(F19a):read unread → 分流消息(text 拼 task / plan_approval / shutdown_request)→ `runToCompletion` → 通知 Lead idle → `setMemberActive(false)` → 等下一条(`wakeQueue.poll(2, SECONDS)`)
   - 检测 mailbox 目录消失 → 优雅退出
4. 把 `AgentEvent` 流转 stdout 打印(`printAgentEvent`),pane 内呈现只读日志

**验证:** 见 AC28 步骤 4 端到端实跑——alice pane 内显示 task 执行流,`/tmp/test_alice.txt` 落地,SendMessage 后 alice 能续派

## T30: 队员空闲通知 hook 注入

**文件:** `Cortex.java`(修改)+ `TeamManager.java`(加 helper)
**依赖:** T21, T3
**步骤:**
1. 在 Main wire 后,注册 onTaskDone 回调到 taskMgr:
   ```java
   taskMgr.onTaskDone(taskId -> teamMgr.handleTaskDone(taskId));
   ```
2. 实现 `void TeamManager.handleTaskDone(String agentId)`:
   - 查 `registry.nameOf(agentId)` → name
   - 遍历 teams 找到该成员所属 Team
   - `team.setMemberActive(name, false)`
   - `new Mailbox(team.mailboxDir()).write(leadAgentId, idleMessage)`

**验证:** 集成测试:in-process 后端 spawn 队员 → 自然结束 → 断言 Team.config 中 isActive=false、Lead mailbox 有 idle 消息

## T30b: Lead mailbox 轮询 + 自动唤醒 — `TeamManager.java` + `tui/LeadMailWatcher.java` + `tui/CortexModel.java`

**文件:**
- `TeamManager.java`(增加 `pollLeadMailboxes()` + `LeadMessage` record)
- `tui/LeadMailWatcher.java`(每秒轮询 virtual thread)
- `tui/LeadMailWaiter.java`(阻塞读 leadMailQueue 把信号转 GUI 事件)
- `tui/CortexModel.java`(Init 启动 watcher + waiter;处理 `LeadMailEvent`)
- `tui/AgentEvent 队列.java`(增加 `beginAutonomousTurn`)

**依赖:** T28(Main 已 wire teamMgr 进 TuiParams)
**步骤:**
1. `TeamManager.pollLeadMailboxes()`:遍历 `list()`,对每个 Team 用 `new Mailbox(t.mailboxDir()).readUnread(t.leadAgentId())` 读未读,标 read,返回 `List<LeadMessage(String teamName, String from, MessageType type, String summary, String content, long time)>`
2. `CortexModel` 加字段 `LinkedBlockingQueue<Object> leadMailQueue`(capacity=1,构造时初始化)
3. `LeadMailWatcher`(TUI Init 启动 virtual thread):1 秒 sleep loop → pollLeadMailboxes → 非空时调 `buildTeamUpdateReminder`(列消息条目 + content 截断 8000 字)→ `runtime.appendReminders` → 非阻塞 `leadMailQueue.offer(SIGNAL)`
4. `LeadMailWaiter`:阻塞读 leadMailQueue,通过 `program.send(new AgentEventMessage(...)` 提交 `LeadMailEvent` 给 CortexModel
5. `CortexModel.handleLeadMailEvent`:
   - re-arm waiter
   - 若 `state == SessionState.IDLE`,调 `beginAutonomousTurn` 自动开新轮
   - 否则 reminder 已在 pendingReminders 里,等当前 Run 下一轮迭代自然取出
6. `beginAutonomousTurn`:合成 user 消息 `"[team-update] 队员发来新消息,请按 Coordinator 流程处理..."`,`conv.addUser(...)` + 调 `beginTurn(userBlock(...))`——保证 LLM 调用满足「对话末尾必须 user」约束,用户在 scrollback 也能看见是自动触发

**验证:** tmux 实跑——Lead 派 alice + bob;30 秒内队员 runToCompletion idle 后 mailbox.unread 1 秒内归零(watcher 消费);若 Lead 当时空闲,屏幕上自动出现 `● [team-update] 队员发来新消息...` 用户文本块 + Lead 紧接着的 Synthesis 回复——内容包含队员报告里的真实文件名(如 `AgentTool.java`、`TeamMailboxIngestor.java`),证明完整 content 通过 reminder 传到 Lead 视野

## T31: 续写检测 — `dev/cortex/teams/SendMessageTool.java`

**文件:** `SendMessageTool.java`(同 T22)
**依赖:** T22, T21
**步骤:**
1. `SendMessageTool.execute` 写完邮箱后:
   - 取目标 `TeammateInfo.backendType()`
   - 若 backendType=IN_PROCESS:
     - 查 `taskManager.get(agentId)`,若 `status() != RUNNING`:
       - `team.setMemberActive(name, true)`
       - `taskManager.sendMessage(ctx, name, content)` 走 ch13 续派接口
   - 若 Pane 后端:已通过 wake 唤醒,无需续派

**验证:** 单测:先 spawn → 等结束 → SendMessage → 断言 task 重新 RUNNING

## T32: Plan 审批权限切换 — `dev/cortex/agent/TeamMailboxIngestor.java` 增强

**文件:** `TeamMailboxIngestor.java`(修改)或 `Agent.java`
**依赖:** T20
**步骤:**
1. 在 incoming-messages 注入逻辑中:若有 `plan_approval_response(approve=true)` 消息:
   - 调 `agent.setPermissionMode(Permission.Mode.DEFAULT)`(或 Lead 当前模式,本期固定 DEFAULT)
   - reminder 加文案:「Lead 已批准计划,权限模式已切到 default,可执行计划」
2. 若 approve=false:reminder 加文案:「Lead 驳回了计划,反馈:<feedback>。请调整后重新提交」

**验证:** 集成测试:队员以 plan 模式起步 → 收到 plan_approval_response(true) → `agent.permissionMode` 切换

## T33: 单元测试集 — 各包 *Test.java

**依赖:** T1-T32
**步骤:**
1. 跑 `./gradlew test`,补失败用例
2. 跑 `./gradlew compileJava` (代码风格由 IDE 保证)(google-java-format 风格),自动修复用 `./gradlew spotless:apply`
3. 跑 `./gradlew shadowJar` 确保打包通过

**验证:** 全绿

## T34: tmux 实跑端到端验证

**依赖:** T1-T33
**步骤:**
1. 启动 tmux:`tmux new-session -s ch15-test`
2. 在内层跑 `cd /path/to/cortex && ./gradlew shadowJar && java -jar build/libs/cortex.jar`
3. 在 TUI 输入:「创建一个名为 demo 的团队」
4. 观察:
   - Agent 调 `TeamCreate`
   - `~/.cortex/teams/demo/config.json` 落地
   - 状态栏 / 输出确认成功
5. 在 TUI 输入:「派 alice 用 general-purpose,在 worktree 里 echo hello > /tmp/test_alice.txt」
6. 观察:
   - tmux split 出新 pane
   - alice pane 内 cortex 子实例启动
   - `.cortex/worktrees/team-demo+alice/` 创建
   - `/tmp/test_alice.txt` 文件内容为 `hello`
7. 在 TUI 输入:`/team info demo`,确认 alice 出现
8. 在 TUI 输入:「给 alice 发消息,让她再写一行 world」(Agent 调 SendMessage)
9. 观察:alice pane 被唤醒,`/tmp/test_alice.txt` 多一行 `world`
10. `/team delete demo --force`,清理

**验证:** 步骤全部成功

## T35: in-process 实跑端到端验证

**依赖:** T1-T33
**步骤:**
1. `unset TMUX TERM_PROGRAM`
2. `java -jar build/libs/cortex.jar`
3. Agent 调 `TeamCreate("inproc")` → backend 为 `in-process`
4. Agent 派 bob(后端 in-process)
5. bob 在同进程跑完
6. 观察 `team.config.json` 中 bob 的 `isActive=false`
7. Lead 调 SendMessage(to="bob", message="再做一件事"),bob 从 session 恢复继续

**验证:** 全部成功

## 执行顺序

```
T1 → T2 → T3 → T3b → T4
              ↘
T5 → T6        T8 ── T9(把 lock 抽出,T6/T8 改 import)
T7
T10 → T11
   → T12,T13,T14(并行)
T15
T16 → T17 → T18 → T19
                → T20 → T32
T21
T22 → T23 → T31
T24 → T25 → T26
T27
T28 → T29 → T30 → T30b
T33(收尾测试)
T34, T35(实跑验收)
```

并行机会:T5/T7/T8 互不依赖;T12/T13/T14 互不依赖;T22 中 7 个工具可分批。