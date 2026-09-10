# Worktree 隔离 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/cortex/worktree/WorktreeSlug.java` | `validate` + `flatten` |
| 新建 | `src/test/java/dev/cortex/worktree/WorktreeSlugTest.java` | Slug 校验单测 |
| 新建 | `src/main/java/com/cortex/worktree/WorktreeSession.java` | record + JSON tag |
| 新建 | `src/main/java/com/cortex/worktree/SessionStore.java` | JSON 原子读写 |
| 新建 | `src/main/java/com/cortex/worktree/GitHelper.java` | `gitProcess` + `hasWorktreeChanges` + `resolveHeadShaFromFS` |
| 新建 | `src/test/java/dev/cortex/worktree/GitHelperTest.java` | git helper 单测 |
| 新建 | `src/main/java/com/cortex/worktree/WorktreeManager.java` | Manager 类型 + 构造 + list/get/currentSession |
| 新建 | `src/main/java/com/cortex/worktree/Worktree.java` | record |
| 新建 | `src/main/java/com/cortex/worktree/ExitAction.java` | enum |
| 新建 | `src/main/java/com/cortex/worktree/ExitOptions.java` | record |
| 新建 | `src/main/java/com/cortex/worktree/ExitReport.java` | record |
| 新建 | `src/main/java/com/cortex/worktree/AutoCleanupReport.java` | record |
| 新建 | `src/main/java/com/cortex/worktree/WorktreeHasChangesException.java` | 异常 |
| 新建 | `src/main/java/com/cortex/worktree/PostCreationSetup.java` | A/B/C/D 子步骤 |
| 新建 | `src/main/java/com/cortex/worktree/WorktreeNaming.java` | randomAgentName + ephemeral 正则 |
| 新建 | `src/test/java/dev/cortex/worktree/WorktreeCreateTest.java` | create + setup 单测 |
| 新建 | `src/test/java/dev/cortex/worktree/WorktreeLifecycleTest.java` | 生命周期单测 |
| 新建 | `src/test/java/dev/cortex/worktree/WorktreeSweepTest.java` | sweepStale 单测 |
| 新建 | `src/test/java/dev/cortex/worktree/WorktreeManagerTest.java` | NewManager + session 持久化测试 |
| 新建 | `src/test/java/dev/cortex/tool/Test.java` | resolvePath 单测 |
| 修改 | `src/main/java/com/cortex/tool/ReadFileTool.java` | 用 `ctx.resolvePath` 解析 path |
| 修改 | `src/main/java/com/cortex/tool/WriteFileTool.java` | 用 `ctx.resolvePath` 解析 path |
| 修改 | `src/main/java/com/cortex/tool/EditFileTool.java` | 用 `ctx.resolvePath` 解析 path |
| 修改 | `src/main/java/com/cortex/tool/GlobTool.java` | 用 `ctx.resolvePath` 解析 root |
| 修改 | `src/main/java/com/cortex/tool/GrepTool.java` | 用 `ctx.resolvePath` 解析 path |
| 修改 | `src/main/java/com/cortex/tool/BashTool.java` | `pb.directory(ctx.resolvePath("").toFile())` |
| 修改 | `src/main/java/com/cortex/subagent/Definition.java` | record 加 `isolation` 字段 |
| 修改 | `src/main/java/com/cortex/subagent/Parser.java` | 解析 isolation: frontmatter |
| 修改 | `src/test/java/dev/cortex/subagent/ParserTest.java` | 增加 isolation 单测 |
| 新建 | `src/main/java/com/cortex/agent/AgentWorktreeRunner.java` | `executeWithWorktree` + `buildWorktreeNotice` |
| 修改 | `src/main/java/com/cortex/agent/AgentTool.java` | 增加 `wtMgr` 字段 + isolation 分支 |
| 新建 | `src/test/java/dev/cortex/agent/AgentWorktreeRunnerTest.java` | 单测(stub Manager) |
| 修改 | `src/main/java/com/cortex/command/Ui.java` | 加 `worktreeAccessor()` 方法 |
| 新建 | `src/main/java/com/cortex/command/WorktreeAccessor.java` | 接口 + WorktreeSummary |
| 新建 | `src/main/java/com/cortex/command/WorktreeCommand.java` | `/worktree` handler + 子命令解析 |
| 修改 | `src/main/java/com/cortex/command/Builtins.java` | 注册 `/worktree` |
| 修改 | `src/test/java/dev/cortex/command/BuiltinsTest.java` | 加 worktree 注册测试 |
| 新建 | `src/main/java/com/cortex/tui/TuiWorktreeAccessor.java` | 实现 `WorktreeAccessor` 适配 `WorktreeManager` |
| 修改 | `src/main/java/com/cortex/tui/CortexModel.java` | `worktreeMgr` / `activeCwd` 字段 + 注入 ctx |
| 修改 | `src/main/java/com/cortex/Cortex.java` | 构造 manager + 注入 AgentTool / TUI + sweepStale |
| 修改 | `.gitignore` | 追加 .cortex/worktrees/ + worktree_session.json |

