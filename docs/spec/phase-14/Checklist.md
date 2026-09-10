# Agent Team Checklist

> 每一项通过运行代码或观察行为来验证,聚焦系统行为而非实现细节。

## 实现完整性

- [x] `TeamManager` 可被实例化:`new TeamManager(home, root, wtMgr, taskMgr, nameReg)` 返回非 null(验证:`./gradlew -q -DskipTests compile`、跑单测)
- [x] `TeamManager.create("demo", "")` 在 `~/.cortex/teams/demo/config.json` 落地(验证:运行单测后检查文件存在)
- [x] `TeamManager.create("foo bar/baz", "")` sanitize 后路径为 `~/.cortex/teams/foo-bar-baz/`(验证:单测)
- [x] 同名 Team 第二次 create 自动后缀 `-2`(验证:单测)
- [x] `BackendType` 三个值齐全:`TMUX` / `ITERM2` / `IN_PROCESS`(验证:`./gradlew spotbugs:check` 通过 + 单测枚举)
- [x] `BackendDetector.detect()` 在 `$TMUX` 设置时返回 `TMUX`;两环境变量都清空时返回 `IN_PROCESS`(验证:注入 `EnvProvider` 接口的单测)
- [x] `Mailbox.write` + `Mailbox.read` 一进一出消息字段一致(验证:单测)
- [x] mailbox 文件锁在 stale 10 秒后能被新 writer 抢占(验证:单测制造 11 秒前的锁,断言能拿到)
- [x] `AgentNameRegistry.register("alice", "agent-123")` 后 `resolve("alice")` 返回 `Optional.of("agent-123")`,`nameOf("agent-123")` 返回 `Optional.of("alice")`(验证:单测)
- [x] `Store.create` 返回的 task id 形如 `task_<6位 hex>`(验证:单测)
- [x] `Store.update(id, new Patch(..., addBlockedBy=[other], ...))` 同时给 other 任务的 `blocks` 加上 id(验证:单测断言双向)
- [x] `Store.list(new Filter(Optional.of(PENDING)))` 返回结果带 `isReady` 字段,反映 blockedBy 是否全 completed(验证:单测)
- [x] `Coordinator.isEnabled` 在 feature flag 关 + 环境变量开时返回 false(验证:单测 4 种组合)
- [x] `Coordinator.allowedTools()` 含 `Bash` 不含 `WriteFile` / `EditFile`(验证:单测)
- [x] `Filter.applyAgentToolFilter(new FilterParams(true, ...))` 返回值含 `TaskCreate` / `SendMessage` 等 5 个协作工具(验证:单测)
- [x] `Filter.applyAgentToolFilter(new FilterParams(false, ...))` 不含这 5 个工具(验证:单测)
- [x] 7 个新工具注册到 registry 后,`ToolRegistry.definitions()` 输出含 `TeamCreate` / `TeamDelete` / `TaskCreate` / `TaskGet` / `TaskList` / `TaskUpdate` / `SendMessage`(验证:单测或启动后 `/status`)
- [x] `Team.addMember` 与 `Team.setMemberActive` 调用前先 `Persistence.reloadFromDiskLocked` 重读 disk(验证:跨进程并发写 disk 时不丢更新——单测制造「Lead 在 alice 子进程读完 config 之后才 addMember」的时序,alice 走 setMemberActive(false) 后回读 disk 应看到 isActive=false)

## 集成

