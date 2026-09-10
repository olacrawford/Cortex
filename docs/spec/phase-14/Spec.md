# Agent Team Spec## 背景

ch13 SubAgent 把任务从单 Agent 委派给子 Agent,实现了消息、权限账本、文件读缓存与 token 计数的隔离;ch14 Worktree 给每个子 Agent 配上独立工作目录,文件系统层并发也安全。但这两章合起来仍是「星型」拓扑——所有子 Agent 只能与主 Agent 通信,子 Agent 之间没有横向通道;主 Agent 既要决策、又要中转,既是大脑也是邮局。对「同时重构四个模块」「三个角度查同一个 bug」这类持续性、需要互相交流的工作,星型结构的瓶颈很明显。

本章把 cortex 从星型升级到「网状」:

- 主 Agent 创建 **Team** 后升任 **Lead**,Team 是一个长期存在的小组对象,记名称、负责人、成员花名册、持久化位置
- 每个 **队员**(Teammate)是一个独立的 Agent 实例,有自己的 Conversation、自己的 Worktree
- 三种执行后端 `tmux` / `iterm2` / `in-process` 覆盖不同环境;按优先级一次性自动检测,启动后不静默回退
- 队员之间通过**共享任务列表**与**邮箱**直接通信,不必经过 Lead 中转;协作工具仅在 Team 上下文出现
- 队员可暂停可续写,自然停下后 session 留盘,Lead 调 `SendMessage` 会从磁盘恢复后继续指派
- Lead 可选启用 **Coordinator Mode**(独立于 Team,但典型场景一起用),双锁机制下剥夺 Write/Edit 工具,只保留调度、读类操作与 shell(用于 git merge)
- 收敛阶段由 Lead 用 Bash 跑 `git merge` 逐个合各队员的 worktree 分支,冲突由 LLM 推理解决,搞不定就 `git merge --abort` 保留 worktree 上报用户

cortex 现有相关基础设施:
- ch13 `task.Manager` 已支持后台任务管理 + `sendMessage` 续派 + `AgentNameRegistry` (`byName` 字段已是 name → id 映射);本章扩展为多 Team 寻址
- ch13 `agent.AgentTool.execute` 已是子 Agent 启动入口,本章新增 `teamName` 参数走 Team spawn 分支
- ch13 工具过滤 `tool.applyAgentToolFilter` 已支持多层防线;本章新增 Team 专属白名单(协作工具)与 Coordinator Mode 白名单
- ch14 `worktree.Manager` 已支持嵌套 slug(`team/alice` → `.cortex/worktrees/team+alice/`),本章直接复用做队员 worktree(slug 形式 `team-<teamName>/<member>`)
- ch12 session 持久化(`.cortex/sessions/<id>/conversation.jsonl`)按对话粒度落盘;本章给每个队员单独申请一个 session,队员 stop 不删 session,SendMessage 续派时通过 session 反序列化 Conversation
- ch10 `dev.cortex.command` slash 命令系统,本章新增 `/team` 系列
- ch07 `permission` 已支持 `plan` 模式,本章给 `planModeRequired` 队员的 Plan 提交-Lead 审批工作流套用同一引擎

本章**只做**到「Lead 多人协作 + Plan 审批 + Coordinator 收敛」。跨进程跨机器分布式团队、队员之间实时流式通信、复杂任务依赖约束(优先级 / deadline)、Windows 平台 iTerm2 适配均不在范围内。

## 目标- **G1**: 提供 `Team` 与 `TeamManager`——Team 封装小组生命周期(name、leadAgentId、members、configPath);Manager 在单 cortex 进程内管理多个 Team(典型场景同时只有一个活跃 Team)
- **G2**: 提供 `TeamCreate` 工具——主 Agent 调用即创建 Team、调 `detectBackend` 确定后端、写 `~/.cortex/teams/<sanitizedName>/config.json`、把 Lead 注册成第一个成员;同名团队自动后缀 `-2` / `-3` 避免冲突
- **G3**: 扩展 `Agent` 工具——增加 `teamName` 可选参数,非空时走 Team spawn 分支:加载定义 → 创建队员 Worktree → 注入协作工具 → 按后端分流 spawn → 注册到 `AgentNameRegistry` → 写入 `team.members`
- **G4**: 提供 `TeamDelete` 工具——确认所有成员 `isActive=false` 后,删队员 worktree + 删 team 目录,Lead 退出团队;有活跃成员时拒绝删除
- **G5**: 三种执行后端 `tmux` / `iterm2` / `in-process`,统一抽象 `Backend` 接口;`detectBackend` 按 `$TMUX → $TERM_PROGRAM=iTerm.app && which it2 → which tmux → in-process` 优先级一次性决定,不做运行时回退
- **G6**: 队员注入 5 个协作工具 `TaskCreate` / `TaskGet` / `TaskList` / `TaskUpdate`(后者支持 `addBlocks` / `addBlockedBy` 依赖字段) / `SendMessage`;主 Agent 与普通 SubAgent 看不到这些工具
- **G7**: `SendMessage` 寻址支持 `to="<name>"`、`to="<agentId>"`、`to="*"` 广播三种;通过 `AgentNameRegistry` 解析 name → agentId,写邮箱;Tmux/iTerm2 后端额外通过 `send-keys` 唤醒目标 pane
- **G8**: 邮箱文件并发安全——每个收件人独占一个 lock 文件(`StandardOpenOption.CREATE_NEW`),抢锁失败按 5-100ms 随机抖动重试,最多 10 次;持锁超过 10 秒视为 stale 直接清掉;消息文件 read-modify-write,走 `Files.move(...,ATOMIC_MOVE)` 原子替换
- **G9**: 三种结构化消息——纯文本(必带 5-10 词 `summary`)、`shutdown_request` / `shutdown_response`(优雅退出协商)、`plan_approval_response`(Plan 审批回复,只允许 Lead 发送);全部走同一 SendMessage 入口,以 `type` 字段分流
- **G10**: 队员收到的未读消息在下一轮 Agent Loop 开头被读出,以 `<incoming-messages>` system reminder 形式注入到 LLM 输入;读后批量标记为 read
- **G11**: 队员 spawn 两种路径——指定 `subagentType` 走定义式(从空白对话起步)、留空走 Fork 路径(继承 Lead 完整对话历史);Fork 路径受 `FORK_TEAMMATE` feature flag 控制,默认关闭
- **G12**: 队员 `runToCompletion` 结束后自动通知 Lead——团队 config 里把该成员 `isActive=false`、Lead 邮箱收到 `idle_notification`;队员的 Conversation 已通过 ch12 Writer 实时写入 session 文件
- **G13**: 队员续写——Lead 调 `SendMessage(to="alice", message="…")`,系统检测 alice 已 stop 时,从 ch12 session 反序列化 Conversation、新建一条 virtual thread 走 `runToCompletion(initialMessage=newMessage)`;Conv 沿用历史
- **G14**: `planModeRequired:true` 的队员被 spawn 时强制以 plan 模式起步——计划生成后通过 SendMessage 发给 Lead,Lead 用 `plan_approval_response` 回复 approve 或 reject;approve 时队员权限模式切到 Lead 的当前模式继续执行,reject 时队员按 feedback 调整后重新提交
- **G15**: Coordinator Mode 独立于 Team——`Coordinator.isEnabled() = feature(COORDINATOR_MODE) && envTruthy(MEWCODE_COORDINATOR_MODE)`,两把锁全开才生效;开启后 Lead 工具集收窄到 `Agent / TeamCreate / TeamDelete / TaskCreate / TaskGet / TaskList / TaskUpdate / SendMessage / read_file / glob / grep / bash`(剥夺 `write_file` / `edit_file`),并注入 coordinator 系统提示词引导 Research / Synthesis / Implementation / Verification 四阶段
- **G16**: 收敛全部由 LLM 推理驱动——Lead 用 Bash 跑 `git merge worktree-team-<team>+<member> --no-ff -m "merge: <member>"` 逐个合,冲突由 Lead 用 Read / Edit / Bash 自行解决;搞不定就 `git merge --abort`,保留队员 worktree,把冲突上下文上报给用户
- **G17**: 提供 TUI slash 命令 `/team list` / `/team info <name>` / `/team delete <name>` / `/team kill <member>`,辅助用户人工介入
- **G18**: 与 ch04~ch14 既有功能协同——主 Agent 平时(未 TeamCreate)看到的工具列表不变;协作工具仅在 Team 上下文出现;ch13 后台任务 / AdoptRunning / SendMessage 续派路径保留,Team 队员的续派复用同一套底层 `TaskManager`