## T1: Slug 校验

**文件:** `WorktreeSlug.java` + `WorktreeSlugTest.java`
**依赖:** 无
**步骤:**
1. 新建包 `com.cortex.worktree`,加 package-info 注释
2. 实现 `public static void validate(String name)`,规则:非空、长度 ≤ 64、按 `/` 切段后每段匹配 `^[a-zA-Z0-9._-]+$` 且不能是 `.` 或 `..`、无连续 `//`、无首末 `/`;失败抛 `IllegalArgumentException` 带具体原因
3. 实现 `public static String flatten(String name)`:`name.replace("/", "+")`
4. 写测试覆盖合法/非法 case:`alice`、`team/alice`、`v1.0`、`a_b`(合法);空、超长、`..`、`./x`、`a//b`、`/x`、`a/`、`a b`、`a;b`(非法)

**验证:** `./gradlew -q test -Dtest=WorktreeSlugTest`

## T2: WorktreeSession 持久化

**文件:** `WorktreeSession.java` + `SessionStore.java`
**依赖:** T1
**步骤:**
1. 定义 `WorktreeSession` record,字段按 spec F3,Jackson `@JsonProperty` 标小写下划线;类型用 `String`(JVM 当前目录用 `Path.of("").toAbsolutePath().toString()` 序列化)
2. 实现 `SessionStore.load(Path path)`:文件不存在返回 `Optional.empty()`;内容为 `null` 或空返回 `Optional.empty()`;JSON 解析失败抛 `IOException`
3. 实现 `SessionStore.save(Path path, WorktreeSession s)`:s=null 时写字符串 `null`;原子写——先写 `path + ".tmp"` 再 `Files.move(..., StandardCopyOption.ATOMIC_MOVE, REPLACE_EXISTING)`
4. 实现 `SessionStore.clear(Path path)`(等同 `save(path, null)`)
5. JSON 用 Jackson(`ObjectMapper`);如项目还未直接依赖,通过 anthropic-java 间接传递,可在 `build.gradle.kts` 显式加 `com.fasterxml.jackson.core:jackson-databind` 锁版本

**验证:** 在 `WorktreeManagerTest` T9 中覆盖

## T3: Git helper

**文件:** `GitHelper.java` + `GitHelperTest.java`
**依赖:** 无
**步骤:**
1. 实现 `static ProcessBuilder gitProcess(Path workDir, String... args)`:`new ProcessBuilder` 接收 `git` + args,`directory(workDir.toFile())`,环境 `environment().put("GIT_TERMINAL_PROMPT", "0")` 与 `put("GIT_ASKPASS", "")`,`redirectInput(ProcessBuilder.Redirect.from(new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null")))`
2. 实现 `static String runGit(Path workDir, String... args) throws IOException`:启动进程,读 stdout(`process.getInputStream()` → `new String(..., UTF_8)`),`waitFor()`;非零退出抛 `IOException` 带 stderr;返回 stdout 去 trailing 换行
3. 实现 `static boolean hasWorktreeChanges(Path wtPath, String baseCommit)`:① `git -C status --porcelain` 非空 ② `git -C rev-list --count <baseCommit>..HEAD` >0;任一 git 命令本身抛异常 fail-closed 返回 true
4. 实现 `static Optional<String> resolveHeadShaFromFS(Path wtPath)`:读 `wtPath/.git` 取 `gitdir: <path>`,读 `<gitdir>/HEAD`,若是 `ref: refs/heads/<name>`,读 `<gitdir>/<refpath>` 拿 SHA;失败返回 `Optional.empty()`
5. 测试:用一个临时 git 仓库(`Files.createTempDirectory` + 调真实 `git init`)做真实 Worktree,断言上述函数行为

