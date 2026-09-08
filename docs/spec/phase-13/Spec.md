# Worktree 隔离 Spec## 背景ch13 SubAgent 隔离了消息、权限决策状态、文件读缓存和 token 计数,但 **文件系统**仍然共享。主 Agent 和后台子 Agent(以及下一章要做的 Agent Team 队员)会在同一时刻并发读写同一份工作目录的文件,出现读到对方写了一半的文件、互相覆盖修改等并行冲突——本质就是经典的并行开发文件冲突,和两个程序员同时改同一份文件一样。

Git 分支只能做**时间维度**的隔离(切换分支时工作目录被覆盖,同一时刻只有一个工作目录),不能解决并行问题;切分支还会刷被切文件的 mtime,触发依赖追踪型构建工具的链式重编。

需要的是**空间维度**的隔离:同一仓库同时挂多个工作目录、共享版本库、各自一个分支。这就是 Git Worktree (Git 2.5+) 的能力。本章在 mewcode 中封装一层 Worktree 管理逻辑,把这块拼图补给 SubAgent,让后台 / 并行场景安全可用。

mewcode 现有相关基础设施:
- ch13 SubAgent 已支持 frontmatter (`dev.mewcode.subagent.Parser`),解析 `name/description/tools/disallowedTools/model/maxTurns/permissionMode/background` 等字段
- ch13 `agent.AgentTool#execute` 已是子 Agent 启动入口,本章在此处插桩 isolation 分支
- ch08 文件读缓存以绝对路径作为 key
- ch10 `dev.mewcode.command` 已有 slash 命令注册系统
- `.gitignore` 已忽略 `.mewcode/sessions/` 等子目录,本章扩展把 `.mewcode/worktrees/` 也忽略
- Tool 接口 `execute(ctx, args) Result` 现支持 ctx 携带值(已有 `CTX_KEY_CONV` / `CTX_KEY_SUBAGENT_DEPTH` 范式),可作为 explicit cwd 的传递通道

本章不引入 Worktree 间合并策略、跨目录代码同步、多 Agent 并行编排,这些属于上层 / 下一章范畴。

## 目标- **G1**: 提供 `WorktreeManager` 封装 Worktree 完整生命周期——创建、快速恢复、进入、退出、删除;并发场景下用单一 `ReentrantLock` 保护内部 `active` 映射
- **G2**: 名字 (slug) 严格安全校验——限字符集 `[a-zA-Z0-9._-]`、总长度上限 64、显式拒绝 `.` 和 `..` 段名、允许 `/` 做嵌套分隔;防 LLM 输入触发路径遍历
- **G3**: Worktree 目录统一落在仓库内不被追踪的位置 `.mewcode/worktrees/<flatSlug>/`,分支名前缀 `worktree-<flatSlug>`,嵌套 slug 的 `/` 替换为 `+` 避免 Git D/F 冲突
- **G4**: 创建后做四类环境初始化——A 复制本地配置 (`.mewcode/config.yaml` / `.mewcode/settings.local.yaml`)、B 配置子目录的 git hooks (`core.hooksPath` 不自动继承)、C 软链 `node_modules` / `.venv` / `vendor` 等大目录、D 按项目根 `.worktreeinclude` 复制被忽略但运行需要的文件;均为 best-effort,失败只警告不中断创建
- **G5**: 快速恢复——目录已存在时,仅读 `.git` 指针 + `HEAD` + `refs/` 文件系统读还原 commit SHA,不调任何 git 子进程,毫秒级返回
- **G6**: 进入 Worktree 不调任何进程级 `chdir`——把 `WorktreePath` 记到会话状态 (`WorktreeSession`) 并通过 ctx 传给工具调用;Bash / Read / Write / Edit / Glob / Grep 工具从 ctx 取 cwd,本次调用显式声明在 Worktree 里跑;JVM 进程当前目录不变,避免并发组件之间的同步点
- **G7**: 文件读缓存等以绝对路径为 key 的缓存,天然按目录隔离;进入 / 退出 Worktree **不需要清缓存**
- **G8**: 退出时变更保护——`action="remove"` 且未显式 `discardChanges=true` 时,检测到未提交修改或本地多于 base 的 commit 一律拒绝删除;同时把当前目录信息还原到原 cwd 兜底防 session 期间残留
- **G9**: 自动清理 (`autoCleanup`)——SubAgent 退出时,无变更则直接 remove,有变更则保留 Worktree 路径与分支名追加到 SubAgent 结果文本给主 Agent review
- **G10**: 后台过期 Worktree 清理——按命名模式 (`agent-a[0-9a-f]{7}`) 只识别临时 Worktree,叠加时间过滤(超过 cutoff 才考虑),最后做 fail-closed 变更检查(有未提交修改 / 未推送 commit 都保留)
- **G11**: `WorktreeSession` 持久化到 `.mewcode/worktree_session.json`,mewcode 启动时读取并校验目录仍存在;退出时写空 JSON `null` 而不是删文件,确保下次启动不误恢复
- **G12**: 在 `subagent.Definition` 增加 `isolation` 字段 (`""` / `"worktree"`);SubAgent 启动器检测到 `isolation:worktree` 后,自动 `create → inject worktree notice → set ctx cwd → runToCompletion → autoCleanup`,无需在 prompt / 工具调用里显式指定
- **G13**: 提供 TUI slash 命令 `/worktree create <slug>`、`/worktree list`、`/worktree exit [--remove]`、`/worktree remove <slug> [--discard]`——让用户手动管理;手动创建的 Worktree **不走自动清理**
- **G14**: 与 ch04~ch13 协同——主 Agent 看到的工具列表不变(ctx 注入不改 schema)、prompt cache 不抖动、既有测试不破坏

