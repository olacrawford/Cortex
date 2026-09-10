# Agent Loop Tasks

> 基于已批准的 spec.md + plan.md。任务有序，每步留绿编译。验证一律「先跑命令看输出，再下结论」。

## 文件清单

| 操作 | 文件                                                                                                       | 职责                                                                                                   |
| ---- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| 修改 | `src/main/java/com/cortex/llm/StreamEvent.java`                                                           | sealed 新增 `UsageEvent`；新增 `Usage` record                                                          |
| 修改 | `src/main/java/com/cortex/llm/Provider.java`                                                              | `stream` 加 `String systemSuffix` 形参                                                                 |
| 修改 | `src/main/java/com/cortex/llm/AnthropicProvider.java`                                                     | `effectiveSystem(suffix)`；流结束上抛 `UsageEvent`                                                     |
| 修改 | `src/main/java/com/cortex/llm/OpenAIProvider.java`                                                        | `streamOptions.includeUsage(true)`；`toOpenAIMessages` 拼 `suffix`；上抛 `UsageEvent`                  |
| 修改 | `src/main/java/com/cortex/tool/Tool.java`                                                                 | 接口加 `boolean readOnly()`                                                                            |
| 修改 | `src/main/java/com/cortex/tool/Registry.java`                                                             | `readOnlyDefinitions`、`isReadOnly`                                                                    |
| 修改 | `src/main/java/com/cortex/tool/{ReadFileTool,WriteFileTool,EditFileTool,BashTool,GlobTool,GrepTool}.java` | 各加 `readOnly()`                                                                                      |
| 修改 | `src/main/java/com/cortex/conversation/Conversation.java`                                                 | `lastRole()`                                                                                           |
| 修改 | `src/main/java/com/cortex/prompt/Prompt.java`                                                             | `PLAN_MODE_REMINDER`、`EXECUTE_DIRECTIVE`；`SYSTEM_PROMPT` 增循环约定                                  |
| 重写 | `src/main/java/com/cortex/agent/Agent.java`                                                               | ReAct 循环、`Mode`、`executeBatched`、`UsageReport`/`Iter`/`Notice` 事件、历史收尾                     |
| 修改 | `src/main/java/com/cortex/agent/Event.java`                                                               | sealed 新增 `UsageReport`、`Iter`、`Notice` 子类型；新增 `Usage` record                                |
| 新建 | `src/main/java/com/cortex/agent/Mode.java`                                                                | `enum Mode { NORMAL, PLAN }`                                                                           |
| 新建 | `src/main/java/com/cortex/agent/CancelToken.java`                                                         | per-turn 取消句柄（volatile + 派生 timeout + 可选回调）                                                |
| 重写 | `src/test/java/dev/cortex/agent/AgentTest.java`                                                           | 多轮 fake provider、并发分批、停止条件、Plan 工具集                                                    |
| 修改 | `src/test/java/dev/cortex/conversation/ConversationTest.java`                                             | `lastRole` 断言                                                                                        |
| 修改 | `src/main/java/com/cortex/tui/{CortexModel,AgentEvent 队列,View}.java`                                   | `mode`、per-turn cancel、Esc/Ctrl+C、`/plan /do`、`UsageReport`/`Iter`/`Notice`/多工具、状态栏、动态区 |
| 修改 | `src/test/java/dev/cortex/smoke/SmokeCortex.java`                                                        | `Agent.run` 调用处补 `Mode` 与 `CancelToken` 实参（`Mode.NORMAL`）                                     |

## T1: llm 新增 Usage record + UsageEvent（纯增量）

**文件：** `src/main/java/com/cortex/llm/StreamEvent.java`、`src/main/java/com/cortex/llm/Usage.java`
**依赖：** 无
**步骤：**