- [x] `Agent` 工具不带 `teamName` 时走 ch13 原路径,行为不变(验证:`./gradlew test -Dtest=AgentToolTest` 全过)
- [x] `Agent` 工具带 `teamName="demo"` 时调 `teamHook.spawnTeammate`(验证:单测 mock teamHook,断言被调用)
- [x] `spawnTeammate` 创建 worktree 路径为 `.cortex/worktrees/team-demo+alice`(验证:单测/集成测试)
- [x] `spawnTeammate` 后 `team.members` 含 alice,持久化到 `config.json`(验证:单测)
- [x] in-process 后端的队员 ctx 含 TeammateContext,其 backendType=IN_PROCESS;该队员调用 `Agent(teamName=...)` 被拒绝并抛 `InProcessTeammateNoSpawnException`(验证:集成测试)
- [x] 队员 `Agent.run` 头部读取 mailbox 未读消息,以 `<incoming-messages>` reminder 注入到 LLM 输入(验证:单测,fake mailbox 写消息,捕获 Agent 构造的 prompt)
- [x] 队员收到 `plan_approval_response(approve=true)` 后 `agent.permissionMode` 切换到 DEFAULT(验证:单测 + tmux 实跑——见场景 4)
- [x] 队员 `runToCompletion` 结束触发 `onTaskDone` 回调,Team config 中该成员 `isActive=false`(验证:单测注册回调 + launch noop task)
- [x] 队员 idle 后 Lead mailbox 收到 `summary="<name> idle"` 消息(验证:单测/集成)
- [x] `SendMessage(to="alice", ...)` 在 alice 已 stop 且为 in-process 后端时,通过 `taskManager.sendMessage` 续派(验证:集成测试,断言 task status 回到 RUNNING);Pane 后端时通过 `backend.wake` 让子进程读 mailbox 自然续派
- [x] 所有 Team 队员一律 `dontAsk=true`(覆盖角色 frontmatter 的 permissionMode),子进程没人能应答 ApprovalRequest 不会卡死(验证:用 `permissionMode: default` 的角色派队员让她调 bash,实跑断言任务正常完成,而不是卡在 Ask)
- [x] Pane 后端 spawn 时 `initialPrompt` 通过预写入 mailbox(type=text, from=lead)送达,子进程不需要走 CLI 参数(验证:tmux 实跑,在 spawn 完检查 alice mailbox 已有一条 from=lead 的初始任务)
- [x] Pane 后端子进程命令行含 `--agent-id <id>` 参数(验证:看 `TmuxBackend.buildMemberCmd` 单测;tmux 实跑后 `ps auxww | grep team-member` 看实际命令)
- [x] Pane 后端的 `cortex --team-member` 子进程**不启动 TUI**,跑 `TeamMemberRunner.run` 自治循环——读 mailbox → `runToCompletion` → 通知 Lead idle → stdin wake 等下一轮(验证:tmux 实跑看 alice pane 显示纯文本日志流而非 cortex TUI 框)
- [x] Lead mailbox watcher 每秒轮询所有 Team 的 lead.json,把未读消息转 `<team-update>` reminder 推 pendingReminders + 给 `leadMailQueue` 发信号(验证:tmux 实跑后看 alice 发完 idle 通知 1 秒内 mailbox 的 unread 归零、read 累加)
- [x] Lead 在 `SessionState.IDLE` 时收到 `LeadMailEvent`,TUI 调 `beginAutonomousTurn` 合成 user 消息自动开新轮(验证:tmux 实跑——派完队员等他完成,Lead 不需要用户输入就自动出现 `[team-update]...` 行 + Synthesis 回复)
- [x] `/team list` 输出含 `~/.cortex/teams/` 下所有 Team(验证:TUI 实跑)
- [x] `/team delete demo --force` 调 `backend.kill` 杀 pane(tmux/iterm2)+ 清 worktree + 清 team 目录(验证:TUI 实跑后 `tmux list-panes` 只剩 Lead,worktree 与 team 目录都消失)
- [x] 沙箱开放 `/tmp` 与 `/private/tmp`(macOS 真实路径)作为白名单——write_file/edit_file 可写 `/tmp/foo.txt`,但 `/etc/passwd` 仍拒(验证:单测 `SandboxTest.testContains` 含两组用例)

## 编译与测试

- [x] `./gradlew shadowJar` 无错误(验证:命令退出码 0)
- [x] `./gradlew spotbugs:check` 无警告(验证:命令退出码 0)
- [x] `./gradlew test` 全部通过(验证:命令退出码 0)
- [x] `./gradlew compileJava` (代码风格由 IDE 保证) 通过——所有源文件符合 google-java-format(验证:无未格式化文件)

## 端到端场景(tmux 实跑)

> 这是本章的核心验收场景,必须在真实 tmux 会话内手动跑一遍。

**场景 1:tmux 后端,Team 全生命周期**

