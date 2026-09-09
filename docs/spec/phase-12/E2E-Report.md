# SubAgent 机制 端到端验证报告

> 环境：macOS tmux（200×50），JDK Temurin 21，独立工作区 `/tmp/cortex12-e2e`（真实 provider 配置 deepseek-v4-flash）。
> 单测：`./gradlew test` 全绿（含阶段 12 新增 ParserTest / CatalogTest / FilterTest / ForkTest /
> RunToCompletionTest / AgentToolTest / AgentToolIntegrationTest / ManagerTest / ToolsTest）。
> 实跑期间发现并修复两个真实缺陷（见文末「实跑修复」）。

## 内置角色加载（启动期 stderr 观察通道）

启动无任何 stderr 警告——内置 3 角色（general-purpose / Explore / Plan）从 classpath 资源加载成功（N4）。

## 场景 1：定义式子 Agent（Explore）前台同步

输入「用 Explore 子 Agent 统计 doc.txt 文件的字节数」：

```
● Agent({"description": "统计 doc.txt 字节数", "subagent_type": "Explore", "prompt": "用只读方式统计…)
  ⎿ 12
● 12  (102s)
```

- 主 Agent 触发 Agent 工具（EXEC 类，弹审批），批准后前台跑动；
- tool_result = 子 Agent 末尾 assistant 文本（`12`），`git status` 等价验证（目录文件未变）✓（AC2）。

## 场景 2：Fork 子 Agent 后台执行（AC4/AC9）

输入「不指定 subagent_type，直接 Fork 一个后台子 Agent 统计 doc.txt 字节数」：

```
● Agent({"description": "后台统计 doc.txt 字节数", "prompt": "用只读方式统计…)
  ⎿ {"task_id":"task_11c9b440f6fe1","status":"async_launched"}
```

- Fork 强制后台、立即返回 task_id，主对话可继续（期间 TaskList/TaskGet 正常响应）✓；
- TaskGet 终态 `result:"Scope: doc.txt 的字节数为 12。"`——`Scope:` 前缀证明 Fork Boilerplate 注入生效（F23/AC4）✓。

## 场景 3：TaskList / TaskGet（AC13）

```
● TaskList({})
  ⎿ [{"id":"task_11c80193cc8d5","last_activity":"bash","name":"","status":"completed","tool_count":1},…]
● TaskGet({"task_id": "task_11c9b440f6fe1"})
  ⎿ {"end_time":"…","id":"…","last_activity":"bash","result":"Scope: …","start_time":"…","status":"completed",
     "tool_count":1,"usage":{…}}
```

字段齐全（id/name/status/tool_count/last_activity/usage/起止时间）✓。

## 场景 4：TaskStop 取消（AC14）

```
● TaskStop({"task_id": "task_11caf858369ab"})
  ⎿ {"task_id":"task_11caf858369ab","status":"cancellation_requested"}
```

随后模型在系统通知里确认「该任务已取消」——取消经 cancelToken 触发、
终态 CANCELLED、`<task-notification>` 注入均成立。stop 亦能解救阻塞在审批上的任务
（requestApproval 注册的 cancel 回调兜底 DENY_ONCE 解阻塞）✓。

## 场景 6：子 Agent 审批升级到主 TUI（AC8/F13）

Explore/general-purpose 子 Agent（default 权限模式）调用 bash 时，审批弹窗出现在主 TUI
（转发器置 pending → SubAgentApprovalMessage 唤醒重绘），用户三选一后子 Agent 继续：
「允许本次」→ `wc -c` 执行、tool_result 正确回灌 ✓。

## 场景 7：定义式子 Agent 看不到 Agent 工具

让 Explore 子 Agent「先再启动一个 Plan 子 Agent」：子 Agent 全程 15 次工具调用全是
read_file/glob/bash，从未产生嵌套 Agent 任务（主 Agent 观察结论同）——
Agent 工具不在其工具定义中，嵌套从源头阻断（AC1/AC6）✓。

## 场景 8：Fork 子 Agent 调 Agent 工具被拦截（AC5/AC19）

Fork 子 Agent 的任务为「先尝试再启动一个子 Agent 读 README.md」，其最终报告：