1. 新建 `Usage.java`：`public record Usage(long inputTokens, long outputTokens) {}`（带中文 Javadoc：本轮输入/输出 token 数）。
2. `StreamEvent.java`：sealed 接口 `permits` 列表增 `UsageEvent`；新增嵌套 `record UsageEvent(Usage usage) implements StreamEvent {}`，并补 Javadoc「`UsageEvent` 在 `Done` 之前一次性发出」。

**验证：** `./gradlew -q -DskipTests compile` 通过（纯增子类型 + record，向后兼容现有 `switch` 表达式时会触发完备性告警——T5 / T6 落地后会消失）。

## T2: tool 只读分类

**文件：** `src/main/java/com/cortex/tool/Tool.java`、`src/main/java/com/cortex/tool/Registry.java`、`src/main/java/com/cortex/tool/{ReadFileTool,WriteFileTool,EditFileTool,BashTool,GlobTool,GrepTool}.java`
**依赖：** 无
**步骤：**

1. `Tool.java`：接口加 `boolean readOnly();`（Javadoc：true=只读，可并发执行 & Plan Mode 放行）。
2. 6 个工具各加一行实现：`ReadFileTool`/`GlobTool`/`GrepTool` → `@Override public boolean readOnly() { return true; }`；`WriteFileTool`/`EditFileTool`/`BashTool` → `return false;`。
3. `Registry.java`：
   - `public List<Map<String, Object>/*tool schema*/> readOnlyDefinitions()`：仿 `definitions()` 按注册顺序遍历，仅收 `tools.get(name).readOnly()==true` 的项。
   - `public boolean isReadOnly(String name)`：`Optional<Tool> t = get(name); return t.isPresent() && t.get().readOnly();`。

**验证：** `./gradlew compileJava`；`./gradlew -q test -Dtest='com.cortex.tool.*'` 不回归（接口加方法后 6 工具均实现，编译即证明完整）。

## T3: Conversation.lastRole

**文件：** `src/main/java/com/cortex/conversation/Conversation.java`、`src/test/java/dev/cortex/conversation/ConversationTest.java`
**依赖：** 无
**步骤：**

1. `Conversation.java`：新增 `public Optional<Role> lastRole()`——空历史返回 `Optional.empty()`，否则返回 `Optional.of(messages.get(messages.size()-1).role())`。
2. `ConversationTest.java`：补一条 `@Test`——空会话 `lastRole().isEmpty()`；`addUser` 后 `lastRole().get() == Role.USER`；`addToolResults` 后 `== Role.TOOL`；`addAssistant` 后 `== Role.ASSISTANT`。

**验证：** `./gradlew -q test -Dtest=ConversationTest` 通过。

## T4: prompt 计划态提示与循环约定

**文件：** `src/main/java/com/cortex/prompt/Prompt.java`
**依赖：** 无
**步骤：**

1. `SYSTEM_PROMPT` 增补一句 Agent 循环约定：持续调用工具推进任务，直到任务完成后再给出最终简洁答复（不要每步都停下来等用户）。
2. 新增 `public static final String PLAN_MODE_REMINDER = "..."`：计划模式系统后缀——当前为计划模式，只能用只读工具（读文件 / 按模式找文件 / 搜内容）调研并产出一份分步执行计划；不得写文件、改文件或执行命令；计划写完即停，等用户用 `/do` 批准后再执行。
3. 新增 `public static final String EXECUTE_DIRECTIVE = "请按上面的计划开始执行。"`。
4. （可选）启动 banner 的就绪提示增提 `/plan`、`/do`。

**验证：** `./gradlew compileJava`；`./gradlew -q test` 不回归。

## T5: llm stream 加 systemSuffix + 用量上抛

**文件：** `src/main/java/com/cortex/llm/Provider.java`、`src/main/java/com/cortex/llm/AnthropicProvider.java`、`src/main/java/com/cortex/llm/OpenAIProvider.java`、`src/main/java/com/cortex/agent/Agent.java`（临时补参）
**依赖：** T1
**步骤：**

