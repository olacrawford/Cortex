# Worktree 隔离 Checklist

> 每一项通过运行代码或观察行为来验证,聚焦系统行为。

## 实现完整性

### worktree 包

- [ ] `com.mewcode.worktree` 包存在且编译通过(验证:`./gradlew -q -pl . -am -DskipTests compile`)
- [ ] `WorktreeSlug.validate` 对合法/非法 case 行为符合 spec F1(验证:`./gradlew -q test -Dtest=WorktreeSlugTest`)
- [ ] `WorktreeSlug.flatten("team/alice").equals("team+alice")`(验证:同上)
- [ ] `WorktreeSession` JSON 序列化/反序列化字段名为下划线小写(`@JsonProperty`)(验证:`./gradlew -q test -Dtest=SessionStoreTest`)
- [ ] `SessionStore.save` 原子写——失败前不破坏既有文件;`save(path, null)` 写入 `null`(验证:同上)
- [ ] `GitHelper.gitProcess` 设置 `GIT_TERMINAL_PROMPT=0` + `GIT_ASKPASS=""`、stdin redirect 自 `/dev/null`(验证:`./gradlew -q test -Dtest=GitHelperTest`)
- [ ] `GitHelper.hasWorktreeChanges` 在临时 git 仓库内:无修改返回 false;改一个文件返回 true;git 命令出错 fail-closed 返回 true(验证:同上)
- [ ] `GitHelper.resolveHeadShaFromFS` 在真实 worktree 路径下返回 commit SHA(验证:`./gradlew -q test -Dtest=GitHelperTest#resolveHead`)
- [ ] `new WorktreeManager(repoRoot)` 校验 repoRoot 是 git 仓库;非 git 目录抛 `IOException`(验证:`./gradlew -q test -Dtest=WorktreeManagerTest`)
- [ ] `new WorktreeManager` 加载已存在的 session 文件;指向不存在目录的 session 自动清空(验证:同上)
- [ ] `manager.create("alice", "HEAD", true)` 在 `.mewcode/worktrees/alice/` 下落地 + 分支 `worktree-alice`(验证:`./gradlew -q test -Dtest=WorktreeCreateTest`)
- [ ] `manager.create("team/alice", ...)` 落地 `.mewcode/worktrees/team+alice/` + 分支 `worktree-team+alice`(验证:同上)
- [ ] `manager.create` 目录已存在时走快速恢复(不调 git;`active` 立即就绪)(验证:同上)
- [ ] `manager.create` 已 active 名字时再 create 抛异常(验证:同上)
- [ ] 创建后设置 A——`.mewcode/settings.local.yaml` 被复制到 Worktree(验证:同上,需在测试 fixture 准备文件)
- [ ] 创建后设置 B——主仓 `.husky/` 存在时 Worktree git config 含 core.hooksPath(验证:`./gradlew -q test -Dtest=PostCreationSetupTest#hooks`)
- [ ] 创建后设置 C——主仓 node_modules 存在时 Worktree 内为软链(`Files.isSymbolicLink(...)` 为 true)(验证:`./gradlew -q test -Dtest=PostCreationSetupTest#symlink`)
- [ ] 创建后设置 D——主仓 `.worktreeinclude` 模式命中的 ignored 文件被复制到 Worktree(验证:`./gradlew -q test -Dtest=PostCreationSetupTest#includeIgnored`)
- [ ] `manager.enter(name)` 不改变 JVM 当前目录 `Path.of("").toAbsolutePath()`,返回 session 含 `originalCwd`/`worktreePath`/`sessionId` 等字段(验证:`./gradlew -q test -Dtest=WorktreeLifecycleTest#enter`)
- [ ] `manager.enter` 持久化 session 到 `.mewcode/worktree_session.json`(验证:同上)
- [ ] `manager.exit(name, REMOVE, new ExitOptions(false))` 有变更时抛 `WorktreeHasChangesException`,Worktree 目录仍在(验证:`./gradlew -q test -Dtest=WorktreeLifecycleTest#exit`)
- [ ] `manager.exit(name, REMOVE, new ExitOptions(true))` 成功删除 Worktree + 分支;session 文件被清空(验证:同上)
- [ ] `manager.exit` 返回的 `ExitReport.path()` 等于原 wt.path()(让上层 UI 还原 activeCwd)(验证:同上)
- [ ] `manager.remove(name, opts)` 与 `exit` 的 REMOVE 分支一致,但允许非当前 session(验证:同上)
- [ ] `manager.autoCleanup` 对 `manual=true` 直接 `kept=true`(验证:`./gradlew -q test -Dtest=WorktreeLifecycleTest#autoCleanup`)
- [ ] `manager.autoCleanup` 无变更时 remove 并返回 `kept=false`;有变更返回 `kept=true`(验证:同上)
- [ ] `manager.sweepStale` 第一层只识别 `agent-a[0-9a-f]{7}` 模式;手动命名跳过(验证:`./gradlew -q test -Dtest=WorktreeSweepTest`)
- [ ] `manager.sweepStale` 跳过当前 session 的目录(验证:同上)
- [ ] `manager.sweepStale` 有未提交修改 / 未推送 commit 的目录跳过(fail-closed)(验证:同上)
- [ ] `WorktreeNaming.randomAgentName` 返回形如 `agent-a[0-9a-f]{7}` 的字符串(验证:`./gradlew -q test -Dtest=WorktreeNamingTest`)