**验证:** `./gradlew -q test -Dtest=GitHelperTest`

## T4: Manager 构造

**文件:** `WorktreeManager.java`(主类) + `Worktree.java` + 辅助 record + `WorktreeManagerTest.java`
**依赖:** T2, T3
**步骤:**
1. 定义 `Worktree` record(spec F2 字段) + `WorktreeManager` 字段(spec F4) + 类型常量 `private static final List<String> DEFAULT_SYMLINK_DIRS = List.of("node_modules", ".venv", "vendor");`
2. 实现 `public WorktreeManager(Path repoRoot) throws IOException`:
   - `this.repoRoot = repoRoot.toAbsolutePath().normalize();`
   - 跑 `git -C <repoRoot> rev-parse --show-toplevel`,输出与 repoRoot 不匹配则抛 `IOException`
   - 初始化 `worktreeDir = repoRoot.resolve(".cortex/worktrees");`、`sessionFile = repoRoot.resolve(".cortex/worktree_session.json");`
   - `Files.createDirectories(worktreeDir);`
   - `SessionStore.load(sessionFile)`;若 session 非空但其 `worktreePath` 不存在,清空 session 并 stderr 警告
   - 扫描 `worktreeDir` 子目录,对每个非空目录用 `GitHelper.resolveHeadShaFromFS` 填 `active`(快速恢复路径,不调 git)
3. 实现 `list()` (按 name 排序)、`get(name)`、`currentSession()`
4. 测试:在临时 git 仓库构造 manager,断言 `worktreeDir` 创建、空 session 时 `currentSession()=null`、预放 session 文件能被加载、Worktree 目录不存在时 session 被清空

**验证:** `./gradlew -q test -Dtest=WorktreeManagerTest`

## T5: Create + 快速恢复 + 创建后设置

**文件:** `WorktreeManager.java`(`create` 方法) + `PostCreationSetup.java` + `WorktreeCreateTest.java`
**依赖:** T4
**步骤:**
1. 实现 `public Worktree create(String name, String baseRef, boolean manual) throws IOException`:
   - `WorktreeSlug.validate(name)` 不通过即抛
   - `lock.lock(); try { ... } finally { lock.unlock(); }`;`active.containsKey(name)` 即抛
   - 算 `flatSlug`、`wtPath`、`branchName`
   - 若 `Files.exists(wtPath)`,用 `GitHelper.resolveHeadShaFromFS` 取 sha,构造 `Worktree` 放 `active`,**直接返回**(快速恢复,跳过 setup)
   - 否则跑 `git worktree add -B <branch> <wtPath> <baseRef>`,用 `GitHelper.gitProcess`
   - 失败时:递归删除已创建的目录,重新抛
   - 调 `PostCreationSetup.run(repoRoot, wtPath, symlinkDirs)`,失败仅 stderr 警告
   - 跑 `git -C <wtPath> rev-parse HEAD` 拿 headSha
   - 构造 `Worktree` 放 `active`,返回