## 功能需求### Team 数据结构与 Manager- **F1**: `Team` 字段——`name`(原始名)、`sanitizedName`(经 `sanitize` 处理后用于路径)、`leadAgentId`、`members List<TeammateInfo>`、`configDir`(`<homeDir>/.cortex/teams/<sanitizedName>/`)、`configPath`(`<configDir>/config.json`)、`createdAt Instant`、`backend BackendType`
- **F2**: `TeammateInfo` 字段——`name`(Lead 分配的队员名,Team 内唯一)、`agentId`(对应 `BackgroundTask.id`)、`agentType`(使用的 subagent 定义名;Fork 路径下为 `""`)、`model`(覆盖,空表 inherit)、`worktreePath`(绝对路径)、`branch`(对应 worktree 分支名)、`backendType`(可 per-member 不同)、`paneId`(tmux pane / iterm2 split id,in-process 为空)、`isActive Boolean`(`null` 或 `true` 表活跃,`false` 表空闲;终止后直接从 `members` 移除)、`planModeRequired boolean`、`sessionDir`(队员独立 session 目录绝对路径)
- **F3**: `TeamManager` 字段——`lock ReentrantLock`、`teams Map<String,Team>`(按 `sanitizedName` 索引)、`homeDir`(`System.getProperty("user.home")`)、`worktreeManager`、`taskManager`、`registry AgentNameRegistry`
- **F4**: `TeamManager(Path homeDir, WorktreeManager wt, TaskManager taskMgr, AgentNameRegistry reg)`——校验 `<homeDir>/.cortex/teams/` 可写;扫描该目录还原 `teams` map(每个子目录读一次 `config.json`,跳过解析失败的并 stderr 警告)
- **F5**: `TeamManager.create(name, agentType)`——
  1. `sanitized = sanitize(name)`(只保留 `[a-zA-Z0-9._-]`,其他替换为 `-`,首尾去 `-`,空字符串拒绝)
  2. 同名冲突时在 `sanitized` 后追加 `-2` / `-3` 直到唯一
  3. 创建 `configDir`,落 `config.json`(原子写)
  4. 调 `detectBackend()` 写入 `team.backend`
  5. 取当前 Lead Agent id(本期 Lead = 主 Agent,固定 `"lead"`)
  6. 把 Lead 注册成第一个成员(`new TeammateInfo("lead","lead", null, ...)`,`isActive=null`)
  7. 加入 `teams` map,返回 Team
- **F6**: `TeamManager.get(name)`——按 sanitized name 查询,返回 `Optional<Team>`
- **F7**: `TeamManager.delete(name, force)`——
  1. 取 Team;不存在抛 `TeamNotFoundException`
  2. 非 force 时若有 `member.isActive != Boolean.FALSE`(包括 null 和 true)抛 `TeamHasActiveMembersException`
  3. 逐个删队员 Worktree(调 `worktreeManager.remove(name, new RemoveOptions(true))`,失败只警告不中断)
  4. 删队员 session 目录(`Files.walk(...).forEach(Files::delete)`,失败只警告)
  5. 删 `configDir`
  6. 从 `teams` map 移除
- **F8**: `Team.addMember(TeammateInfo info)`——校验 name 在 Team 内唯一;加入 `members`;持久化 `config.json`(原子写——先写 `.tmp` 再 `Files.move(...,ATOMIC_MOVE)`)
- **F9**: `Team.setMemberActive(name, active)`——更新 `isActive`,持久化
- **F10**: `Team.removeMember(name)`——从 `members` 移除,持久化