1. `Provider.java`：接口签名改为 `BlockingQueue<StreamEvent> stream(List<Message> messages, List<Map<String, Object>/*tool schema*/> tools, String systemSuffix);`，更新 Javadoc 说明 `systemSuffix` 语义（非空时拼到内置 `SYSTEM_PROMPT` 之后）。
2. `AnthropicProvider.java`：
   - `stream` 加 `systemSuffix` 形参；`MessageCreateParams.system(...)` 改为 `effectiveSystem(systemSuffix)`——`suffix==null||suffix.isEmpty()` 单块 `Prompt.SYSTEM_PROMPT`；非空时单块 `Prompt.SYSTEM_PROMPT + "\n\n" + suffix`。
   - SDK 异步流式订阅的 `onCompleteFuture`/`get()` 完成（无异常）后、上抛 `ToolCallComplete` 与 `pub.close()` 前：从 `MessageAccumulator` 取 `var u = accumulator.message().usage();`，`pub.submit(new StreamEvent.UsageEvent(new Usage(u.inputTokens(), u.outputTokens())))`。
3. `OpenAIProvider.java`：
   - `stream` 加 `systemSuffix`；构造请求时 `params.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())`。
   - `toOpenAIMessages(messages, systemSuffix)`：首条 system 消息文本 `Prompt.SYSTEM_PROMPT`，`suffix` 非空时 `+"\n\n"+suffix`（其调用处同步加实参）。
   - 流结束后：从累加器取 `CompletionUsage u`，`pub.submit(new StreamEvent.UsageEvent(new Usage(u.promptTokens(), u.completionTokens())))`。
4. `Agent.java`：把现有 `streamOnce` 里唯一的 `provider.stream(conv.messages(), defs)` 调用补成 `provider.stream(conv.messages(), defs, "")` 以匹配新签名——本步即让**非测试构建**保持绿（T6 会整体重写 `Agent.java`）。

> 说明：`SmokeMain` 走 `Agent.run`、不直接调 `stream`，本步不动它（其 `run` 调用在 T7 随 `mode` / `cancel` 形参一并更新）。`AgentTest.FakeProvider#stream` 也实现该接口，本步之后它会编译失败——这是预期的，T6 重写 `AgentTest` 时一并补 `systemSuffix` 形参；因此本步**不要**跑 `./gradlew -q test -Dtest=AgentTest`。

**验证：** `./gradlew -q -DskipTests compile` 通过（主源码绿）；`./gradlew -q test -Dtest='com.cortex.llm.*'` 不回归；用 `./gradlew exec:java -Dexec.mainClass=com.cortex.Cortex` 发一条纯文本回复正常（用量已随流上抛，旧 agent 暂未消费）。

## T6: agent ReAct 循环重写

**文件：** `src/main/java/com/cortex/agent/Agent.java`、`src/main/java/com/cortex/agent/Event.java`、`src/main/java/com/cortex/agent/Mode.java`、`src/main/java/com/cortex/agent/CancelToken.java`、`src/test/java/dev/cortex/agent/AgentTest.java`
**依赖：** T1, T2, T3, T4, T5
**步骤：**