## 功能需求### Slug 验证- **F1**: `WorktreeSlug.validate(name)` 校验规则——
  - name 非空,总长度 ≤ 64
  - 按 `/` 切段,每段必须匹配正则 `^[a-zA-Z0-9._-]+$` 且不能是 `.` 或 `..`
  - 不允许出现连续 `//`、首末 `/`
  - 失败时抛出带具体原因的 `IllegalArgumentException`

### WorktreeManager 与核心数据结构- **F2**: `Worktree` record 记录单个 Worktree 的元信息——`name`(原始 slug)、`path`(绝对路径)、`branch`(`worktree-<flatSlug>`)、`basedOn`(创建时的 base 引用,如 `HEAD` 或具体 commit)、`headCommit`(创建时的 commit SHA)、`created`(`Instant`)、`manual`(boolean,是否用户手动创建,影响 autoCleanup 跳过判断)
- **F3**: `WorktreeSession` record 记录当前活跃的 Worktree 会话——`originalCwd`、`worktreePath`、`worktreeName`(原 slug)、`originalBranch`、`originalHeadCommit`、`sessionId`(UUID 字符串)、`hookBased`(boolean,预留)
- **F4**: `WorktreeManager` 内部字段——`repoRoot`(绝对路径)、`worktreeDir`(`<repoRoot>/.mewcode/worktrees`)、`sessionFile`(`<repoRoot>/.mewcode/worktree_session.json`)、`ReentrantLock lock`、`Map<String, Worktree> active`、`WorktreeSession currentSession`
- **F5**: `new WorktreeManager(Path repoRoot)` 构造时——
  - 校验 `repoRoot` 是 git 仓库根目录(`git rev-parse --show-toplevel` 输出与之等);失败抛 `IOException`,mewcode 启动允许降级到「Worktree 功能未启用」
  - 创建 `worktreeDir` 目录(如不存在)
  - 从 `sessionFile` 反序列化 `currentSession`(允许文件不存在);若 session 指向的 Worktree 目录已不存在,清空 session 文件并把 `currentSession=null`
  - 扫描 `worktreeDir` 子目录还原 `active` 映射(name → Worktree),仅按文件系统读填字段(快速恢复路径)