2. 实现 `PostCreationSetup.run(...)`,四个 static 子函数:
   - `copyLocalConfigs(repoRoot, wtPath)`:对 `.cortex/config.yaml` / `.cortex/settings.local.yaml`,若主仓存在且 Worktree 不存在,`Files.copy`
   - `setupGitHooks(repoRoot, wtPath)`:优先 `.husky/`,回退 `git -C <repoRoot> config --get core.hooksPath` 拿主仓配置,若有值跑 `git -C <wtPath> config core.hooksPath <绝对路径>`
   - `symlinkLargeDirs(repoRoot, wtPath, symlinkDirs)`:对每个目录若主仓存在且 Worktree 不存在,`Files.createSymbolicLink(wtPath.resolve(dir), repoRoot.resolve(dir).toAbsolutePath())`
   - `copyIncludedIgnored(repoRoot, wtPath)`:读 `.worktreeinclude` 模式;跑 `git -C <repoRoot> ls-files --others --ignored --exclude-standard --directory` 列出忽略文件;每个文件用 `FileSystems.getDefault().getPathMatcher("glob:" + pat)` 匹配;命中则 `Files.copy` 到 Worktree
   - 每个子函数 try/catch,失败只往 stderr 写一行 `worktree: setup <step>: <err>` 警告,继续下个步骤
3. 测试:在临时 git 仓库覆盖:create 成功后目录存在、分支存在、设置 A 复制 settings.local.yaml、设置 C 软链 node_modules、设置 D 按 .worktreeinclude 复制 .env;快速恢复路径不调 git(可观察临时仓库 `git reflog` 或在子进程统计)

**验证:** `./gradlew -q test -Dtest=WorktreeCreateTest`

## T6: Enter / Exit / Remove / AutoCleanup

**文件:** `WorktreeManager.java`(生命周期方法) + `WorktreeLifecycleTest.java`
**依赖:** T5
**步骤:**
1. 在 `WorktreeManager` 上实现 `enter` / `exit` / `remove` / `autoCleanup`
2. `public WorktreeSession enter(String name) throws IOException`:
   - 加锁取 `active.get(name)`
   - `Path.of("").toAbsolutePath()` 取原 cwd 字符串
   - `git -C <repoRoot> rev-parse --abbrev-ref HEAD` 与 `git -C <repoRoot> rev-parse HEAD` 取原状态(失败用空字符串兜底)
   - 生成 `sessionId = UUID.randomUUID().toString()`
   - 写 `currentSession` 字段,`SessionStore.save`
3. `public ExitReport exit(String name, ExitAction action, ExitOptions opts) throws IOException`:
   - 加锁;校验 `currentSession` 非空且 `worktreeName().equals(name)`;否则抛
   - 取 `active.get(name)`;若 null 抛
   - `action == REMOVE && !opts.discardChanges()`:调 `hasWorktreeChanges`,true 则抛 `WorktreeHasChangesException`
   - 记录 `originalCwd`(供上层 TUI 还原 activeCwd)
   - `currentSession = null`,`SessionStore.save(sessionFile, null)`
   - `action == REMOVE`:`git worktree remove --force <path>`,`Thread.sleep(100)`,`git branch -D <branch>`,`active.remove(name)`
   - 返回 `ExitReport`
4. `public void remove(String name, ExitOptions opts) throws IOException`:类似 `exit` 的 REMOVE 分支,但允许非当前 session;变更保护同
5. `public AutoCleanupReport autoCleanup(String name) throws IOException`:
   - 取 `active.get(name)`;`manual=true` 直接 `kept=true` 返回(带 path/branch)
   - `hasWorktreeChanges=false` 调 `remove(name, new ExitOptions(true))`,返回 `kept=false`
   - 有变更:`kept=true`,返回 path/branch
6. 测试:`enter` 不改 JVM cwd、`exit` 后 `activeCwd` 还原由 TUI 接管这里只断言 `currentSession` 已清空、`exit` remove 变更保护、`autoCleanup` manual/无变更/有变更三种分支

**验证:** `./gradlew -q test -Dtest=WorktreeLifecycleTest`

## T7: SweepStale

**文件:** `WorktreeManager.java`(`sweepStale`) + `WorktreeNaming.java` + `WorktreeSweepTest.java`
**依赖:** T6
**步骤:**
1. `WorktreeNaming` 内定义 `public static final Pattern EPHEMERAL_PATTERN = Pattern.compile("^agent-a[0-9a-f]{7}$");`
2. 实现 `public List<String> sweepStale(Instant cutoff)`:
   - `Files.list(worktreeDir)`(try-with-resources)遍历
   - 对每个目录:不匹配 pattern 跳过;`Files.getLastModifiedTime` > cutoff 跳过;`currentSession != null && Path.of(currentSession.worktreePath()).equals(sub)` 跳过
   - 跑 `GitHelper.hasWorktreeChanges(子路径, "HEAD")` true 跳过(fail-closed)
   - 额外:`git -C <子路径> rev-list --max-count=1 HEAD --not --remotes` 非空跳过(有未推送 commit 也保留)
   - 通过的:调 `remove(name, new ExitOptions(true))`,记 `removed`