### 后端检测与抽象- **F11**: `BackendType` 枚举,取值 `TMUX` / `ITERM2` / `IN_PROCESS`,带 `wireValue()` 返回 `"tmux"` / `"iterm2"` / `"in-process"`
- **F12**: `Backend` 接口——
  ```java
  public interface Backend {
      BackendType type();
      // spawn 在后端启动一个新队员;返回 PaneID(in-process 返回空)。
      // 对 Pane 后端,spawn 会执行 split-window / it2 split + send-keys 启动 CLI。
      // 对 in-process 后端,spawn 在同进程起一条 virtual thread 跑 runToCompletion。
      SpawnResult spawn(SpawnRequest req) throws IOException;
      // wake 用于消息到达时唤醒目标 pane。in-process 后端为 no-op。
      void wake(String paneId, String agentId) throws IOException;
      // kill 终止 pane(Pane 后端)或 cancel virtual thread(in-process)。
      void kill(String paneId, String agentId) throws IOException;
  }

  public record SpawnResult(String paneId, String agentId) {}
  ```
- **F13**: `SpawnRequest`(record)字段——`teamName`、`memberName`、`agentId`、`worktreePath`、`sessionDir`、`agentType`、`model`、`initialPrompt`、`planModeRequired`、`subAgent Object`(in-process 用,实际类型 `Agent`,用 Object 避免反向依赖)、`conv Object`(`Conversation`)、`taskManager Object`(`TaskManager`)
  - 对 Pane 后端(tmux / iterm2),`initialPrompt` **不**走命令行——在 `backend.spawn` 调用前由 `TeamManager.spawnTeammate` 预写入 alice 的 mailbox(类型 `text`,from `lead`),子进程启动后读 mailbox 自然拿到。这样避免长 prompt 在命令行里 shell-quote 的边界问题。
- **F14**: `Backend.detect()`——按以下优先级一次性决定:
  1. `System.getenv("TMUX") != null` → `TMUX`
  2. `"iTerm.app".equals(System.getenv("TERM_PROGRAM"))` && PATH 中存在 `it2` → `ITERM2`
  3. PATH 中存在 `tmux` → `TMUX`(外部 spawn 新 session)
  4. 否则 → `IN_PROCESS`

### tmux 后端- **F15**: `TmuxBackend` 实现 `Backend` 接口
  - `spawn`:`tmux split-window -h -P -F "#{pane_id}" -- <cmd>`(横向 split,-P 打印 pane id,-F 指定格式);`cmd` 为 `cortex --team-member --team <teamName> --member <memberName> --agent-id <agentId> --session-dir <sessionDir> --worktree <worktreePath> [--agent-type <type>] [--model <model>] [--plan-mode]`
  - `--agent-id` 是关键:Lead spawn 时已生成的 agentId 直接传给子进程,子进程不需要读 Lead 还没写完的 `config.json` 找自己
  - `wake`:`tmux send-keys -t <paneId> "" Enter`(回车触发子进程 stdin scanner 读到一行,立刻去 mailbox 轮询;in-process 后端无此动作)
  - `kill`:`tmux kill-pane -t <paneId>`(忽略 pane 不存在错误)
- **F16**: 若当前在 tmux 会话外但本机有 tmux,spawn 走 `tmux new-session -d`(detached 新 session);若失败回落到错误而非 in-process(不静默回退)

### iterm2 后端- **F17**: `Iterm2Backend` 实现 `Backend` 接口
  - `spawn`:`it2 split --new-pane --command "<cmd>"`,`<cmd>` 与 F15 同构(含 `--agent-id`);通过 `it2` CLI 解析输出取 pane id
  - `wake`:`it2 send-text --pane <paneId> ""`(空文本即唤醒)
  - `kill`:`it2 close-pane --pane <paneId>`

### in-process 后端- **F18**: `InProcessBackend` 实现 `Backend` 接口
  - `spawn`:复用 `TaskManager.launch`——创建带 `withCwd(worktreePath)` 的子 Agent,在 virtual thread 里跑 `runToCompletion`;返回空 `paneId`,内部用 `BackgroundTask.id` 关联
  - `wake`:no-op(同进程,下一轮 Loop 自动读邮箱)
  - `kill`:调 `TaskManager.stop(agentId)`
- **F19**: in-process 后端的队员**只允许同步子 Agent**——其 `Agent` 工具看不到 `teamName` 参数(`teamName` 被拦截);后台子 Agent 也禁用(过滤 `runInBackground=true`)

### Pane 后端子进程的 team-member 模式- **F19a**: `cortex --team-member` 在 Pane 后端被 spawn 的 cortex 子进程**不启动 TUI**,而是跑一个自治循环(`dev.cortex.cli.TeamMemberRunner` 的 `run` 方法):
  1. 从 CLI 解析 `--team / --member / --agent-id / --session-dir / --worktree / --agent-type / --model / --plan-mode`(用 picocli 或 Apache Commons CLI 解析,本项目选 picocli `info.picocli:picocli`)
  2. `System.setProperty("user.dir", workTree)` + `Path.of(workTree).toAbsolutePath()` 作为后续所有 IO 的根;让该进程的 `Path.of("").toAbsolutePath()` 与权限沙箱根都指到 worktree
  3. 构造**单独的** `TeamManager`、provider、registry、permission engine、hook engine(完整复用 Lead wire 代码,但不构造 TUI)
  4. 构造队员 `Agent`,设 `dontAsk=true`(子进程无 TUI 接 ApprovalRequest)、注入 `<team-context>` reminder、用 `setCtxDecorator` 注入 `TeammateContext`(含 mailbox client)
  5. 启动 stdin scanner virtual thread:任何来自 tmux send-keys 的回车都推到 `wakeQueue`(`SynchronousQueue<Object>` 或 `BlockingQueue` size=1),触发立刻去 mailbox 轮询(0~2s 内响应)
  6. 进入主循环:
     - 读 `mailbox.readUnread(agentId)`
     - 空 → 阻塞 `wakeQueue.poll(2, SECONDS)` 兜底轮询
     - 有未读:`text` 拼成 task,`plan_approval_response(approve=true)` 触发 `setPermissionMode(DEFAULT)` + 续派 prompt,`shutdown_request` 触发优雅退出
     - 调 `agent.runToCompletion(conv, task, eventConsumer)` 让队员跑到底
     - 完成后:写 `summary="<name> idle"` 到 Lead mailbox,再 `Team.setMemberActive(name, false)`
     - 检测到 mailbox 目录已被删除(Lead 调用 `/team delete`)→ 优雅退出