- **F6**: `manager.create(name, baseRef, manual)`——
  - 1. `WorktreeSlug.validate(name)` 不通过即抛异常
  - 2. `lock.lock()`,若 `active.get(name)` 已存在,抛异常
  - 3. `flatSlug = name.replace("/", "+")`、`wtPath = worktreeDir.resolve(flatSlug)`、`branchName = "worktree-" + flatSlug`
  - 4. 快速恢复路径:若 `wtPath` 已存在,直接读 `.git` 指针 + `HEAD` + `refs/heads/<branch>` 得 `headSha`,构造 `Worktree` 放入 `active`,返回(不调任何 git 子进程)
  - 5. 否则执行 `git worktree add -B <branch> <wtPath> <baseRef>`,环境变量 `GIT_TERMINAL_PROMPT=0` + `GIT_ASKPASS=""`,stdin 关闭;失败时抛异常并清理可能残留的目录
  - 6. 执行创建后设置 `performPostCreationSetup` (F7-F10),任何子步骤失败仅 stderr 警告,不中断
  - 7. 读出 `headSha`(`git -C <wtPath> rev-parse HEAD`),装填 `Worktree(name, path, branch, basedOn, headCommit, created, manual)`
  - 8. 加入 `active`,返回
- **F7**: 创建后设置 A——复制本地配置文件,从 `<repoRoot>/.mewcode/config.yaml` 与 `<repoRoot>/.mewcode/settings.local.yaml` 复制到 Worktree 同位置(目标已存在跳过,文件不存在跳过)
- **F8**: 创建后设置 B——配置 git hooks,检测主仓库 `core.hooksPath` 与 `.husky/` 目录,若有则 `git -C <wtPath> config core.hooksPath <绝对路径>`;无则跳过
- **F9**: 创建后设置 C——按配置软链大目录,默认列表 `["node_modules", ".venv", "vendor"]`,对每个目录若主仓库存在且 Worktree 不存在则用 `java.nio.file.Files.createSymbolicLink(...)` 创建;失败只警告
- **F10**: 创建后设置 D——按项目根 `.worktreeinclude` 复制被忽略但运行需要的文件;读取 `.worktreeinclude` 每行为 glob 模式(支持 `*.env` 这种),用 `git -C <repoRoot> ls-files --others --ignored --exclude-standard --directory` 列出所有忽略文件,匹配模式后逐个复制到 Worktree 对应路径;文件不存在 / 模式无匹配只警告

### 进入与退出- **F11**: `manager.enter(name)`——
  - 1. `lock.lock()`,从 `active` 取 wt(不存在抛异常)
  - 2. 取当前 `Path.of("").toAbsolutePath()` 与当前 Git HEAD/branch 作为原状态
  - 3. 构造 `WorktreeSession(originalCwd, wt.path, name, originalBranch, originalHeadCommit, sessionId=UUID.randomUUID().toString(), hookBased=false)`
  - 4. 写 `currentSession = session`,持久化到 `sessionFile`(原子写——先写 tmp 再 `Files.move(..., ATOMIC_MOVE)`)
  - 5. 返回 session
  - **不动 JVM 进程当前目录**
- **F12**: `manager.exit(name, action, opts)`——`ExitAction` 取 `KEEP` / `REMOVE`;`ExitOptions(boolean discardChanges)`
  - 1. `lock.lock()`,取 `active.get(name)` 与 `currentSession`(若 `currentSession.worktreeName().equals(name) == false` 抛异常,只能退当前)
  - 2. 若 `action=REMOVE` 且 `!opts.discardChanges()`,调 `hasWorktreeChanges(wt.path, wt.headCommit)`,有变更则抛 `WorktreeHasChangesException`
  - 3. 记录 `session.originalCwd` 留作上层 UI 还原 cwd 时使用(JVM 进程当前目录本就没变)
  - 4. `currentSession = null`,持久化为 `null`(覆写 sessionFile 为空 JSON `null` 字符串)
  - 5. 若 `action=REMOVE`:`git worktree remove --force <wtPath>` → `Thread.sleep(100)` → `git branch -D <branchName>`;`active.remove(name)`
  - 6. 返回 `ExitReport(boolean removed, String path, String branch)`
- **F13**: `manager.remove(name, opts)`——独立 remove 入口,允许删除非当前 session 的 Worktree;变更保护同 F12
- **F14**: `manager.autoCleanup(name)`——
  - 1. 取 `active.get(name)`,`manual=true` 直接返回 `Kept` 报告
  - 2. `hasWorktreeChanges(wt.path, wt.headCommit)` 返回 false 走 `remove(name, new ExitOptions(true))`,报告 `kept=false`
  - 3. 有变更:`kept=true, path=wt.path, branch=wt.branch`