3. 实现 `public static String randomAgentName()`(返回 `"agent-a" + 7位随机 hex`),用 `SecureRandom` 取 4 字节 → hex 截前 7 位
4. 测试:构造三个目录(匹配模式无变更、匹配模式有变更、不匹配模式),`sweepStale` 只删第一个

**验证:** `./gradlew -q test -Dtest=WorktreeSweepTest`

## T8: tool ctx

**文件:** 工具执行取消通过 Thread.interrupt() 实现
**依赖:** 无(并行 T1-T7)
**步骤:**
1. 新建/扩展 `com.cortex.tool.` record,字段 `Optional<Path> cwd`(若已有 ctx record,把 cwd 合并到既有 record 末尾)
2. 实现 `static  root()` 返回空 ctx;`withCwd(Path dir)` 返回新 ctx
3. 实现 `Path resolvePath(String p)`:
   - `p == null || p.isEmpty()`:返回 ctx cwd 或 `Path.of("").toAbsolutePath()` 兜底
   - `Path.of(p).isAbsolute()`:返回 `Path.of(p).normalize()`
   - 否则:`base = ctx.cwd().orElseGet(() -> Path.of("").toAbsolutePath()); return base.resolve(p).normalize();`
4. 测试:覆盖三种 path、ctx 无 cwd 时回落到 JVM 当前目录、空字符串返回 cwd 本身

**验证:** `./gradlew -q test -Dtest=Test`

## T9: 改造 6 个核心工具

**文件:** `BashTool.java` / `ReadFileTool.java` / `WriteFileTool.java` / `EditFileTool.java` / `GlobTool.java` / `GrepTool.java`
**依赖:** T8
**步骤:**
1. `ReadFileTool`:在 `Files.readAllBytes(...)` 前 `Path abs = ctx.resolvePath(args.path());`,后续用 `abs`
2. `WriteFileTool`:同样改造 path;若需要 `Files.createDirectories` 时也用 `abs.getParent()`
3. `EditFileTool`:同样
4. `GlobTool`:`String root = args.path() == null ? "." : args.path();`;然后 `Path absRoot = ctx.resolvePath(root);`;`Files.walk(absRoot)` 用 absRoot;返回路径仍按相对 root 输出(保持现有行为)
5. `GrepTool`:与 `GlobTool` 同
6. `BashTool`:在 `ProcessBuilder pb = ...` 之后,`pb.directory(ctx.resolvePath("").toFile());`(空字符串解析为 cwd 本身)
7. 不改 Schema(`schema()` 不变),不改 description
8. 单测可放各 tool 测试或新增 `ToolCwdTest`——构造 `ctx.withCwd(tmpDir)`,在 tmpDir 里准备文件,调工具断言读到对应内容

**验证:** `./gradlew -q test -Dtest='*ToolTest,*ToolCwdTest'`

## T10: subagent.Definition isolation

**文件:** `Definition.java` / `Parser.java` / `ParserTest.java`
**依赖:** 无
**步骤:**
1. `Definition.java`:record 加 `String isolation`(末尾参数,默认值通过 `@JsonProperty(defaultValue = "")` 或解析时填空)
2. `Parser.java`:frontmatter Map 取 `isolation` 字段,校验合法值 `""` / `"worktree"`,非法值 stderr 警告并回落 `""`,把结果填到 `Definition`
3. `ParserTest`:增加测试覆盖 `isolation: worktree` 解析成功、`isolation: gibberish` 警告并回落空

**验证:** `./gradlew -q test -Dtest=ParserTest`

## T11: AgentWorktreeRunner