- **F19b**: 该自治循环的最小事件转 stdout 打印:`TextEvent` 直接 `System.out.println`、`ToolEvent` 打 `● tool(args)` 行、`DoneEvent` 打分隔横线、错误打 stderr。pane 内 UX 是只读的「日志流」,不接受用户输入(任何回车都被 stdin scanner 消费做 Wake 信号)
- **F19c**: 跨进程 `config.json` 写入并发:Lead 与子进程是不同进程,各持一份内存中的 Team 对象。`Team.addMember` 与 `Team.setMemberActive` 在持锁后**先从磁盘 reload `members` 字段**再修改+原子 save(`reloadFromDiskLocked`)。否则会出现「子进程内存看不到自己,setMemberActive 静默 no-op」的丢更新问题

### TeamCreate 工具- **F20**: 工具名 `TeamCreate`,参数 schema:
  - `teamName`(string,必填):团队名,经 sanitize 后做 `Team.sanitizedName`
  - `description`(string,可选):团队描述,写入 `config.json` 的 `description` 字段
  - `agentType`(string,可选):本期保留位,实际不使用
- **F21**: `TeamCreate.execute`——
  1. 解析参数
  2. 调 `TeamManager.create(name, agentType)` 创建 Team
  3. 返回 JSON `{"teamName":"<sanitized>","backend":"<type>","configPath":"<path>"}`
  4. Lead 创建 Team 后保持原有工具集(非 Coordinator Mode 下不剥夺工具)

### TeamDelete 工具- **F22**: 工具名 `TeamDelete`,参数 `teamName`(必填)、`force`(可选 boolean)
- **F23**: `TeamDelete.execute`——调 `TeamManager.delete(name, force)`,返回成功/失败消息

### Agent 工具扩展 (teamName)- **F24**: `Agent` 工具参数 schema 新增字段:
  - `teamName`(string,可选):非空时走 Team spawn 分支
- **F25**: 当 `teamName` 非空,`Agent.execute` 走 Team 分支:
  1. 校验 `teamName` 对应的 Team 存在(`TeamManager.get`),否则报错
  2. 校验当前调用者权限:
     - 主 Agent / Lead → 允许
     - in-process 队员调 Team spawn → 拒绝(抛 `InProcessTeammateNoSpawnException`)
     - Pane 队员可以调(README:Pane 队员拥有完整 Agent 工具),但 `teamName` 参数被屏蔽(队员不能往 Team 加人,只 Lead 在 Coordinator Mode 或普通 Lead 调用时可以)
  3. 加载 `SubAgentDefinition`(指定 `subagentType` 走 Catalog;留空且 `FORK_TEAMMATE` 开启走 Fork 定义;留空且 flag 关闭则用 `general-purpose`)
  4. 调 `worktreeManager.create("team-"+sanitized+"/"+memberName, "HEAD", false)` 创建 Worktree
  5. 申请新 session 目录(复用 `session` 包接口),作为 `sessionDir`
  6. 构造 in-process 子 Agent(若后端为 in-process)或仅构造 SpawnRequest(若 Pane 后端);把协作工具注入到子 Agent 的 allowed tools 集合
  7. 注入队员系统提示词附录(F39)
  8. 注入 `<team-context>` initial system reminder 到子 Agent Conv
  9. **若是 Pane 后端**,在 `backend.spawn` 之前把 `initialPrompt` 作为 `text` 消息(`from=lead, summary=initial task`)预写入 alice 的 mailbox(F13);in-process 后端不需要,`initialPrompt` 直接作为 `TaskManager.launch` 的 task 参数
  10. 调 `Backend.spawn(req)` spawn,记 `paneId`
  11. 注册到 `AgentNameRegistry`:`memberName → agentId`
  12. 构造 `TeammateInfo` 加入 `team.members`,持久化(F19c 的 reload-before-modify 兜底)
  13. 返回 JSON `{"memberName":"<name>","agentId":"<id>","worktree":"<path>","backend":"<type>","paneId":"<id 或空>"}`

### 协作工具- **F26**: `TaskCreate` 工具——参数 `title`(必填)、`description`(可选)、`assignee`(可选,队员名)、`blockedBy`(可选 `List<String>`,任务 id);返回新建 `taskId`(`task_<6位 hex>`);写入 Team 的 `tasks.json`(原子)
- **F27**: `TaskGet` 工具——参数 `taskId`,返回任务详情
- **F28**: `TaskList` 工具——参数可选 `status` 过滤(`pending`/`in_progress`/`completed`/`blocked`);返回任务数组,带依赖关系标注(`blockedBy`、`blocks`、是否 `isReady`(无未完成 blocker))
- **F29**: `TaskUpdate` 工具——参数 `taskId`(必填)、`title`(可选)、`description`(可选)、`status`(可选)、`assignee`(可选)、`addBlocks`(可选 `List<String>`)、`addBlockedBy`(可选 `List<String>`)、`removeBlocks` / `removeBlockedBy`(可选 `List<String>`);更新后持久化
- **F30**: `tasks.json` 结构:
  ```json
  {
    "tasks": [
      {
        "id": "task_a1b2c3",
        "title": "...",
        "description": "...",
        "status": "pending",
        "assignee": "alice",
        "blockedBy": ["task_xxx"],
        "blocks": ["task_yyy"],
        "createdAt": 1234567890,
        "updatedAt": 1234567890
      }
    ]
  }
  ```
  写入走 `<teamConfigDir>/tasks.json`,read-modify-write,文件锁 `tasks.lock`(同邮箱 lock 机制)

### SendMessage 工具与邮箱- **F31**: `SendMessage` 工具——参数:
  - `to`(string,必填):队员名 / agentId / `"*"` 广播
  - `summary`(string,纯文本消息时必填,5-10 词)
  - `message`(string,可选,纯文本消息体)
  - `type`(string,可选,默认 `"text"`):取值 `"text"` / `"shutdown_request"` / `"shutdown_response"` / `"plan_approval_response"`
  - `payload`(object,可选):结构化消息的载荷(如 `shutdown_response` 的 `{approve, reason}`)
- **F32**: 邮箱文件路径——`<teamConfigDir>/mailbox/<agentId>.json`,结构:
  ```json
  {
    "messages": [
      {
        "from": "lead",
        "to": "alice",
        "type": "text",
        "summary": "interface change",
        "content": "...",
        "payload": null,
        "timestamp": 1234567890,
        "read": false
      }
    ]
  }
  ```