```
1. 子 Agent 是否成功启动：否 —— 它在尝试再启动一个子 Agent 读取 README.md 时被系统拦截，
   返回错误："Fork 子 Agent 不能再启动 Agent"（Fork 进程不允许再嵌套 Fork）。
```

Fork 工具列表保留 Agent 工具，调用被 QuerySource 闸拦截并回灌错误 ✓。

## 场景 9：SendMessage 续派（AC15）

```
● Agent({…"name":"w2"…run_in_background:true…}) → {"task_id":"task_11d488bc9b2c3","status":"async_launched"}
● SendMessage({"name": "w2", "message": "把刚才的行数乘以 3，再把结果报告回来。"})
  ⎿ {"task_id":"task_11d488bc9b2c3","status":"resumed"}
● TaskGet → result:"…之前统计的 doc.txt 行数：2 行 - 2 × 3 = 6 …"  status=completed, tool_count=2
```

续派即时返回 resumed；子 Agent 带原上下文（记得此前统计过 2 行）重新跑动并二次回报 ✓。

## 场景 10：前台超时自动切后台（AC10）

Explore 子 Agent 前台跑超过 120 秒，Agent 工具 result 自动变为：

```
  ⎿ {"task_id":"task_11d7a3475033e","status":"timed_out_to_background"}
```

主对话立即恢复可交互；后台任务继续跑动（toolCount 持续增长，TaskGet 可见）。
测试覆盖用 `-Dcortex.subagent.autoBackgroundMs=5000` 可调小（`AgentTool.autoBackgroundMs()` 每次执行时读取）✓。

## 场景 11：项目级覆盖内置角色（AC16）

`.cortex/agents/explore.md`（name: Explore）放置后：

```
● Agent({"subagent_type": "Explore", …})
  ⎿ [project-level-explore]
```

项目级 systemPrompt 覆盖内置定义；删除文件重启后恢复内置行为 ✓。

## 场景 11.5：全新自定义子 Agent 端到端（AC7/AC21）

`.cortex/agents/wc-counter.md`（disallowedTools=[write_file,edit_file]、permissionMode: dontAsk、
maxTurns: 5）：

```
● Agent({"description": "统计 doc.txt 行数", "prompt": "请统计…})
  ⎿ [wc-counter] 2
```

- 答复以 `[wc-counter]` 开头（systemPrompt 注入生效）✓
- 子 Agent 跑 bash 全程零审批弹窗（dontAsk 生效，AC7）✓
- 单轮即完成（maxTurns 上限内）；黑名单工具不在其工具列表 ✓

## 场景 11.6：自定义 Agent 字段错误降级（AC22/N4）

`.cortex/agents/bad.md`（model: gpt-4、permissionMode: weirdMode）启动时 stderr：

```
subagent "bad": unknown model "gpt-4", fallback to inherit (/private/tmp/cortex12-e2e/.cortex/agents/bad.md)
subagent "bad": unknown permissionMode "weirdMode", fallback to default (/private/tmp/cortex12-e2e/.cortex/agents/bad.md)
```

两条警告精确出现、启动不阻断，bad 角色仍可 resolve ✓。

## 场景 2/9 补充：<task-notification> 注入（AC12/N7）

worker1 后台完成后，新回合问「worker1 完成了吗？」，模型**未调用任何查询工具**直接回答：

```
● 是的，worker1 已完成（系统通知显示 status: completed）。
  • doc.txt 总行数：2 行
```

通知只进 reminder 区（模型可见、不进用户视窗、不占工具配额）✓。

## 实跑修复（E2E 发现的真实缺陷）

1. **审批互相覆盖竞态**：子 Agent 的审批请求（转发器置 pending）会被主 Agent 事件流里的
   Approval 直接覆盖，导致子 Agent 永久阻塞。修复：主/子审批统一走 `offerApproval` 队列
   （pending 占用时排队，`promoteNextApproval` 晋升），任何请求不再丢失。
2. **SendMessage 拖垮工具线程**：续派曾直接在 SendMessage 工具执行线程上跑整个子 Agent，
   30 秒 per-tool 超时把工具打成「工具执行超时」并中断续派。修复：续派改起新虚拟线程，
   工具立即返回 `{"status":"resumed"}`。

两处修复后全量 `./gradlew test` 复跑全绿，以上场景在修复后的 jar 上重新实跑验证通过。