### tool 包 ctx 改造

- [ ] `.withCwd` / `cwd()` / `resolvePath` 三方法存在(验证:`./gradlew -q test -Dtest=Test`)
- [ ] `resolvePath` 对绝对路径直接返回;对相对路径用 ctx cwd 或 JVM 当前目录拼接(验证:同上)
- [ ] `read_file(path="a.txt")` 在 `ctx.withCwd(tmpDir)` 下读 `tmpDir/a.txt`(验证:`./gradlew -q test -Dtest=ReadFileToolCwdTest`)
- [ ] `write_file(path="a.txt")` + ctx cwd 同上(验证:同上)
- [ ] `edit_file(path="a.txt")` + ctx cwd 同上(验证:同上)
- [ ] `bash(command="pwd")` + ctx cwd 输出 cwd 路径(验证:`./gradlew -q test -Dtest=BashToolCwdTest`)
- [ ] `glob(pattern="*.txt")` + ctx cwd 在 cwd 内搜索(验证:`./gradlew -q test -Dtest=GlobToolCwdTest`)
- [ ] `Grep` + ctx cwd 同上(验证:`./gradlew -q test -Dtest=GrepToolCwdTest`)
- [ ] 工具 schema 不变——`schema()` 不含新字段(验证:对比 ch13 测试快照,或断言 keys)

### subagent 包扩展

- [ ] `subagent.Definition` 含 `String isolation()` accessor(验证:`./gradlew -q test -Dtest=DefinitionTest`)
- [ ] `Parser` 正确解析 `isolation: worktree`(验证:`./gradlew -q test -Dtest=ParserTest#isolation`)
- [ ] 非法 `isolation` 值时 stderr 警告并回落 `""`(验证:同上)
- [ ] 既有定义不写 isolation 时 `def.isolation().equals("")`(验证:同上)

### agent 包扩展

- [ ] `AgentTool` 含 `WorktreeManager wtMgr` 字段;构造器签名末尾接收 `wtMgr`(验证:`./gradlew -q -DskipTests compile`)
- [ ] `AgentWorktreeRunner.executeWithWorktree` 调用 `manager.create` + `autoCleanup`,期间通过 ctx 传 `wt.path()`(验证:`./gradlew -q test -Dtest=AgentWorktreeRunnerTest`)
- [ ] `AgentWorktreeRunner.buildWorktreeNotice` 输出含 `<worktree-context>` 标签 + 父目录 + 工作目录(验证:同上)
- [ ] `AgentTool#execute` 在 `def.isolation().equals("worktree")` 时走 worktree 分支(验证:同上)
- [ ] `AgentTool#execute` 在 `wtMgr==null` 且 isolation=worktree 时返回 `ToolResult.error`(验证:同上)
- [ ] `AgentTool#execute` 在 isolation=worktree + background=true 时强制走前台路径(验证:同上)

### command 包扩展

- [ ] `command.WorktreeSummary` 与 `WorktreeAccessor` 接口存在(验证:`./gradlew -q -DskipTests compile`)
- [ ] `Ui` 接口加 `worktreeAccessor()` 方法;`NopUi` 返回 null(验证:同上)
- [ ] `/worktree` 命令被注册,`Builtins.lookup("worktree")` 命中(验证:`./gradlew -q test -Dtest=BuiltinsTest#registered`)
- [ ] `WorktreeCommand.handle` 分发子命令 create/list/enter/exit/remove(验证:`./gradlew -q test -Dtest=WorktreeCommandTest`)
- [ ] `WorktreeCommand.handle` 在 `ui.worktreeAccessor()` 返回 null 时报错(验证:同上)