- **F15**: `hasWorktreeChanges(wtPath, baseCommit)` boolean——两件事:`git -C <wtPath> status --porcelain` 非空即有未提交;`git -C <wtPath> rev-list --count <baseCommit>..HEAD` >0 即有新增 commit;任一 git 命令本身出错 fail-closed 返回 true(宁可保留)

### explicit cwd 工具改造- **F16**: 在 `dev.mewcode.tool` 包定义 ctx key 与帮助函数(`ToolContext` 不可变上下文对象封装)——
  - `ToolContext.withCwd(ctx, dir)` 返回新 ctx 含 cwd
  - `ctx.cwd()` 返回 `Optional<Path>`
  - `ctx.resolvePath(p)`——若 p 是绝对路径直接返回;否则用 ctx cwd(优先)或 JVM 当前目录拼相对路径,返回绝对 `Path`
- **F17**: 改造 6 个核心工具支持 ctx cwd——
  - `ReadFileTool`、`WriteFileTool`、`EditFileTool`:用 `ctx.resolvePath` 解析 `path` 参数
  - `GlobTool`:用 `ctx.resolvePath` 解析 `path` 参数
  - `GrepTool`:同 `GlobTool`(参数名可能不同,按现有 schema)
  - `BashTool`:在 `ProcessBuilder` 上 `directory(ctx.resolvePath("").toFile())` 即 ctx cwd 或 JVM 当前目录
- **F18**: ctx cwd 注入点——
  - SubAgent isolation:worktree 启动时,在调 `runToCompletion` 前 `ctx = ctx.withCwd(wt.path())`
  - TUI `/worktree create` 后用户手动 `enter` 也注入到主 Agent 的下一次 Run 的 ctx(通过 tui 的 `runOnce` 入口)
- **F19**: 工具 Schema 不变——主 Agent 看到的工具列表与参数与 ch13 完全一致,ctx 注入不暴露 cwd 字段

### SubAgent 集成- **F20**: 扩展 `subagent.Definition` 增加 `String isolation` 字段;`Parser` 解析 frontmatter `isolation:` 字段,合法值 `""` / `"worktree"`,非法值 stderr 警告后回落到 `""`
- **F21**: 改造 `agent.AgentTool#execute`——当 `def.isolation().equals("worktree")` 时走 `executeWithWorktree` 分支:
  - 1. 用 `agent-a<7位随机 hex>` 作为 worktree name(规避同类型并发冲突)
  - 2. 调 `worktreeManager.create(name, "HEAD", false)` 创建临时 Worktree
  - 3. 构造 `worktreeNotice` 文本(F22)拼到 task 文本前
  - 4. `ctx = ctx.withCwd(wt.path())`
  - 5. 调 `subAgent.runToCompletion(ctx, subConv, taskWithNotice, events)`
  - 6. 跑完后调 `manager.autoCleanup(name)`,kept=true 时把 `\n[Worktree 保留在 <path>,分支 <branch>]` 追加到 finalText
  - 7. 返回 finalText 给主 Agent
- **F22**: `buildWorktreeNotice(parentCwd, wtPath)` 模板(实际内容大致如下,中文友好)——
  ```
  <worktree-context>
  你当前在一个独立的 Git Worktree 副本中工作,与父 Agent 隔离。
  - 父目录: <parentCwd>
  - 你的工作目录: <wtPath>
  - 父 Agent 提到的绝对路径基于父目录,你需要翻译成本地路径(替换前缀)再读写
  - 编辑文件前,必须先在本地 Worktree 重新 `read_file` 一次,避免使用过时内容
  </worktree-context>
  ```
- **F23**: 后台 SubAgent + isolation 协同——若 `background && isolation:worktree`,本期强制走前台路径(忽略 background 标志);后续章节再扩展异步路径

