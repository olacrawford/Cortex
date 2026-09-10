# Agent Team 端到端验证报告

> 环境：macOS tmux（200×50），JDK Temurin 21，独立工作区 `/tmp/cortex14-e2e`（git 仓库 + 真实 provider
> deepseek-v4-flash；`env -u TMUX` 启动 → BackendDetector 选中 in-process）。Leader 端 `~/.cortex/teams/`。
> 单测：`./gradlew test` 全绿（新增 TeamManagerTest / TeamMemberTest / MailboxTest / AgentNameRegistryTest /
> TaskStoreTest / BackendTest / SpawnTeammateTest / CoordinatorTest / TeamMailboxIngestorTest /
> ConfigLoaderTest.features / FilterTest.teammate 用例）。
>
> **范围说明（对齐 task.md 决议）**：Pane 后端（tmux/iterm2）的**队员子进程自治模式**（`--team-member` /
> TeamMemberRunner，F19a-F19b）本期未实现——后端检测、spawn/wake/kill 命令构造已实现并单测，
> spawn 时 pane 后端报错引导改用 in-process；Lead 邮箱自动唤醒开轮（F41b 的 beginAutonomousTurn）
> 简化为 reminder 注入 + 下一轮 Run 自然取出。以上两处延后已在 E2E-Report 与 AGENTS.md 注明。

## 实跑 1：TeamCreate（AC2 形态）

```
● TeamCreate({"teamName": "e2e", "backend": "in-process"})
  ⎿ {"teamName":"e2e","backend":"in-process","configPath":"/Users/ibupro/.cortex/teams/e2e/config.json"}
```

- `~/.cortex/teams/e2e/config.json` 落地，backend=in-process，lead 成员已注册 ✓
- `features:` 配置段解析验证（ConfigLoaderTest）+ 环境变量双锁 ✓

## 实跑 2：Team spawn（AC7/AC25）

```
● Agent({"teamName": "e2e", "subagent_type": "researcher", "name": "alice", …})
  ⎿ {"memberName":"alice","agentId":"task_137da87a83b1c","worktree":"…/.cortex/worktrees/team-e2e+alice",
     "backend":"in-process","paneId":""}
● TaskList ⎿ [{"id":"task_137da87a83b1c","last_activity":"SendMessage","name":"alice","status":"running",…}]
```

- 队员 Worktree 落地 `team-e2e+alice`（ch13 嵌套 slug）✓
- TaskList 可见 alice（running），注册表 name→agentId 寻址生效 ✓

## 实跑 3：队员 SendMessage → Lead 邮箱（AC12/G7）

alice 队员（带 teammate 上下文）调用 `SendMessage(to=lead)`：

```
LEAD MAIL: alice | TEXT | hello from alice | hello from teammate | read= True
LEAD MAIL: alice | TEXT | alice idle | agent task_137da87a83b1c finished work, … | read= True
```

- 队员上下文分派：SendMessage 走 Team 邮箱而非 ch13 续派 ✓（`to=lead` 寻址修复后验证）
- Lead 邮箱收到实质消息 + 空闲通知两条；watcher 1 秒内消费（read=true）✓（F41a/T30）

## 实跑 4：队员空闲 → isActive=false（AC17/T30）

```
members: [('lead', None), ('alice', False)]
```

`runToCompletion` 结束 → `handleTaskDone` 置 isActive=false + 写 idle 通知 ✓。

## 实跑 5：SendMessage 续写（AC18/T31）

alice2 因 maxTurns 终结（FAILED）后，Lead 调 `SendMessage(name=alice2)`：

```
  ⎿ {"task_id":"task_1369b71749774","status":"resumed"}
● TaskGet ⎿ {…"status":"running","tool_count":6,…}    ← 从 FAILED 回到 RUNNING
```

- F46 放宽后 FAILED 状态可续派（本轮同步修改 Manager.sendMessage：仅 RUNNING 拒绝）✓
- 续派后再次自然结束 → isActive=false 二次验证 ✓

## 实跑 6：/team 命令（AC26）

```
⊕ /team list → e2e  in-process  2 成员  [1/2] 活跃
⊕ /team info e2e → 团队: e2e（e2e） 后端: in-process + 成员明细
⊕ /team delete e2e → ✖ 团队 e2e 仍有活跃成员，拒绝删除（可加 force 强制删除）
```

变更保护：lead 成员活跃时拒绝删除 ✓（force 路径由单测覆盖）。

## 实跑 7：Coordinator Mode（AC21/AC30）

`features.coordinator_mode: true` + `CORTEX_COORDINATOR_MODE=1` 启动：

- 状态栏出现 `DEFAULT [COORDINATOR]` ✓
- 模型提示 write_file → **write_file 不在其工具定义中**（allowedTools 收窄），模型改走 bash 亦被
  default 模式审批拦截；拒绝后确认 `test.txt` 未创建 ✓
- 权限闸顺序修正（本轮实修）：白名单外的幻觉调用现在在权限判定**之前**即回灌
  「工具未授权」错误，不再触发审批弹窗

## 实跑修复（E2E 发现的真实缺陷）

1. **TeamCreate 漏注册 lead 成员**（F5-6）：`to=lead` 寻址依赖 members 里的 lead 条目，缺失导致
   队员 `SendMessage(to=lead)` 报「团队缺少 lead 成员」。修复：create 时注册 lead 并持久化。
2. **权限闸时序**：allowedTools 闸原在权限判定之后，Coordinator 收窄下幻觉调用会先弹审批窗。
   修复：闸前移到 `engine.check` 之前，越权调用直接回灌错误。
3. **续派状态放宽**（F46）：FAILED 任务原不可续派；放宽为仅 RUNNING 拒绝（任务仍运行中）。

## 单测覆盖（补充说明）

- 跨进程 reload（F19c）：单测模拟「另一进程写盘带 alice」→ `setMemberActive("alice",…)` 走 reload
  成功而非静默 no-op ✓
- 邮箱并发（AC14）：10 条 virtual thread 并发写同一 agentId，全量落盘无丢失 ✓
- stale 锁（AC15）：mtime 拨回 11 秒 → write 清锁重抢成功 ✓
- Pane 后端：spawn 报错引导 + 命令构造断言（tmux split/new-session、wake send-keys、kill-pane）✓