### tui 包扩展

- [ ] `MewCodeModel` 含 `WorktreeManager worktreeMgr` 与 `Path activeCwd` 字段(验证:`./gradlew -q -DskipTests compile`)
- [ ] `MewCodeModel.Builder` 接收 `worktreeMgr` 参数;启动时若 `manager.currentSession()` 非 null,设 `activeCwd = Path.of(session.worktreePath())`(验证:`./gradlew -q test -Dtest=MewCodeModelStartupTest`)
- [ ] 主 Agent run 前 ctx 注入 cwd——可通过日志或 mock Provider 断言 tool 调用收到的 cwd(验证:同上)
- [ ] `TuiWorktreeAccessor` 实现 `WorktreeAccessor` 接口(验证:`./gradlew shadowJar`)

### main 接入

- [ ] `MewCode.java` 构造 `WorktreeManager`,失败 stderr 警告 + 降级(验证:`./gradlew shadowJar`)
- [ ] `new AgentTool(...)` 调用末尾追加 `worktreeMgr`(验证:同上)
- [ ] `new MewCodeModel(` 链上有 `.worktreeMgr(...)`(验证:同上)
- [ ] 启动时通过 `Thread.startVirtualThread` 跑 `sweepStale`(验证:`Grep` 检查代码)
- [ ] `.gitignore` 追加 `.mewcode/worktrees/` 与 `.mewcode/worktree_session.json`(验证:`git check-ignore .mewcode/worktrees/test`)

## 集成

- [ ] `subagent.Definition#isolation` + `agent.AgentTool` 协同——`isolation:worktree` 的 SubAgent 启动时自动创建 Worktree(验证:`AgentWorktreeRunnerTest` 通过)
- [ ] tool ctx `withCwd` + `AgentTool.executeWithWorktree` 协同——SubAgent 在 Worktree 内的工具调用使用 `wt.path()` 作为 cwd(验证:集成测试,在临时 git repo 跑一个 mock subagent)
- [ ] 主 Agent 工具列表稳定——5 个核心工具 + Agent + TaskList + TaskGet + TaskStop + SendMessage + worktree 不暴露新工具(验证:工具数计数)
- [ ] worktree 包 + subagent 包 + agent 包 + command 包 + tui 包之间无导入循环(验证:`./gradlew shadowJar`)

## 编译与测试

- [ ] 项目编译无错误:`./gradlew shadowJar`
- [ ] 所有单元测试通过:`./gradlew -q test`
- [ ] （代码风格由 IDE 保证） 检查通过:`./gradlew -q spotless:check`

## 端到端场景(tmux 实跑)

每个场景在 tmux 内启动一个 mewcode 实例完成,验证可视化行为。

**通用预置:**
- 当前目录 `cd /Users/codemelo/mewcode`
- 已执行 `./gradlew shadowJar`

### 场景 1:isolation:worktree 子 Agent 修改文件不影响主目录

**预置:** 创建项目级自定义 Agent:

```
.mewcode/agents/worktree-writer.md
---
name: worktree-writer
description: 在 Worktree 内写文件的测试 Agent
permissionMode: dontAsk
maxTurns: 5
isolation: worktree
---

你是一个测试 Agent。当用户让你写文件时,直接用 write_file 工具写,不要询问。
```

并准备一个主目录文件 `echo "MAIN" > scratch_ch14.txt`(测试前 git status 干净,这个文件未跟踪)。

**步骤:**
- [ ] tmux 启动:`tmux new-session -d -s ch14 -x 200 -y 50 "java -jar build/libs/mewcode.jar"`
- [ ] 输入:「用 Agent 工具调 subagent_type=worktree-writer,prompt 是『把 scratch_ch14.txt 的内容覆盖为 SUBAGENT,只用 write_file 工具』」
- [ ] 子 Agent 跑动,scrollback 出现 `Agent(...)` 行
- [ ] tool_result 中末尾含 `[Worktree 保留: .mewcode/worktrees/agent-a... ,分支 worktree-agent-a...]`(因为有未提交修改,autoCleanup 保留)
- [ ] **主目录** `cat scratch_ch14.txt` 仍为 `MAIN`(验证主目录未被改)
- [ ] **Worktree 副本** `cat .mewcode/worktrees/agent-a*/scratch_ch14.txt` 为 `SUBAGENT`
- [ ] tmux 截屏断言:`tmux capture-pane -p -t ch14 | grep -i "worktree"`
- [ ] 清理:`rm scratch_ch14.txt`,删除残留 worktree:mewcode 内 `/worktree remove agent-a... --discard`(或 `git worktree remove --force` 手动清)
- [ ] `tmux kill-session -t ch14`