### TUI Slash 命令- **F24**: `/worktree create <slug>`——调 `manager.create(slug, "HEAD", true)` (`manual=true`),输出 Worktree path + branch
- **F25**: `/worktree list`——遍历 `manager.list()`,每行格式 `<name>  <path>  <branch>  [active?]`
- **F26**: `/worktree exit [--remove] [--discard]`——退出当前 session;`--remove` 时调 `exit(name, REMOVE, new ExitOptions(discard))`,`--discard` 跳过变更保护
- **F27**: `/worktree remove <slug> [--discard]`——直接调 `manager.remove(slug, ...)`
- **F28**: `/worktree enter <slug>`——调 `manager.enter(slug)`,把 ctx cwd 写到 TUI 的 `activeCwd` 字段,主 Agent 下次 Run 用这个 cwd 注入 ctx
- **F29**: slash 命令属于 `KindLocal`(只读)或 `KindUI`(改 TUI 状态),不进对话历史;输出走 `ui.println`

### 持久化与恢复- **F30**: `WorktreeSession` 用 Jackson(或同等 JSON 库)序列化,字段名采用小写下划线(`@JsonProperty`);原子写——先写 `<sessionFile>.tmp` 再 `Files.move(..., StandardCopyOption.ATOMIC_MOVE)`
- **F31**: mewcode 启动时(`new WorktreeManager` 内),读 `sessionFile` 反序列化;若文件内容为 `null` 或空,`currentSession=null`;若 `worktreePath` 不存在,清空文件并 `currentSession=null`(stderr 警告 "session worktree gone, cleared")
- **F32**: `--resume` (mewcode 现有恢复入口)读到已有 session 时,把 `activeCwd` 设置到 `session.worktreePath`,主 Agent 后续工具调用都按 explicit cwd 走

### 后台过期清理- **F33**: `manager.sweepStale(cutoff)` 返回 `List<String> removed`——
  - 1. 遍历 `worktreeDir` 子目录
  - 2. **第一层** 名字匹配正则 `^agent-a[0-9a-f]{7}$`(本期只识别 SubAgent 临时模式)
  - 3. **第二层** 目录 mtime > cutoff 跳过;`currentSession.worktreePath().equals(子目录)` 跳过
  - 4. **第三层** `hasWorktreeChanges(子目录, 该 wt 的 headCommit)` 为 true 跳过(fail-closed);额外跑 `git -C <子目录> rev-list --max-count=1 HEAD --not --remotes`,非空跳过(有未推送 commit 也保留)
  - 5. 通过三层的子目录调 `remove(name, new ExitOptions(true))`,记入 `removed`
- **F34**: mewcode 启动时跑一次 `Thread.startVirtualThread(() -> manager.sweepStale(Instant.now().minus(24, HOURS)))`(异步、后台执行),不阻塞启动

### .gitignore 更新- **F35**: 在项目根 `.gitignore` 追加 `.mewcode/worktrees/` 与 `.mewcode/worktree_session.json` 两行;mewcode 启动时若发现 `.gitignore` 不含这两行,**只警告不修改**(尊重用户配置)

## 非功能需求- **N1**: 主 Agent 看到的工具列表稳定——ctx 注入不改 schema,既有缓存不抖动
- **N2**: Worktree 创建后设置失败 (F7-F10) 不阻塞创建;主路径只在 git worktree add 本身失败时抛异常
- **N3**: Manager 所有状态变更受 `ReentrantLock lock` 保护;Worktree 内部 git 操作不持锁,避免长锁
- **N4**: 不使用 JVM 进程级 `chdir`(JVM 不支持也不应模拟);所有 cwd 行为通过 `ToolContext` 与 `ProcessBuilder.directory(...)` 实现
- **N5**: Worktree session 文件被破坏(非法 JSON)启动时只警告并清空,不阻断 mewcode 启动
- **N6**: 与 ch04~ch13 既有测试零破坏——`mvn test` 全绿
- **N7**: 中文友好——错误消息与命令输出全部中文(对齐 mewcode 其他模块风格)

## 不做的事