1. `Mode.java`（新增）：`public enum Mode { NORMAL, PLAN }`。
2. `CancelToken.java`（新增）：`volatile boolean cancelled` + `cancel()` 方法 + `isCancelled()` + 可选 `Runnable onCancel` 列表 + `withTimeout(Duration)` 派生（用 `ScheduledExecutorService` 定时触发 `cancel()`，可被父 token 提前触发）。
3. `Event.java`：sealed `permits` 列表新增 `UsageReport`、`Iter`、`Notice` 子类型；新增 `Usage(long input, long output)` record（注意与 `llm.Usage` 同义但解耦在 agent 包内）。
4. `Agent.java`：
   - 类 Javadoc 改为「ReAct 循环编排」。
   - 类型：保留 `Phase`/`ToolEvent`/构造函数；新增 `Usage` record（或复用 `agent.Usage`）、`Mode` enum；`Event` sealed 增 `UsageReport`/`Iter`/`Notice` 子类型。
   - 常量：按 plan「迭代、停止常量与提示文案」原样落 `MAX_ITERATIONS` / `MAX_UNKNOWN_RUN` 与 `NOTICE_MAX_ITER` / `NOTICE_UNKNOWN_TOOLS` / `NOTICE_STREAM_ERR` / `NOTICE_CANCELLED`（文案以 plan 为准，T8 端到端按这些文案核对）。
   - `run(conv, mode, cancel) → BlockingQueue<AgentEvent>`：按 plan「`run` 算法」实现 `for iter` 循环——按 `mode` 取 `defs`（`definitions` / `readOnlyDefinitions`）与 `suffix`（`""` / `Prompt.PLAN_MODE_REMINDER`）；emit Iter → `streamOnce` → emit `UsageReport` → 无工具自然完成 / 有工具 `addAssistantWithToolCallComplete` → 统计 `unknownRun` → `executeBatched` → `addToolResults`（无条件）→ **取消（`!batch.completed()`）最高优先级收尾** → 未知工具上限收尾 → 循环走完触达迭代上限收尾。内部用 `BlockingQueue<AgentEvent> bus`，整个循环跑在 `Thread.ofVirtual().start(...)` 内，`try (bus)` 关闭。
   - `streamOnce(conv, defs, suffix, cancel, bus) → StreamOutcome`：`suffix` 为 ch04 新增形参，透传给 `provider.stream`；订阅 `BlockingQueue<llm.StreamEvent>` 同步消费——`switch` pattern match 处理 `TextDelta` / `ToolCallComplete` / `UsageEvent` / `Done` / `Failed`；记录 `usage`、收集 `calls`、转发 Text，`Failed` 即发 `AgentEvent.ErrorEvent` 返回 `failed=true`。
   - `executeBatched(calls, cancel, bus) → BatchOutcome`：保序分批——从 `i=0` 扫描，`registry.isReadOnly(calls.get(i).name())` 为真则吃最长连续只读区间 `[i, j)` 用 `CountDownLatch(j-i)` + `Thread.ofVirtual().start(...)` **并发**（每虚拟线程内 `var sub = cancel.withTimeout(Tool.DEFAULT_TIMEOUT)` 后 `// 工具执行走 StreamingExecutor.executeAll(sub, ...)`，只写自己下标 `results[k]`），否则**串行**单个；每段执行前判 `cancel.isCancelled()` 取消则填 `NOTICE_CANCELLED` 结果返 `completed=false`；事件「Start 按序、End 按序」（见 plan）。
   - 辅助：`allUnknown(calls)`（每个 call 用 `registry.get` 判，全未注册才 true）、`ensureFinal`（沿用 ch03）、`ensureAssistantTail(conv, fallback)`、`finishCancelled(conv)`、`emit` / `argsPreview`（沿用 ch03）。