环境准备:
- macOS / Linux
- tmux 已安装
- JDK 21 安装
- 当前不在 cortex 进程内,准备开新 tmux 会话

步骤:
- [x] `tmux new-session -s ch15-test` 进入新 tmux 会话
- [x] `cd /path/to/cortex && ./gradlew shadowJar`(预编译,加快冷启动)
- [x] `java -jar build/libs/cortex.jar` 启动 TUI;启动消息显示一切正常,无 ch15 相关 error
- [x] 在 TUI 输入:「创建一个名为 demo 的团队」
  - 预期:Agent 调 `TeamCreate(teamName="demo")`;返回 `{"teamName":"demo","backend":"tmux","configPath":"..."}`
  - 验证:`ls ~/.cortex/teams/demo/config.json` 存在;`cat config.json` 中 `backend` 字段为 `tmux`
- [x] 在 TUI 输入:「派 alice 用 general-purpose 角色,在 worktree 里跑 `echo hello > /tmp/test_alice.txt && pwd > /tmp/test_alice_pwd.txt`」
  - 预期:Agent 调 `Agent(teamName="demo", subagentType="general-purpose", name="alice", prompt="...")`
  - 验证 a:tmux 自动 split 出右侧 pane(`tmux list-panes -F "#{pane_id} #{pane_current_command}"` 看到新 pane)
  - 验证 b:新 pane 内**显示自治循环日志流**(`[team-member] alice · team=demo · agent=... · cwd=...` 起始行 + Agent 工具调用打印,**不是** cortex TUI 框)
  - 验证 c:`ls /path/to/cortex/.cortex/worktrees/team-demo+alice/` 目录存在
  - 验证 d:等待 30 秒,`cat /tmp/test_alice.txt` 内容为 `hello`
  - 验证 e:`cat /tmp/test_alice_pwd.txt` 内容为 worktree 路径(`.../team-demo+alice`)
  - 验证 f:`cat ~/.cortex/teams/demo/config.json` 中 `members` 数组含 alice,`backendType="tmux"`,`paneId` 非空
  - 验证 g:`~/.cortex/teams/demo/mailbox/<aliceAgentId>.json` 中应已含一条 from=lead 的 text 消息——Pane 后端的 initialPrompt 预写入证据
- [x] 在 TUI 输入 `/team info demo`
  - 预期:输出含 alice 行,显示 worktree、paneId、isActive 状态
- [x] 在 TUI 输入:「给 alice 发消息,让她再写一行 world 到 /tmp/test_alice.txt」
  - 预期:Agent 调 `SendMessage(to="alice", summary="append world", message="...")`
  - 验证 a:alice pane 被唤醒(`tmux send-keys` 触发,pane 显示新内容)
  - 验证 b:30 秒内,`cat /tmp/test_alice.txt` 看到第二行 `world`
- [x] 等待 alice 任务自然结束(或在 TUI 输入 `/team kill alice` 终止)
  - 验证 a:`cat ~/.cortex/teams/demo/config.json` 中 alice 的 `isActive` 为 `false`(跨进程 reload 修复——alice 子进程的 `setMemberActive(false)` 必须真的反映到 disk;早期 bug 是静默 no-op)
  - 验证 b:Lead 的 mailbox(`cat ~/.cortex/teams/demo/mailbox/lead.json`)含一条 `summary` 含 `idle` 的消息,且 1-2 秒后该消息 `read=true`(watcher 已消费)
  - 验证 c:Lead 屏幕**不需要用户输入**自动出现 `● [team-update] 队员发来新消息...` 文本块 + 紧接的 Synthesis 回复(自动唤醒)
- [x] 在 TUI 输入 `/team delete demo --force`
  - 验证 a:`ls ~/.cortex/teams/` 无 `demo` 目录
  - 验证 b:`ls /path/to/cortex/.cortex/worktrees/` 无 `team-demo+alice`
  - 验证 c:`tmux list-panes` 只剩 Lead pane,alice 的 `%1` 被 `backend.kill` 干掉了

**场景 2:in-process 后端实跑**