- **F33**: `Mailbox` 提供 `write(agentId, msg)` / `read(agentId)` / `markRead(agentId, indices)` 接口
  - `write`:抢 `<teamConfigDir>/mailbox/<agentId>.lock`(`Files.newOutputStream(..., StandardOpenOption.CREATE_NEW)`),失败 5-100ms 随机抖动重试 10 次;持锁超 10 秒视为 stale(`Files.getLastModifiedTime` 判定)直接删 lock 重试;成功后 read-modify-write,`Files.move(tmp, target, ATOMIC_MOVE)` 原子替换
  - 广播 `to="*"` 时,write 对 Team 内除发件人外所有成员的 mailbox 各 write 一次
- **F34**: `SendMessage.execute`——
  1. 校验调用者在 Team 内
  2. 解析 `to`:若 `"*"` 走广播;否则通过 `AgentNameRegistry.resolve(to)` 取 agentId(name 优先,失败按 agentId 直查);解析不到报错
  3. `plan_approval_response` 仅 Lead 可发,否则报错
  4. `shutdown_response` 只能发给 Lead,否则报错
  5. 调 `Mailbox.write`
  6. 取目标的 `backendType` 与 `paneId`,若是 Pane 后端调 `backend.wake(paneId, agentId)`
  7. 若目标 agentId 已 stop(in-process 后端):触发续写(F45)
  8. 返回 `{"deliveredTo":["<agentId>"],"timestamp":<ts>}`

### Agent 名称注册表- **F35**: `AgentNameRegistry` 字段——`lock ReentrantLock`、`byName Map<String,String>`(name → agentId)、`byId Map<String,String>`(agentId → name,反查)
- **F36**: 接口 `register(name, agentId)`、`unregister(name)`、`resolve(nameOrId)` 返回 `Optional<String>`、`nameOf(agentId)` 返回 `Optional<String>`
- **F37**: 注册时机——`Agent` 工具 spawn 队员时(F25 step 11);`AgentTool` 的 `name` 参数非空时(ch13 已有,本章统一这套 registry,替换 `TaskManager.byName` 的内部 map)
- **F38**: 命名冲突——后注册的覆盖前注册的(README 称「弱引用,后启动覆盖前面的弱引用」)

### 队员系统提示词附录- **F39**: 在子 Agent 的 systemPrompt 后追加(若 spawn 进 Team)以下文本(无变量):
  ```
  IMPORTANT: You are running as an agent in a team.
  Just writing a response in text is not visible to others
  on your team - you MUST use the SendMessage tool.
  The user interacts primarily with the team lead.
  Your work is coordinated through the task system
  and teammate messaging.
  ```
- **F39a**: 所有 Team 队员(三种后端共有)一律以 `dontAsk=true` 启动,**覆盖角色定义里的 `permissionMode`**。理由:队员没有可交互的 TUI 接 `ApprovalRequest`(in-process 走 TaskManager 聚合事件不响应、Pane 子进程更没有 TUI),Ask 工具会无人应答地永远阻塞。队员的安全边界由 allowed 工具集 + Worktree 隔离 + Plan 模式控制,不靠逐次 ask 弹窗(子进程没人在看)。
- **F40**: 在 spawn 时把 `<team-context>` 注入子 Conv 的首条 system reminder:
  ```
  <team-context>
  team: <teamName>
  你的成员名: <memberName>
  你的 agentId: <agentId>
  worktree 目录: <worktreePath>
  当前团队成员: <name1>(<role1>), <name2>(<role2>) ...
  </team-context>
  ```

### 邮箱读取与消息注入- **F41**: 子 Agent 的 Loop 在每轮请求 LLM **之前**先调 `Mailbox.read(agentId)`;若有未读消息,构造 `<incoming-messages>` system reminder 追加到本轮请求的 systemReminders,然后调 `markRead`
- **F41a**: Lead 侧不通过 ctx hook 自动读 mailbox(Lead 没有 `TeammateContext`),而是由 TUI 在初始化时启动后台 virtual thread `consumeLeadMail`(实现于 `dev.cortex.tui.LeadMailWatcher`):
  - 每秒调 `TeamManager.pollLeadMailboxes()`,遍历所有 Team 的 `<configDir>/mailbox/lead.json` 读未读消息,标 read,返回 `List<LeadMessage>`
  - 把这批消息渲染成 `<team-update>` reminder(与 `<incoming-messages>` 不同,Lead 视角语义更清晰;消息内容截断上限 8000 字符,允许队员的完整报告完整透传),调 `runtime.appendReminders(...)` 推到 `pendingReminders`
  - **同时**往 `leadMailQueue`(`LinkedBlockingQueue` capacity=1)`offer` 一个信号(非阻塞,buffer=1 合并掉重复)
  - Lead 下一轮 Run 迭代头部 `buildReminder` 自动取出。**Lead 即便正在长 Run 中也能中途惊醒**——下一个 LLM 调用前就会看到队员更新
  - 这是 Pane 后端队员通知 Lead 的关键路径:in-process 队员还有 `TaskManager.subscribeDone` → TUI `<task-notification>` 的额外路径,但 Pane 队员只能靠 mailbox + 本机制
- **F41b**: Lead idle 时的自动续推。TUI 通过 `LeadMailWaiter`(订阅 `Flow.Publisher`)阻塞在 `leadMailQueue` 上,收到信号后通过 GUI thread 提交 `LeadMailEvent`:
  - 若 `model.state == SessionState.IDLE`,调 `beginAutonomousTurn`:合成一条 user 消息 `"[team-update] 队员发来新消息,请按 Coordinator 流程处理..."` 加入对话历史(用户在 scrollback 也看得见,清楚是系统通知触发而非自己输入),然后走 `beginTurn` 启 Run
  - 若 `model.state` 非 idle(`STREAMING`/`APPROVING`):reminder 已经在 `pendingReminders` 里,Lead 当前 Run 的下一轮迭代头部自然取出,不需要主动 wake
  - 末尾 re-arm `LeadMailWaiter` 让后续信号也能接住
  - 这避免了「队员都 idle 了,Lead 在 IDLE 等用户输入,reminder 静默积累没人取」的卡死场景——这正是 ch15 协作 UX 的关键
- **F42**: `<incoming-messages>` 格式:
  ```
  <incoming-messages>
  收到 N 条新消息:
  [1] 来自 <from>(type=<type>,ts=<时间>): <summary>
      <content 前 200 字>
  [2] ...
  </incoming-messages>
  ```