5. `AgentTest.java`（**替换** ch03 的 `testSingleRoundReadAndAnswer` / `testSingleRoundLimit`——后者断言单轮已与 ch04 多轮矛盾）。`FakeProvider#stream` 签名补 `String systemSuffix`（并在某用例里记录收到的 `tools` / `suffix` 供断言）；多轮靠 `List<List<StreamEvent>> scripts` 逐次返回：
   - 场景 A（多轮链路 AC1）：脚本①返回 1 个 read_file 工具调用、脚本②返回纯文本 → 断言事件序列含 `Iter(1)`、`Tool(Start/End)`、`Iter(2)`、最终 `TextDelta`、`Done`；`conv` 末尾为 assistant 文本，中间含 tool_use 回合 + `Role.TOOL` 回合。
   - 场景 B（迭代上限 AC3）：用「每次 stream 都返回一个工具调用」的 fake（忽略脚本耗尽，恒返工具）→ 断言恰好 `MAX_ITERATIONS` 次请求后停（`fake.calls == MAX_ITERATIONS`）、收到 `Notice(NOTICE_MAX_ITER)`、`conv.lastRole().get() == Role.ASSISTANT`。
   - 场景 C（连续未知工具 AC4）：脚本连续返回未注册工具名 → 断言 `MAX_UNKNOWN_RUN` 轮后停并 `Notice(NOTICE_UNKNOWN_TOOLS)`；另一用例在其间混入一个 read_file，断言计数重置、不提前停。
   - 场景 D（保序分批 AC8）：构造**自定义 `ToolRegistry`** 注册两个插桩工具——一个只读工具（`readOnly()==true`，`execute` 内 `AtomicInteger` 记录「同时在跑的并发数」峰值、并 `Thread.sleep` 制造重叠）与一个有副作用工具（`readOnly()==false`，记录开始时刻）。脚本一轮返回 `[ro, ro, rw]` → 断言：两只读的并发峰值 ≥2（确实并发）、rw 的开始时刻晚于两只读完成、`addToolResults` 写入历史的结果顺序与调用序一致（按结果内容 / id 比对，不依赖具体方法名）。
   - 场景 E（取消历史一致 AC9）：插桩工具在 `execute` 中阻塞，测试侧在执行期间调 `cancel.cancel()` per-turn token → 断言 `conv` 末尾配对合法（含 tool_results、最后是 assistant 文本 `NOTICE_CANCELLED`），无悬空 tool_use；随后再追加一轮纯文本脚本能正常跑（角色交替未坏）。
   - 场景 F（Plan 工具集 AC13）：`agent.run(conv, Mode.PLAN, new CancelToken())` → 断言 fake 收到的 `tools` 仅含只读工具定义、`suffix == Prompt.PLAN_MODE_REMINDER`。

**验证：** `./gradlew -q test -Dtest=AgentTest` 全通过；用 `-DargLine="-Xss2m"` 或 `./gradlew -q -Dtest=AgentTest test` 加压跑多遍（覆盖并发分批，N6）；可选 `./gradlew -q dependency:tree | grep junit` 确认 JUnit 5 启用。

## T7: tui 接入 Agent Loop + 收尾 `run` 调用方

**文件：** `src/main/java/com/cortex/tui/CortexModel.java`、`src/main/java/com/cortex/tui/AgentEvent 队列.java`、`src/main/java/com/cortex/tui/Styles.java + MarkdownRenderer.java`、`src/test/java/dev/cortex/smoke/SmokeCortex.java`
**依赖：** T4, T6
**说明：** T6 改了 `Agent.run` 签名（加 `mode` 与 `cancel`），其调用方 `tui/AgentEvent 队列`（或 `CortexModel.onSubmit`）与 `SmokeMain` 在此步同步更新——本步完成后 `./gradlew shadowJar` 才在**仓库级**重新转绿（T6 后只保证 agent 包及其测试绿）。
**步骤：**

1. `CortexModel.java`：
   - 新增字段：`Mode mode = Mode.NORMAL;`、`int iter;`、`long usageIn;`、`long usageOut;`、`List<ToolDisplay> curTools = new ArrayList<>();`（移除单个 `curTool`）、`CancelToken turnCancel;`。
   - JLine/tui.tea `KeyStroke` 拦截：`Ctrl+C` → `SessionState.STREAMING` 时 `turnCancel.cancel()`（不退出，等 onComplete）/ 否则 `screen.stopScreen(); System.exit(0);`；新增 `Esc` → `SessionState.STREAMING` 时 `turnCancel.cancel()`。