- Worktree 间的合并策略(交给上层 `git merge` / `git cherry-pick`)
- 跨 Worktree 代码同步、文件 watcher
- 多 Agent 并行编排 / Agent Team(下一章)
- 主 Agent 用专用 merge 工具(README 章末已说明)
- Plugin 来源的 Worktree 配置
- Windows 平台特殊支持(symlink 行为在 Windows 上不保证;本期 mewcode 以 macOS / Linux 为主)
- 跨 mewcode 进程实例的 Worktree 共享(同一仓库同一时刻只支持一个 mewcode 实例操作 worktree session)
- Worktree 内部 git 操作的 retry / exponential backoff(用一次性 `Thread.sleep(100)` 解决 lockfile 竞态即可)

## 验收标准- **AC1**: `WorktreeSlug.validate` 对 `"feature/a"` 通过,对 `"../etc"` / `".."` / `"a//b"` / `"a/b "` 拒绝
- **AC2**: `manager.create("alice", "HEAD", true)` 在 `.mewcode/worktrees/alice/` 下落地 Worktree,分支为 `worktree-alice`
- **AC3**: `manager.create("team/alice", "HEAD", true)` 在 `.mewcode/worktrees/team+alice/` 下落地,分支 `worktree-team+alice`
- **AC4**: 已存在 worktree 目录时再调 create 走快速恢复——不调 `git worktree add`,毫秒级返回(单测可断言 git 子进程未启动)
- **AC5**: 创建后设置 A——主仓库存在 `.mewcode/settings.local.yaml` 时,Worktree 内同位置出现该文件
- **AC6**: 创建后设置 B——主仓库 `.husky/` 存在时,Worktree 的 `.git/config` 含 `core.hooksPath`
- **AC7**: 创建后设置 C——主仓库有 `node_modules/` 时,Worktree 内是软链(`Files.isSymbolicLink(...)` 为 true)
- **AC8**: 创建后设置 D——主仓库有 `.worktreeinclude` 含 `*.env`,且主仓库存在被忽略的 `.env`,Worktree 内出现 `.env`
- **AC9**: `manager.enter(name)` **不**改变 JVM 当前目录 `Path.of("").toAbsolutePath()`;返回 session 含正确字段
- **AC10**: `manager.exit(name, REMOVE, new ExitOptions(false))` 当 Worktree 有未提交修改时,抛 `WorktreeHasChangesException`,Worktree 目录仍在
- **AC11**: `manager.exit(name, REMOVE, new ExitOptions(true))` 显式 discard 时,目录被删,分支被删
- **AC12**: `manager.autoCleanup(name)` 对 `manual=true` 直接 keep;对 `manual=false` 且无变更直接 remove
- **AC13**: 工具 `read_file` / `write_file` / `edit_file` / `bash` / `glob` / `grep` 在 ctx 注入 cwd 后,以 cwd 为基准解析相对路径(单测断言)
- **AC14**: `bash` 工具在 ctx cwd 注入下,`ProcessBuilder.directory()` 等于 cwd(单测 / 集成测试可断言)
- **AC15**: `subagent.Definition#isolation()` 为 `"worktree"` 时,`AgentTool#execute` 创建临时 Worktree、注入 worktree notice、传 ctx cwd、跑完后调 autoCleanup
- **AC16**: SubAgent + worktree 路径上,子 Agent 写文件不影响主 Agent 工作目录(集成测试或 tmux 实跑可观察)
- **AC17**: `/worktree create alice` slash 命令成功落地 Worktree,`/worktree list` 输出含 alice
- **AC18**: `/worktree exit --remove` 在 Worktree 有未提交修改时报错;加 `--discard` 后成功删除
- **AC19**: `manager.sweepStale(cutoff)` 只删命名匹配 `agent-a[0-9a-f]{7}` 的目录、跳过当前 session、跳过有变更或有未推送 commit 的目录
- **AC20**: `WorktreeSession` 持久化到 `.mewcode/worktree_session.json`,启动时读取;指向的 Worktree 目录被外部删除后,启动时清空 session 并 stderr 警告
- **AC21**: 项目编译无错误 (`mvn -q -DskipTests package`)、所有单元测试通过 (`mvn test`)、Spotless 检查通过 (`mvn spotless:check`)
- **AC22**: tmux 实跑——`mewcode` 启动 + 触发 `isolation:worktree` 子 Agent 改文件 + 验证主目录 `server.py`(若改的是 `server.py`)未变,Worktree 副本里 `server.py` 已变;Worktree 留盘 / 自动清理符合预期