环境准备:
- `unset TMUX TERM_PROGRAM`(确保 detect 选 IN_PROCESS)
- 在非 tmux 终端窗口内

步骤:
- [x] 启动 `java -jar build/libs/cortex.jar`(同会话已 unset 上述变量)
- [x] 在 TUI 输入:「创建 inproc 团队」
  - 验证:`cat ~/.cortex/teams/inproc/config.json` 中 `backend` 为 `in-process`
- [x] 在 TUI 输入:「派 bob 用 general-purpose,在 worktree 里 `echo step1 > /tmp/bob.txt`」
  - 验证:无新终端窗口/pane 出现(同进程 virtual thread)
  - 验证:`/tmp/bob.txt` 内容 `step1`
- [x] 等 bob 结束(`/team info inproc` 看 isActive=false)
- [x] 在 TUI 输入:「给 bob 发消息让他再加一行 step2」
  - 验证:`/tmp/bob.txt` 多一行 `step2`
  - 验证:`/team info inproc` 看 bob 在 active → idle 反复变化
- [x] `/team delete inproc --force` 清理

**场景 3:Coordinator Mode 实跑**

环境准备:
- `.cortex/config.yaml` 加 `features:\n  coordinator_mode: true`(snake_case,不是 camelCase)
- 设环境变量 `MEWCODE_COORDINATOR_MODE=1`

步骤:
- [x] `MEWCODE_COORDINATOR_MODE=1 java -jar build/libs/cortex.jar`
- [x] 观察 TUI 状态栏出现 `[COORDINATOR]` 标签
- [x] 在 TUI 输入:「写一个 hello world 到 /tmp/coord_test.txt」
  - 预期:`WriteFile` **不在 Lead 工具集**(被 `setAllowedTools` 剥夺),LLM 应该说「我没有 write_file 工具」并尝试用 bash 转写
  - 验证:`cat /tmp/coord_test.txt` 文件不存在(若用户拒掉 bash 的话)
- [x] 在 TUI 输入:「跑 `git status`」
  - 预期:Agent 调 `Bash`,工具正常执行(bash 在 Coordinator 白名单中)
  - 验证:输出含 git 状态信息
- [x] 在 TUI 输入:「派几个队员探索 cortex 的 dev/com.cortex/agent 和 dev/com.cortex/team」
  - 预期:Lead 调 Agent + SendMessage 派出队员后,**不**立刻调 read_file/glob/bash 自己探索(被 Coordinator system prompt 中的纪律段约束)
  - 验证:Lead 派完队员的回复应该是「等待汇报中」类似措辞;在队员发完 idle 消息前 Lead 屏幕没新工具调用

**场景 4:Plan 审批工作流**

环境准备:无特殊

步骤:
- [x] 准备一个角色定义 `~/.cortex/agents/planner.md`,frontmatter 含 `permissionMode: plan`,body 简述「先制定计划」
- [x] 启动 cortex,创建 team `plan-test`
- [x] 在 TUI 输入:「派 planner 用 planner 角色,在 worktree 制定 hello world 程序的实现计划」
  - 预期:planner 队员以 plan 模式起步,生成计划后通过 SendMessage 发给 Lead
  - 验证:Lead mailbox 含计划消息
- [x] 在 TUI 输入:「批准 planner 的计划」
  - 预期:Lead 调 `SendMessage(to="planner", type="plan_approval_response", payload={approve:true})`
  - 验证:planner 收到后切换权限模式,继续执行计划

## 失败回归

- [x] cortex 启动时 `~/.cortex/teams/` 不存在,自动创建,不报错
- [x] `~/.cortex/teams/<somename>/config.json` 内容损坏时,启动只 stderr 警告,跳过该 Team
- [x] 创建 Team 时若 disk 写失败(可手动 chmod 模拟),抛 IOException,不留半成品目录
- [x] mailbox 文件锁抢占冲突 10 次仍失败时,SendMessage 抛 IOException,不丢消息
- [x] tmux 后端在 `tmux split-window` 失败时(非 tmux 会话),抛错误,Team.members 不留半成品
- [x] 协作工具被主 Agent 误调用(主 Agent 工具列表本应不含)时,工具自己也返回 error 兜底