2. `AgentEvent 队列.java`（兼 `CortexModel.onSubmit`）：
   - `onSubmit`：识别 `/exit`（退出）、`/plan`（`mode=Mode.PLAN`、提示块、回 IDLE）、`/do`（`mode=Mode.NORMAL`、`conv.addUser(Prompt.EXECUTE_DIRECTIVE)`、走启动流程）、普通文本（`conv.addUser`）。启动处：`turnCancel = new CancelToken()`；`BlockingQueue<AgentEvent> events = new Agent(client, registry, protocol, selectedProvider).run(conv, mode, turnCancel)`；`events.subscribe(this)`；`iter=0`；`state=SessionState.STREAMING`。
   - `onNext(event)`：通过 `program.send(new AgentEventMessage(...)` 切回 GUI 线程，按 plan 分派顺序处理（switch pattern match）`Failed` / `Tool` / `UsageReport`（累加 `usageIn`/`usageOut`）/ `Notice`（灰提示块）/ `Iter`（set `this.iter`）/ `Done` / `TextDelta`；`Tool.Phase.START` 追加 `curTools`（首个工具前先提交 preamble）、`Phase.END` 从 `curTools` 移除队首并按序 append 工具行 + 结果摘要到 scrollback。
   - `onComplete` / `onError`：兜底 `finishTurn()`。
   - `finishTurn`：清 `curReply` / `curTools.clear()` / `iter=0` / `turnCancel=null`，回 `SessionState.IDLE`（保留 `mode`、`usageIn`/`usageOut`）。
3. `Styles.java + MarkdownRenderer.java`：
   - `statusBar`：左侧 provider 名后在 `Mode.PLAN` 时附「PLAN」徽标；右侧 model 名旁附 `↑{in} ↓{out} tok`（紧凑数字，如 `1.2k`）。
   - 流式动态区：`curTools` 非空逐行渲染 `● name(args)` Running…；否则「Imagining… (Ns · 第 N 轮)」（`iter>0` 附轮次）。
4. `SmokeCortex.java`：`new Agent(client, registry, protocol, selectedProvider).run(conv)` 调用补 `mode` 与 `cancel` 实参 → `agent.run(conv, Mode.NORMAL, new CancelToken())`（保持其调试用途，不需感知 plan / 取消）。

**验证：** `./gradlew shadowJar`（仓库级转绿）；`./gradlew -q test`（无新增测试，但需保证未回归）；`./gradlew compileJava` (代码风格由 IDE 保证) 通过（如启用）。

## T8: 全量验证与端到端冒烟

**文件：** 无（验证）
**依赖：** T1–T7
**步骤：**

1. `./gradlew compileJava` (代码风格由 IDE 保证)（google-java-format 合规）；`./gradlew shadowJar`；`./gradlew -q test`；`./gradlew -q test -Dtest='com.cortex.agent.*,com.cortex.tool.*'`（再跑一次锁住 N6 关键包）。
2. 端到端（openai 兼容端点，用 `.cortex/config.yaml`）：
   - 多轮（AC1）：问「读 `docs/ch03/spec.md`，再据其内容新建 `docs/ch03/summary.txt` 写一句话摘要」→ 观察 read_file → write_file 跨多轮自动连环、状态栏用量增长、动态区轮次递增、最终答复。
   - 取消（AC10）：发一个会跑多步的任务，中途按 Esc / Ctrl+C → 回空闲态不退出 → 再正常发一条继续对话（验证历史未坏）。
   - 流出错（AC5）：临时改坏 `base_url` 或断网发一条 → 错误提示、程序不退出、改回后继续。
   - Plan Mode（AC13）：`/plan` → 问「给登录功能加单测的方案」→ 观察只出现 read/glob/grep 类工具与计划文本、无写/执行 → `/do` → 切回全工具按计划执行。
3. （可选）若有 anthropic 配置，重复多轮场景验证跨协议一致（AC14）。

**验证：** 全部命令通过、端到端各场景符合预期；密钥不回显（通读输出，AC/N7）。

## 执行顺序

```
T1 ─┬─ T5 ─┐
T2 ─┤      │
T3 ─┼──────┼─ T6 ─┬─ T7 ─┐
T4 ─┘      │      │      │
           └──────┘      └─ T8
```

（T1–T4 互相独立可并行；T5 依赖 T1；T6 依赖 T1/T2/T3/T4/T5；T7 依赖 T4/T6；T8 收尾全部。）