- **F43**: 收到 `shutdown_request` 时,队员可在下一轮自主选择回复 `shutdown_response(approve=true)` 然后停止,或 `approve=false` 拒绝并附 reason(LLM 决策,不强制)
- **F44**: 收到 `plan_approval_response(approve=true)` 时,队员的权限模式自动切换到 Lead 当前模式(从 Team config 取);`approve=false` 时队员根据 `feedback` 调整重新发 Plan

### 队员空闲与续写- **F45**: 队员 `runToCompletion` 自然结束时(`TaskManager.runTask` 完成路径):
  1. 调 `Team.setMemberActive(memberName, false)`
  2. 给 Lead 邮箱写一条 `idleNotification`(`type="text", summary="<member> idle", content="agent <id> finished work, available for new tasks"`)
- **F46**: SendMessage 检测到目标 agentId 已 stop 且为 in-process 队员(`BackgroundTask.status` 不是 `RUNNING`):
  1. 从 `TeammateInfo.sessionDir` 反序列化 Conversation(`Session.load`)
  2. 调 `TaskManager.sendMessage(parentCtx, name, message)` 复用 ch13 已有续派接口
  3. `TaskManager.sendMessage` 重置 `status=RUNNING`,起新 virtual thread 跑 `runToCompletion(newMessage)`
  4. 续派前调 `Team.setMemberActive(memberName, true)`
- **F47**: Pane 后端队员的续写——SendMessage 写邮箱后,目标 pane 内的 cortex 实例下一轮 Loop 自然读到消息;若 pane 已死(`tmux list-panes` 查不到 `paneId`),报错让 Lead 决定是否重新 spawn

### Plan 审批工作流- **F48**: `Agent` 工具 spawn 队员时,若 `planModeRequired=true`(来自 SubAgentDefinition 的新字段或 spawn 参数),把子 Agent 的初始 `Permission.Mode` 设为 `PLAN`
- **F49**: 队员在 plan 模式下生成 Plan 后(通过常规 LLM 推理),用 `SendMessage(to="lead", type="text", summary="plan ready", content="<plan text>")` 发给 Lead——本期不强制结构化 Plan 类型(Lead 自行识别)
- **F50**: Lead 用 `SendMessage(to="<member>", type="plan_approval_response", payload={"approve":true|false,"feedback":"..."})` 回复
- **F51**: 队员收到 `plan_approval_response`:
  - `approve=true`:从 Team config 读 Lead 当前 `permissionMode`(本期固定 `DEFAULT`),切到该模式继续执行 plan
  - `approve=false`:把 `feedback` 当作新的用户消息加入对话,重新进入 plan 模式

### Coordinator Mode- **F52**: 提供 `Coordinator.isEnabled()` 静态方法:
  ```java
  public static boolean isEnabled(AppConfig cfg) {
      if (!Feature.has("COORDINATOR_MODE", cfg)) {
          return false;
      }
      return envTruthy(System.getenv("MEWCODE_COORDINATOR_MODE"));
  }
  ```
  `Feature.has` 通过 `dev.cortex.config` 读 `features.coordinatorMode` 字段;`envTruthy` 接受 `"1"` / `"true"` / `"yes"`(大小写不敏感)
- **F53**: Coordinator Mode 允许工具白名单常量:
  ```java
  public static final List<String> ALLOWED_TOOLS = List.of(
      "Agent", "TeamCreate", "TeamDelete",
      "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
      "SendMessage",
      "read_file", "glob", "grep", "bash"
  );
  ```
- **F54**: Lead 启动时(`tui` 主循环创建 Agent 后),若 `Coordinator.isEnabled(cfg)`:
  1. 把 Lead 的 allowed tools 设为 `Coordinator.ALLOWED_TOOLS`(调 `Agent.setAllowedTools` 已有接口)
  2. 在 systemPrompt 后追加 coordinator 提示词(F55)
  3. TUI 状态栏显示 `[COORDINATOR]` 模式标签
- **F55**: Coordinator 系统提示词追加在 systemPrompt 末尾,核心是「四阶段 + 派完不许自己干」纪律。最终文案见 [src/main/java/dev/cortex/coordinator/Coordinator.java:SYSTEM_PROMPT_SUFFIX](../../src/main/java/dev/cortex/coordinator/Coordinator.java),关键约束:
  - **派完队员就停手等汇报**:派出 Agent / SendMessage 后**禁止**立刻调 read_file / glob / grep / bash 自己探索;**禁止**用 sleep / TaskList 轮询凑时间。`TaskManager` 完成时自然推送 `<task-notification>` reminder,Lead 下一轮被唤醒后再继续
  - 唯一该做的事:发一行总结「已派 N 名队员探索 X,等结果」,让本轮结束
  - 允许自己用 read_file/glob/grep 的场景仅限:Research 第一次目标定位;Synthesis 阶段读**队员产出的报告文件**;Verification 阶段 git diff / git status 等收敛操作

  这段纪律是为了对抗「LLM 派完队员后等不及自己 glob 代码库重复劳动」的常见行为——纯 prompt 引导,不强制(LLM 偶尔仍会越线,弱模型尤甚)。

### 收敛阶段- **F56**: 收敛由 LLM 推理驱动,**不提供专门的 merge 工具**——Lead(无论是否 Coordinator Mode)在所有任务 `completed` 后,自主用 Bash 跑:
  ```bash
  git merge worktree-team-<sanitizedTeam>+<member> --no-ff -m "merge: <member>"
  ```
- **F57**: 冲突解决也由 Lead 推理——Lead 用 `read_file` 看冲突文件、`edit_file`(非 Coordinator Mode)或 `bash`(Coordinator Mode)写入解决方案、`bash` 跑 `git add` + `git commit`
- **F58**: 回滚——Lead 判断搞不定时,自主调 `bash` 跑 `git merge --abort`,然后给用户报告冲突文件 + 队员 worktree 路径;**不删队员 worktree**### TUI Slash 命令- **F59**: `/team list`——遍历 `TeamManager.teams`,每行 `<name>  <backend>  <memberCount> 成员  [<active>/<total>] 活跃`
- **F60**: `/team info <name>`——展示 Team 详情:配置路径、各成员的 name/agentId/backend/worktreePath/isActive/任务计数
- **F61**: `/team delete <name> [--force]`——调 `TeamManager.delete(name, force)`
- **F62**: `/team kill <member>`——查到 member 所属 Team,调对应 backend.kill,然后 `removeMember`