**文件:** `AgentWorktreeRunner.java` + `AgentWorktreeRunnerTest.java`
**依赖:** T6, T8, T10
**步骤:**
1. 新建 `AgentWorktreeRunner`,持 `WorktreeManager wtMgr`(构造器注入);agent 包直接 import `com.cortex.worktree.*`(worktree 包不依赖 agent,无循环)
2. 实现 `static String buildWorktreeNotice(Path parentCwd, Path wtPath)`(按 spec F22 模板)
3. 实现 `static String randomAgentName()`,委托 `WorktreeNaming.randomAgentName()`
4. 实现 `String executeWithWorktree(Map<String, Object> args, Definition def, Agent subAgent, Conversation subConv, String prompt, BlockingQueue<AgentEvent> events) throws IOException`:
   - `name = randomAgentName()`
   - `wt = wtMgr.create(name, "HEAD", false)`
   - `Path parentCwd = Path.of("").toAbsolutePath();`
   - `notice = buildWorktreeNotice(parentCwd, wt.path())`
   - `taskText = notice + "\n\n" + prompt`
   - `ctx = ctx.withCwd(wt.path())`
   - `finalText = subAgent.runToCompletion(ctx, subConv, taskText, events)`
   - `report = wtMgr.autoCleanup(name)`
   - 若 `report.kept()`,把保留信息追加到 `finalText`
   - 返回 `finalText`
5. 单测:用真实临时 git 仓库构造 `WorktreeManager`;subAgent 用 mock Provider(返回空文本即结束);断言 `wt.path()` 被传到 ctx、`autoCleanup` 被调用

**验证:** `./gradlew -q test -Dtest=AgentWorktreeRunnerTest`

## T12: AgentTool 接入 isolation 分支

**文件:** `AgentTool.java`
**依赖:** T11
**步骤:**
1. `AgentTool` 加字段 `WorktreeManager wtMgr`(允许 null)
2. 构造器 `new AgentTool(catalog, taskMgr, parent, bgEnabled, wtMgr)`——签名末尾追加 `wtMgr`
3. 在 `execute` 内,若 `def.isolation().equals("worktree")`:
   - `wtMgr == null` → 返回 `ToolResult.error("worktree manager not configured")`
   - `background == true` → 本期 isolation+worktree 强制走前台路径(忽略 background)
   - 调 `new AgentWorktreeRunner(wtMgr).executeWithWorktree(ctx, def, subAgent, subConv, args.prompt(), events)` 替代直接 `runToCompletion`
4. 改 `Cortex.java` 构造 `AgentTool` 时传入 `wtMgr`

**验证:** `./gradlew -q test -Dtest='Agent*Test'`

## T13: command 包加 WorktreeAccessor + /worktree handler

**文件:** `Ui.java` / `WorktreeAccessor.java` / `WorktreeCommand.java` / `Builtins.java` / `BuiltinsTest.java`
**依赖:** T6
**步骤:**
1. `Ui.java`:加方法 `WorktreeAccessor worktreeAccessor()`(可返回 null);`NopUi` 实现返回 null
2. `WorktreeAccessor.java`:新增接口(spec F24-F28 所列方法) + `WorktreeSummary` record
3. `WorktreeCommand.java`:实现 `Command` 接口(`name()`="worktree"、`kind()`=LOCAL、`handle(ctx, ui, args)`)——`args` 是 `/worktree` 后面的全部尾随字符串;split 子命令 + 其余参数
   - `create <slug>` → `ui.worktreeAccessor().create(slug)`,输出 `Worktree 已创建: <path> (分支 <branch>)`
   - `list` → 遍历 `list()`,按格式输出
   - `enter <slug>` → `enter(slug)`,输出 `已进入 <slug>: <path>`
   - `exit [--remove] [--discard]` → 解析 flag,调 `exit`
   - `remove <slug> [--discard]` → 调 `remove`
   - 未知子命令报错
4. `Builtins.java`:`register(new WorktreeCommand())`——需要 command parser 支持带参数命令把 tail 透传到 handler。若现有 parser 在带参时让 lookup miss,**最小改动**:扩展 `Parse` 使带参命令仍能命中,把 tail 通过 `handle(ctx, ui, args)` 的 args 参数传入
5. 测试:测试 `WorktreeCommand.handle` 分发逻辑(用 stub Ui / stub Accessor)