### 场景 2:isolation:worktree 子 Agent 无变更时自动清理

**预置:** 同场景 1 的 `worktree-writer.md`(已存在)。

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 输入:「用 Agent 工具调 subagent_type=worktree-writer,prompt 是『用 read_file 读 README.md 头 5 行,然后用 30 字总结』」
- [ ] 子 Agent 跑动,tool_result 是总结文本
- [ ] tool_result **不含**「Worktree 保留」字样(因为读文件不产生修改,autoCleanup 直接清理)
- [ ] `ls .mewcode/worktrees/` 不存在与本次任务对应的 `agent-a*` 目录(已被 autoCleanup 删除)
- [ ] `tmux kill-session`

### 场景 3:`/worktree create` + `/worktree list` 手动管理

**预置:** 当前在 main 分支,git 工作区干净。

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 输入:`/worktree create demo-feature`
- [ ] scrollback 显示 `Worktree 已创建: .mewcode/worktrees/demo-feature (分支 worktree-demo-feature)`
- [ ] 输入:`/worktree list`
- [ ] scrollback 显示一行含 `demo-feature` 的列表项,标记 `[manual]`(`manual=true`)
- [ ] tmux 外验证:`ls .mewcode/worktrees/demo-feature/` 含正常 mewcode 仓库内容;`git -C .mewcode/worktrees/demo-feature branch` 显示在 `worktree-demo-feature`
- [ ] 清理:输入 `/worktree remove demo-feature --discard`
- [ ] 验证 `.mewcode/worktrees/demo-feature` 已不存在
- [ ] `tmux kill-session`

### 场景 4:`/worktree exit` 变更保护

**预置:** 同场景 3 创建好 `demo-feature`。

**步骤:**
- [ ] 手动写一个修改:`echo "modified" > .mewcode/worktrees/demo-feature/test.txt`
- [ ] tmux 启动 mewcode
- [ ] 输入:`/worktree enter demo-feature`
- [ ] 输入:`/worktree exit --remove` (不加 `--discard`)
- [ ] scrollback 显示错误 `worktree has uncommitted changes or new commits`(或对应中文消息)
- [ ] 输入:`/worktree exit --remove --discard`
- [ ] scrollback 显示成功消息,worktree 已被删除
- [ ] `tmux kill-session`

### 场景 5:explicit cwd——`/worktree enter` 后工具调用用 worktree 路径

**预置:** 创建 worktree 并准备测试文件。

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 输入:`/worktree create cwd-test`
- [ ] 在 tmux 外:`echo "in-worktree-only" > .mewcode/worktrees/cwd-test/probe.txt`(主目录无 probe.txt)
- [ ] tmux 内输入:`/worktree enter cwd-test`
- [ ] 输入:「用 read_file 读 probe.txt」
- [ ] 主 Agent 调 `ReadFile` 工具(path=probe.txt 相对路径)
- [ ] tool_result 应为 `in-worktree-only`(证明 cwd 解析到 worktree 路径)
- [ ] 输入:`/worktree exit`,activeCwd 恢复为 JVM 当前目录
- [ ] 再输入:「用 read_file 读 probe.txt」
- [ ] tool_result 报「无法访问文件 probe.txt」(主目录没这文件)
- [ ] 清理:`/worktree remove cwd-test --discard`
- [ ] `tmux kill-session`

### 场景 6:Slug 校验阻止路径遍历

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 输入:`/worktree create ../etc`
- [ ] scrollback 显示错误,含「invalid」或「拒绝」(不创建 `.mewcode/etc/` 或类似)
- [ ] 输入:`/worktree create ..`
- [ ] 同样错误
- [ ] 输入:`/worktree create normal_one`
- [ ] 成功创建
- [ ] 清理:`/worktree remove normal_one --discard`
- [ ] `tmux kill-session`