### 持久化与恢复- **F63**: `~/.cortex/teams/<sanitizedName>/config.json` 结构:
  ```json
  {
    "name": "...",
    "sanitizedName": "...",
    "leadAgentId": "lead",
    "backend": "tmux",
    "description": "",
    "createdAt": 1234567890,
    "members": [
      {
        "name": "alice",
        "agentId": "agent-a1b2c3d",
        "agentType": "worker",
        "model": "",
        "worktreePath": "/abs/path/.cortex/worktrees/team-foo+alice",
        "branch": "worktree-team-foo+alice",
        "backendType": "tmux",
        "paneId": "%5",
        "isActive": null,
        "planModeRequired": false,
        "sessionDir": "/abs/path/.cortex/sessions/<id>"
      }
    ]
  }
  ```
  所有写操作原子(先写 `.tmp` 再 `Files.move(..., ATOMIC_MOVE)`),受 `Team.lock` 保护。**跨进程**(Pane 后端)下,Lead 与子进程是不同进程的不同 Team 内存对象——`addMember` 与 `setMemberActive` 在持锁后**先 `reloadFromDiskLocked` 重读 disk members**再改写+ atomic save(F19c)
- **F64**: cortex 启动时(`new TeamManager(...)`)扫描所有 Team 目录:
  - 解析 `config.json`,失败的目录跳过并 stderr 警告
  - **不**自动恢复 in-process 队员(进程重启后 in-process 队员状态丢失,isActive 视为 false)
  - Pane 队员根据 `paneId` 探测后端是否仍在(`tmux has-session` / `it2 list-panes`),不在的 isActive 标 false
- **F65**: 队员 session 沿用 ch12 session 持久化机制,路径 `<projectRoot>/.cortex/sessions/<id>/conversation.jsonl`;Team 删除时一并删除
- **F66**: `TeamManager.delete(name, force=true)` 步骤(顺序重要):
  1. 持锁,校验 `force` 或全员 isActive=false
  2. 对每个非 lead 成员:用 `BackendFactory.create` 解析其 `backendType` 拿 `Backend` 实例,调 `backend.kill(paneId, agentId)` 杀掉 pane(tmux/iterm2)或 cancel virtual thread(in-process);Pane 子进程检测到 mailbox 目录消失会自行优雅退出兜底
  3. 调 `cleanupMemberResources` 删 session 目录与 worktree
  4. 递归删 `team.configDir` 整个 Team 目录
  5. 从 Manager 的 in-memory map 移除

## 非功能需求- **N1**: 主 Agent 平时(未 TeamCreate)看到的工具列表保持稳定——`TeamCreate` / `TeamDelete` 总是可见;`Agent` 工具的 `teamName` 参数对模型可见但仅在调用时校验
- **N2**: 协作工具(TaskCreate 等)仅在队员上下文出现,主 Agent 与普通 SubAgent 看不到——通过 `applyAgentToolFilter` 在 spawn 时收窄
- **N3**: 邮箱写入对所有后端共用一套并发安全机制(文件锁);in-process 多 virtual thread 写同一 mailbox 也由文件锁串行
- **N4**: 所有 Team 状态变更受 `Team.lock` 保护;Team 之间互不相关,各自一把锁;`TeamManager.lock` 仅保护 `teams` map
- **N5**: 后端 spawn / kill 调用不持 `Team.lock`(避免长锁);只在更新 `members` 时短暂持锁
- **N6**: 与 ch04~ch14 既有测试零破坏——`mvn test` 全绿
- **N7**: 中文友好——错误消息、TUI 输出、coordinator 提示词全部中文(对齐 cortex 其他模块风格);代码注释中文
- **N8**: Coordinator Mode 一旦启用,Lead 不可在运行时解锁(避免 LLM 被注入后自行解锁);取消的唯一方式是退出 cortex 重启
- **N9**: 权限沙箱(`dev.cortex.permission.Sandbox`)允许写入项目根**之外**的 `/tmp` 与 macOS 真实路径 `/private/tmp` 作为系统临时目录白名单。理由:工具脚本和队员经常需要 `/tmp` 做中转文件,严格限定在项目根内会导致大量正常用法被沙箱误杀。这一开放对 file-class 工具(read_file / write_file / edit_file)生效;bash 走 exec-class 权限,本来就不受沙箱约束

## 不做的事

- 跨 cortex 进程的 Team 共享(同一仓库同一时刻只支持一个 cortex 实例操作活跃 Team)
- 跨机器分布式 Team
- 队员之间实时流式通信(走 mailbox 文件 + 轮询/Wake,不走 socket)
- 复杂任务依赖约束(优先级、deadline、SLA)
- 任务自动分配(Lead 与队员都靠 LLM 推理领任务,系统不做调度)
- 队员的细粒度资源限额(token 上限、超时硬限制)
- Plan 审批的结构化 Plan 类型(本期 Plan 文本就是 SendMessage content,Lead 自行识别)
- Windows 平台特殊适配(iTerm2 后端仅 macOS;tmux 在 WSL 可用但不保证;本期以 macOS / Linux 为主)
- Coordinator Mode 的运行时解锁与重新进入
- 跨 Team 寻址(SendMessage 只能在同一 Team 内寻址)
- 插件来源的 Team 后端