**验证:** `./gradlew -q test -Dtest='BuiltinsTest,WorktreeCommandTest'`

## T14: TUI 适配 + 注入 ctx

**文件:** `TuiWorktreeAccessor.java` + `CortexModel.java`
**依赖:** T11, T13
**步骤:**
1. `TuiWorktreeAccessor.java`:实现 `WorktreeAccessor` 接口,内部持 `WorktreeManager` + `Consumer<Path> activeCwdSetter`,把方法转发并组装 `WorktreeSummary` 列表;`enter` 内部调 `manager.enter` 后调 setter 把 activeCwd 写到 TUI
2. `CortexModel.java`:字段加 `WorktreeManager worktreeMgr`、`Path activeCwd`(null 表示 JVM 当前目录)
3. `CortexModel.Builder` 加 `worktreeMgr(WorktreeManager)` 方法;构造时若 `manager.currentSession()` 非 null,设 `activeCwd = Path.of(session.worktreePath())`
4. 实现 `worktreeAccessor()` 方法返回 `TuiWorktreeAccessor` 实例
5. 在主 Agent run 调用入口(找 `CortexModel` 里 `agent.run(ctx, conv, mode)` 调用点),前置 `ctx = ctx.withCwd(effectiveCwd());`
6. `effectiveCwd()`:若 `activeCwd != null` 返回 `activeCwd`,否则返回 `Path.of("").toAbsolutePath()`

**验证:** `./gradlew shadowJar` 通过;`/worktree create x` + `/worktree enter x` + Read file(相对路径) 在 worktree 内成功

## T15: Main 接入

**文件:** `Cortex.java` + `.gitignore`
**依赖:** T4-T14 全部
**步骤:**
1. `Cortex.java`:在 `var subagentCatalog = SubagentCatalog.load(root);` 后加:
   ```java
   WorktreeManager worktreeMgr;
   try {
       worktreeMgr = new WorktreeManager(root);
       final var mgr = worktreeMgr;
       Thread.startVirtualThread(() ->
           mgr.sweepStale(Instant.now().minus(24, ChronoUnit.HOURS)));
   } catch (IOException werr) {
       System.err.println("Worktree 管理器降级: " + werr.getMessage());
       worktreeMgr = null;
   }
   ```
2. `new AgentTool(...)` 调用末尾追加 `worktreeMgr` 参数
3. `new CortexModel(` 链上新增 `.worktreeMgr(worktreeMgr)`
4. `.gitignore` 追加:
   ```
   # ch14: Worktree 隔离副本(仅供 SubAgent 与手动管理使用)
   .cortex/worktrees/
   .cortex/worktree_session.json
   ```

**验证:** `./gradlew shadowJar`、`./gradlew -q spotless:check`、`./gradlew -q test` 全过

## T16: 端到端 tmux 验证

**文件:** 无代码修改,运行测试
**依赖:** T15
**步骤:**
1. `./gradlew shadowJar` 产出 `build/libs/cortex.jar`
2. 准备项目级自定义 Agent `.cortex/agents/worktree-writer.md`(详见 checklist 场景 1)
3. tmux 启动 cortex(`java -jar build/libs/cortex.jar`),跑 checklist 端到端场景
4. 通过即标记 T16 完成

**验证:** 见 checklist.md 场景 1-6

## 执行顺序

```
T1 (slug)
  ↓
T2 (session) — T3 (git helper) — T8 (tool/ctx)
                                    ↓
T4 (manager construct)          T9 (改造 6 tools)
  ↓
T5 (create + setup)
  ↓
T6 (lifecycle)
  ↓
T7 (sweep)
  ↓
T10 (subagent.isolation)
  ↓
T11 (AgentWorktreeRunner)
  ↓
T12 (AgentTool 接入)
  ↓
T13 (/worktree command) — T14 (TUI 接入)
                              ↓
T15 (Cortex.java + .gitignore)
  ↓
T16 (tmux 端到端)
```

T1/T2/T3/T8 之间可并行;其余按依赖顺序。