## 验收标准- **AC1**: `new TeamManager(...)` 在 `~/.cortex/teams/` 不存在时自动创建;已有时正确扫描子目录还原 `teams` map
- **AC2**: `TeamManager.create("refactor auth", "")` 把 `"refactor auth"` sanitize 为 `"refactor-auth"`,在 `~/.cortex/teams/refactor-auth/config.json` 落地,`backend` 字段反映 `detectBackend` 结果
- **AC3**: 同名 Team 二次 create 自动后缀 `-2`,目录与 sanitizedName 都生效
- **AC4**: `TeamManager.delete(name, false)` 在有 `isActive != Boolean.FALSE` 成员时抛 `TeamHasActiveMembersException`,目录仍在
- **AC5**: `TeamManager.delete(name, true)` 删 Worktree、删 session 目录、删 configDir
- **AC6**: `Backend.detect()` 在 `$TMUX` 设置时返回 `TMUX`;未设但 `$TERM_PROGRAM=="iTerm.app"` 且 `it2` 可执行返回 `ITERM2`;都无但 `tmux` 二进制在 PATH 返回 `TMUX`;否则 `IN_PROCESS`
- **AC7**: `Agent` 工具带 `teamName="<existing>"` 时,在 `.cortex/worktrees/team-<sanitized>+<member>/` 落地 Worktree、调对应 `Backend.spawn` 并在 `team.members` 里出现该成员;不带 `teamName` 时维持 ch13 原行为
- **AC8**: in-process 后端队员的 `Agent` 工具调用 `teamName` 参数被拦截,抛 `InProcessTeammateNoSpawnException`
- **AC9**: 协作工具 `TaskCreate` / `TaskGet` / `TaskList` / `TaskUpdate` / `SendMessage` 在主 Agent 工具列表里**不**可见;在 Team 队员的工具列表里**可见**
- **AC10**: `TaskCreate` 落 `<teamConfigDir>/tasks.json`,`TaskUpdate(taskId, addBlockedBy=[id])` 正确更新双向 `blockedBy` / `blocks` 关系
- **AC11**: `TaskList(status="pending")` 返回的任务带 `isReady` 字段,反映其 `blockedBy` 是否全部 `completed`
- **AC12**: `SendMessage(to="alice", summary="hi", message="hello")` 在 `<teamConfigDir>/mailbox/<aliceAgentId>.json` 追加一条 unread 消息
- **AC13**: `SendMessage(to="*", ...)` 广播给 Team 内除发件人外所有成员;每人邮箱各得一条
- **AC14**: 并发 10 条 virtual thread 同时向同一 mailbox `write`,最终 10 条消息全部落盘且无丢失/无截断(集成测试)
- **AC15**: mailbox lock 文件 `Files.getLastModifiedTime` 超过 10 秒时,新的 write 会清掉旧 lock 并继续(集成测试)
- **AC16**: 队员 LLM 调用前,未读消息以 `<incoming-messages>` reminder 注入 systemReminders;调用后标记 read(单测断言)
- **AC17**: 队员 `runToCompletion` 自然结束后,`Team.config.json` 里该成员 `isActive=false`,Lead mailbox 收到 `summary="<member> idle"` 消息
- **AC18**: `SendMessage(to="alice", message="new task")` 当 alice 已 stop 时,从其 sessionDir 恢复 Conv 并续派(in-process 后端,`TaskManager` 状态从 CANCELLED/COMPLETED 回到 RUNNING)
- **AC19**: `Agent(teamName="t", subagentType="planner", planModeRequired=true, ...)` spawn 后,该队员初始权限模式为 `PLAN`
- **AC20**: Lead 发 `SendMessage(to="planner", type="plan_approval_response", payload={"approve":true})` 后,planner 队员下一轮权限模式切回 `DEFAULT`
- **AC21**: `Feature.has("COORDINATOR_MODE")=true` 且 `MEWCODE_COORDINATOR_MODE=1` 时,Lead 的 allowed tools 收窄为 `Coordinator.ALLOWED_TOOLS`,`write_file` / `edit_file` 不在其中;TUI 状态栏显示 `[COORDINATOR]`
- **AC22**: Coordinator Mode 关闭时,Lead 工具列表与 ch13 一致(`write_file` / `edit_file` 可见)
- **AC23**: tmux 后端 spawn 后,`tmux list-panes` 看到新 pane,pane 内 cortex 实例启动并连接到该 Team
- **AC24**: tmux 后端 `wake(paneId)` 通过 `tmux send-keys` 触发目标 pane 输入(集成测试可观察 pane 内容)
- **AC25**: in-process 后端队员与主 Agent 在同一进程内运行,共享 `TaskManager`,但有独立 `withCwd(worktreePath)`
- **AC26**: `/team list` slash 命令输出含所有 Team 摘要;`/team info <name>` 输出成员详情;`/team delete <name>` 调 `TeamManager.delete`
- **AC27**: 项目编译无错误 `mvn -q -DskipTests package`、所有单元测试通过 `mvn test`、`mvn spotbugs:check` 通过
- **AC28**: tmux 实跑(端到端):
  - 步骤 1:在 tmux 会话内启动 `cortex`
  - 步骤 2:输入 prompt 让主 Agent 调 `TeamCreate(teamName="demo")`,看到状态栏出现 team 标识,`~/.cortex/teams/demo/config.json` 落地
  - 步骤 3:Agent 调 `Agent(teamName="demo", subagentType="general-purpose", name="alice", prompt="在 worktree 里 echo hello > /tmp/test_alice.txt")`
  - 步骤 4:观察 tmux 新增 pane,pane 内出现 cortex 子实例;`.cortex/worktrees/team-demo+alice/` 目录创建;`/tmp/test_alice.txt` 文件创建,内容 `hello`
  - 步骤 5:`/team info demo` 显示 alice 成员
  - 步骤 6:Lead 调 `SendMessage(to="alice", summary="ping", message="再写一行 world 到 /tmp/test_alice.txt")`,观察 alice pane 被唤醒(send-keys 触发)、`/tmp/test_alice.txt` 多一行 `world`
  - 步骤 7:`/team delete demo --force`,worktree 和 team 目录清空
- **AC29**: in-process 后端实跑(端到端,不依赖 tmux):
  - 步骤 1:`unset TMUX TERM_PROGRAM`,启动 `cortex`(自动 fallback in-process)
  - 步骤 2:主 Agent 调 `TeamCreate("inproc")`,创建后端为 `in-process`
  - 步骤 3:`Agent(teamName="inproc", name="bob", prompt="...")` 在同进程 virtual thread 启动 bob
  - 步骤 4:bob 完成后 `Team.config.json` 标记 `isActive=false`、Lead mailbox 收到 idle 消息
  - 步骤 5:Lead 调 `SendMessage(to="bob", message="再做一件事")`,bob 从 sessionDir 恢复对话上下文继续
- **AC30**: Coordinator Mode 实跑——`MEWCODE_COORDINATOR_MODE=1` 启动 cortex,主 Agent 的 `write_file` 工具调用被拒绝(`isError=true`);`bash git merge` 调用允许