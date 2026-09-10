# Agent Loop Plan

> 基于已批准的 spec.md。本文档与语言相关（Java 21）。SDK 类型已对 `com.anthropic:anthropic-java` 2.x、`com.openai:openai-java` 4.x 核对（grounding 实测）。

## 架构概览

ch04 不新增包，在 ch03「tool / agent / llm / conversation / prompt / tui」之上**扩展**：

- **com.cortex.agent（重写 `Agent.run`）**：把 ch03 的「请求#1 → 执行 → 请求#2 → 停」改为真正的 ReAct 循环——`for` 迭代直到自然完成 / 上限 / 取消 / 连续未知工具 / 出错。新增保序分批并发执行、迭代进度与用量事件、终止时的历史一致性收尾、Plan/Normal 两种模式。
- **com.cortex.llm（扩展）**：`StreamEvent` 新增 `Usage` 子类型；`Provider.stream` 增 `String systemSuffix` 形参（Plan Mode 系统提示后缀）；两适配器在流结束后上抛本轮 token 用量、把 `systemSuffix` 拼到内置系统提示后；OpenAI 打开 `ChatCompletionStreamOptions.includeUsage`。
- **com.cortex.tool（扩展）**：`Tool` 接口增 `boolean readOnly()`；6 个工具各实现；`ToolRegistry` 增 `readOnlyDefinitions()` 与 `isReadOnly(name)`。
- **com.cortex.conversation（扩展）**：增 `Optional<Role> lastRole()`（终止收尾判断角色尾巴）。
- **com.cortex.prompt（扩展）**：增 `PLAN_MODE_REMINDER`（计划态系统后缀）与 `EXECUTE_DIRECTIVE`（`/do` 触发执行的用户消息）；`SYSTEM_PROMPT` 增补「持续工作直到任务完成」的 Agent 循环约定。
- **com.cortex.tui（扩展）**：`submit` 识别 `/plan`、`/do`；引入 per-turn 取消标志位与 cancel 钩子；事件订阅器处理用量 / 进度 / 通知 / 多个并发工具；按键处理拆分 Esc / Ctrl+C；状态栏显示模式与累计用量、动态区显示迭代轮次。

依赖方向不变、无环：`tool → llm`；`conversation → llm`；`agent → {llm, tool, conversation}`；`tui → {agent, tool, conversation, llm, prompt}`；`llm → {config, prompt}`。

## 核心数据结构

### llm 包（`StreamEvent` 扩展）

```java
package com.cortex.llm;

// Usage 协议无关地承载一轮请求的 token 用量。
public record Usage(long inputTokens, long outputTokens) {}

// StreamEvent 扩展：在 TextDelta / ToolCallComplete / Done / Failed 之外，
// turn 结束时一次性上抛 UsageEvent（Done 之前发出）。
public sealed interface StreamEvent
        permits AgentEvent.StreamText,
                AgentEvent.ToolUseEvent,
                StreamEvent.UsageEvent,
                AgentEvent.LoopComplete,
                AgentEvent.ErrorEvent {

    record TextDelta(String text) implements StreamEvent {}
    record ToolCallComplete(java.util.List<ToolCall> calls) implements StreamEvent {}
    record UsageEvent(Usage usage) implements StreamEvent {} // 新增
    record Done() implements StreamEvent {}
    record Failed(Throwable error) implements StreamEvent {}
}
```

`Provider.stream` 签名变更（新增第 3 形参）：

```java
// systemSuffix 非空时拼接到内置 system prompt 之后（Plan Mode 计划态约束）；为空即普通模式。
java.util.concurrent.BlockingQueue<StreamEvent> stream(
        java.util.List<Message> messages,
        java.util.List<Map<String, Object>/*tool schema*/> tools,
        String systemSuffix);
```

`Message`/`ToolCall`/`ToolResult`/`Map<String, Object>/*tool schema*/` 与 `Role.TOOL` 沿用 ch03，不变。

### tool 包（接口扩展）

```java
// Tool 接口新增 readOnly：true=只读工具（可并发执行 & Plan Mode 放行）。
public interface Tool {
    String name();
    String description();
    java.util.Map<String, Object> schema();
    boolean readOnly(); // 新增
    ToolResult execute(CancelToken cancel, com.fasterxml.jackson.databind.JsonNode args);
}
```

只读分类（依据语义）：`ReadFileTool` / `GlobTool` / `GrepTool` → `true`；`WriteFileTool` / `EditFileTool` / `BashTool` → `false`（`BashTool` 可执行任意副作用命令，保守归为有副作用、串行执行）。

`ToolRegistry` 新增：

```java
// Plan Mode：只导出 readOnly()==true 的工具定义。
public java.util.List<Map<String, Object>/*tool schema*/> readOnlyDefinitions();

// 分批判定；未知工具返回 false（按串行处理）。
public boolean isReadOnly(String name);
```

### agent 包（事件模型扩展 + `run` 重写）

```java
package com.cortex.agent;

// Usage 一轮请求的 token 用量（透传 llm.Usage 的语义）。
public record Usage(long input, long output) {}

// Mode 区分普通模式与计划模式。
public enum Mode { NORMAL, PLAN }

// Event 对外事件流元素，消费者据子类型分派渲染（sealed + pattern matching）。
public sealed interface AgentEvent
        permits AgentEvent.StreamTextDelta,
                AgentEvent.ToolResultEvent,
                Event.UsageReport,
                Event.Iter,
                Event.Notice,
                AgentEvent.LoopComplete,
                AgentEvent.ErrorEvent {

    record TextDelta(String text) implements Event {}              // 模型文本增量（preamble 或最终答复）
    record Tool(ToolEvent toolEvent) implements Event {}           // 工具调用开始/结束（沿用 ch03）
    record UsageReport(Usage usage) implements Event {}            // 本轮 token 用量（每轮 stream 结束后一次）
    record Iter(int iter) implements Event {}                      // 进入第 iter 轮迭代（进度提示）
    record Notice(String message) implements Event {}              // 系统提示（停止原因等）；仅 UI 展示，不入历史
    record Done() implements Event {}                              // 本轮（整个 Loop）结束
    record Failed(Throwable error) implements Event {}             // 出错（不中断会话）
}

// Agent.run 执行 Agent Loop，返回事件 Publisher；mode 决定工具集与系统后缀。
public java.util.concurrent.BlockingQueue<AgentEvent> run(
        com.cortex.conversation.ConversationManager conv,
        Mode mode,
        CancelToken cancel);
```

`ToolEvent`、`Phase`(START/END)、`Agent`、`Agent(client, registry, protocol, cfg)` 构造沿用 ch03。`run` 签名新增 `mode` 形参与 per-turn `CancelToken`。

`Agent` 构造沿用 ch03：`public Agent(LlmClient client, ToolRegistry registry)`。`mode` 为 `run` 的每次调用入参，不写入 `Agent` 状态（同一 `Agent` 可被不同 mode 复用）。

迭代、停止常量与提示文案（内置，不可配）：

```java
final class AgentConstants {
    static final int MAX_ITERATIONS  = 25; // 迭代上限兜底（F2）
    static final int MAX_UNKNOWN_RUN = 3;  // 连续「整轮只产生未知工具调用」的迭代数上限（F2）

    // 停止/收尾提示文案——既作为 Event.Notice 推给 UI，
    // 也作为 ensureAssistantTail 写入历史的兜底文本。
    static final String NOTICE_MAX_ITER       = "(已达最大迭代轮数 25,自动停止;可继续发消息推进。)";
    static final String NOTICE_UNKNOWN_TOOLS  = "(连续多轮只请求到未注册的工具,自动停止。)";
    static final String NOTICE_STREAM_ERR     = "(请求出错,本轮已中断。)";
    static final String NOTICE_CANCELLED      = "(已取消。)";
}
```

`CancelToken` 为本章引入的轻量取消句柄（`volatile boolean cancelled` + `cancel()` 方法 + 可选回调），取代 Go 的 `ctx.Done()`；适配器内的虚拟线程在每个流事件回调里轮询 `cancel.isCancelled()`，工具执行同样接收 `CancelToken` 并在阻塞处定期检查。

## 模块设计

### com.cortex.agent（核心：`run` 重写）

**职责：** ReAct 循环编排（F1/F2）、保序分批并发执行（F5）、事件流（F3/F8/F9）、终止历史一致性（F6）、Plan/Normal 模式（F10）。
**对外接口：** `Agent`、构造函数、`run(conv, mode, cancel)`、`Event` sealed 接口、`ToolEvent`、`Phase`、`Mode`、`Usage`、`CancelToken`。
**依赖：** `llm`、`tool`、`conversation`、`java.util.concurrent.Flow`、`java.util.concurrent.BlockingQueue`、`java.util.concurrent.StructuredTaskScope`（或 `CompletableFuture` 集合，二选一，见技术决策）。

**`run` 算法（virtual thread 内执行，try-with-resources 关 `BlockingQueue<AgentEvent> bus`）：**

1. 按 `mode` 取工具集与系统后缀：
   - `Mode.PLAN` → `defs = registry.readOnlyDefinitions()`；`suffix = Prompt.PLAN_MODE_REMINDER`。
   - `Mode.NORMAL` → `defs = registry.getAllSchemas(protocol)`；`suffix = ""`。
2. `int unknownRun = 0;`
3. `for (int iter = 1; iter <= MAX_ITERATIONS; iter++)`：
   1. `emit(new Event.Iter(iter))`（进度，F9）；emit 返回 false（已取消）→ `finishCancelled(conv)`、`return`。
   2. `StreamOutcome out = streamOnce(conv, defs, suffix, cancel, bus);`
      - `out.failed()` 且 `cancel.isCancelled()` → `finishCancelled(conv)`、`return`。
      - `out.failed()` 且未取消（流出错，`AgentEvent.ErrorEvent` 已在 `streamOnce` 内发出）→ `ensureAssistantTail(conv, NOTICE_STREAM_ERR)`、`return`。
   3. `if (out.usage() != null) emit(new Event.UsageReport(new Usage(out.usage().inputTokens(), out.usage().outputTokens())))`（F8）。
   4. **无工具** `out.calls().isEmpty()`：`conv.addAssistant(ensureFinal(bus, out.text()))`；`emit(new AgentEvent.LoopComplete())`；`return`（自然完成，F2-1）。
   5. **有工具**：`conv.addAssistantWithToolCallComplete(out.text(), out.calls())`。
   6. 统计未知工具：`if (allUnknown(out.calls())) unknownRun++; else unknownRun = 0;`
   7. `BatchOutcome batch = executeBatched(out.calls(), cancel, bus);`（保序分批并发，F5）。
   8. `conv.addToolResults(batch.results())`（无论是否取消都回灌，含已取消占位，F6）。
   9. `if (!batch.completed())`（执行中被取消）→ `ensureAssistantTail(conv, NOTICE_CANCELLED)`、`return`。
   10. `if (unknownRun >= MAX_UNKNOWN_RUN)` → `emit(new Event.Notice(NOTICE_UNKNOWN_TOOLS))`；`ensureAssistantTail(conv, NOTICE_UNKNOWN_TOOLS)`；`emit(new AgentEvent.LoopComplete())`；`return`（F2-4）。
4. 循环正常走完（触达上限）：`emit(new Event.Notice(NOTICE_MAX_ITER))`；`ensureAssistantTail(conv, NOTICE_MAX_ITER)`；`emit(new AgentEvent.LoopComplete())`（F2-2）。

**`streamOnce(conv, defs, suffix, cancel, bus) → StreamOutcome(text, calls, usage, failed)`：**
订阅 `provider.stream(conv.messages(), defs, suffix)`（`BlockingQueue<llm.StreamEvent>`），用一个内部 `BlockingQueue.poll()` 同步消费：

- `AgentEvent.ErrorEvent f` → `emit(new AgentEvent.ErrorEvent(f.error()))`、返回 `failed=true`。
- `StreamEvent.UsageEvent u` → 记录 `usage = u.usage()`（不立即 emit，由 `run` 在拿到后统一 emit）。
- `AgentEvent.ToolUseEvent tc` → `calls.addAll(tc.calls())`。
- `AgentEvent.StreamText d` → 累积 `text` 并 `emit(new AgentEvent.StreamTextDelta(d.text()))`；emit 失败→标记 `failed=true`。
- `AgentEvent.LoopComplete` → 结束订阅。
  循环结束后若 `cancel.isCancelled()` 即视为失败；否则返回 `new StreamOutcome(text.toString(), calls, usage, false)`。

**`executeBatched(calls, cancel, bus) → BatchOutcome(results, completed)`：**
保序分批（F5）。`ToolResult[] results = new ToolResult[calls.size()];` 从 `i=0` 逐段扫描：

- 当前 `calls.get(i)` 只读 → 向前吃连续只读得最长区间 `[i, j)`（`j` 为首个非只读或末尾），**并发**执行该批：用 `Thread.ofVirtual().start(() -> ...)` 为每个调用起一个虚拟线程，线程内 `var toolCancel = cancel.withTimeout(Tool.DEFAULT_TIMEOUT)`（基于 `ScheduledExecutorService` 调度的派生 token）后 `// 工具执行走 StreamingExecutor.executeAll(toolCancel, ...)`，结果写入**自己下标** `results[k]`（互不重叠，无锁）；用 `CountDownLatch(j - i)` 等齐所有线程。`i = j`。
- 当前 `calls.get(i)` 非只读 → **串行**执行单个 `calls.get(i)`（同样 `cancel.withTimeout(Tool.DEFAULT_TIMEOUT)`），写 `results[i]`。`i++`。
- 每段开始执行前先判 `cancel.isCancelled()`：给区间内尚未执行的 call 填「已取消」结果（`new ToolResult(callId, NOTICE_CANCELLED, true)`），其余沿用已得结果，`return new BatchOutcome(List.of(results), false)`。
- 全部完成 `return new BatchOutcome(List.of(results), true)`。

> 超时口径：每个工具各拿一个 `DEFAULT_TIMEOUT`（30s）派生 cancel token，互不相加——并发批的整体上限仍是单个 30s（N1）。派生 token 都挂在 per-turn `cancel` 下，用户取消时一并触发，工具尽快返回。

事件与顺序（满足 N3 顺序、N2 不阻塞、N6 无竞争）：

- 单个串行工具：`emit(AgentEvent.ToolResultEvent{Start})` → 执行 → `emit(AgentEvent.ToolResultEvent{End})`（沿用 ch03 时序，动态区显示该工具 Running）。
- 并发批：**先**按序 `emit(AgentEvent.ToolResultEvent{Start})` 区间内每个工具（动态区列出多个在执行的工具行）→ 并发执行 → **再**按原始顺序 `emit(AgentEvent.ToolResultEvent{End})` 每个工具（逐个把工具行 + 结果摘要提交 scrollback）。即「开始事件按序、结束事件按序」，并发只发生在执行环节，事件顺序始终是调用序，scrollback 不交错。
- 并发安全：每个虚拟线程只写自己下标的 `results[k]`（不同下标互不重叠），不触碰 `conv`；`conv.addToolResults` 由 `run` 主流程在 `CountDownLatch.await()` 后串行调用。Token 用量累计在 TUI 侧串行处理。

**辅助函数：**

- `emit(bus, event) → boolean`：沿用 ch03——`if (cancel.isCancelled()) return false; bus.submit(event); return true;`。返回 false 当且仅当 per-turn cancel 被触发（`BlockingQueue` 由 `run` 自己持有并 try-with-resources 关闭，不会在提交中被外部关）。调用方据 false 提前收尾。
- `allUnknown(calls)`：对每个 call 用 `registry.get(tc.toolName())` 判断，**全部** `Optional.isEmpty()` 才返回 true；任一已注册即 false（混入已知工具视为有进展，计数重置）。不能用 `isReadOnly`（未知工具它也返回 false，会与有副作用工具混淆）。
- `ensureFinal(bus, text)`：沿用 ch03——`text` 非空原样返回；为空则 emit 占位提示并返回占位文本（避免空 assistant 回合破坏下一轮请求）。
- `ensureAssistantTail(conv, fallback)`：若 `conv.lastRole().orElse(null) != Role.ASSISTANT`（含空历史、末尾为 user 或 `Role.TOOL`），调 `conv.addAssistant(fallback)`，保证历史以 assistant 文本回合收尾（F6：取消/出错/上限后角色仍交替，下一轮请求不报 400）。
- `finishCancelled(conv)`：取消路径统一收尾——`ensureAssistantTail(conv, NOTICE_CANCELLED)`、`return`（**不 emit**，因 cancel 已触发 emit 必失败；`bus` 经 try-with-resources 关闭，TUI 由订阅器收到 `onComplete()` 即视为结束）。

> 终止优先级：执行中取消（`batch.completed()==false`）是**最高优先级**终止——立即 `ensureAssistantTail` 并 `return`，**跳过**未知工具计数与迭代上限检查。

### com.cortex.llm（扩展）

**职责：** 协议无关请求/响应 + 两协议工具调用全流程（沿用 ch03）+ 本轮用量上抛（F8）+ 系统后缀（F10）。

**`StreamEvent.java`：** sealed 接口新增 `UsageEvent(Usage usage)` 子类型；新增 `Usage` record。

**`Provider.java`：** `stream` 增 `String systemSuffix` 形参（更新接口 Javadoc 说明 systemSuffix 语义）。

**`AnthropicProvider.java`：**

- 系统提示：`MessageCreateParams.system(...)` 由硬编码 `Prompt.SYSTEM_PROMPT` 改为 `effectiveSystem(suffix)`——`suffix==""` 时单块 `Prompt.SYSTEM_PROMPT`；非空时拼成 `Prompt.SYSTEM_PROMPT + "\n\n" + suffix`（单 `TextBlockParam`，避免多块边界差异）。
- 用量：SDK 的异步流式订阅 `onCompleteFuture` 完成且无异常后，在上抛 `ToolCallComplete` / `Done` 之前从累加器 `MessageAccumulator` 读 `accumulator.message().usage()`：`pub.submit(new StreamEvent.UsageEvent(new Usage(usage.inputTokens(), usage.outputTokens())))`（usage 仅在流结束后完整）。
- 历史含工具交互时 thinking 已自动关闭（ch03 既有逻辑），多轮续答沿用，无需改动。

**`OpenAIProvider.java`：**

- 请求构造增 `params.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())`（不开则流式 usage 为空）。
- 系统提示：`toOpenAIMessages` 接收 `suffix`，把首条 system 消息文本由 `Prompt.SYSTEM_PROMPT` 改为拼接 `suffix`（非空时 `+"\n\n"+suffix`）。
- 用量：流结束后从累加器读 `CompletionUsage`：`pub.submit(new StreamEvent.UsageEvent(new Usage(usage.promptTokens(), usage.completionTokens())))`。

### com.cortex.tool（扩展）

- `Tool` 接口加 `boolean readOnly()`；6 个工具各加一行实现（read/glob/grep 返回 true，write/edit/bash 返回 false）。
- `Registry.readOnlyDefinitions()`：仿 `definitions()`，仅收 `tools.get(name).readOnly()==true` 的项，保持注册顺序。
- `Registry.isReadOnly(name)`：`Optional<Tool> t = get(name); return t.isPresent() && t.get().readOnly();`（未知工具 false）。
- `execute`、`Tool.DEFAULT_TIMEOUT`、6 工具的执行逻辑均不变。

### com.cortex.conversation（扩展）

```java
// lastRole 返回最后一条消息的角色;空历史返回 Optional.empty()。
public java.util.Optional<Role> lastRole();
```

其余沿用 ch03。

### com.cortex.prompt（扩展）

```java
// PLAN_MODE_REMINDER:Plan Mode 系统提示后缀,拼接到 SYSTEM_PROMPT 之后。
public static final String PLAN_MODE_REMINDER =
        "You are currently in PLAN MODE. You may use ONLY the read-only tools "
        + "(read_file, glob, grep) to investigate the codebase. You must NOT write files, edit files, "
        + "or run shell commands. Produce a clear, step-by-step plan for the task, then stop and wait for "
        + "the user to approve it with /do before doing any work.";

// EXECUTE_DIRECTIVE:/do 注入的用户消息——指示模型按上文已确认的计划开始执行,可使用全部工具。
public static final String EXECUTE_DIRECTIVE = "请按上面的计划开始执行。";
```

`SYSTEM_PROMPT` 增补一句 Agent 循环约定（追加到现有文案）：`"Keep using tools across multiple steps to make progress, and only give your final concise answer once the task is complete."`（中文项目里保持英文 system prompt 风格，与 ch03 现有 `SYSTEM_PROMPT` 一致）。

### com.cortex.tui（扩展）

**`CortexModel` 新增字段：**

- `Mode mode`——当前模式（默认 `Mode.NORMAL`），`/plan`、`/do` 切换，跨轮保持。
- `int iter`——当前迭代轮次（进度显示），每 `Event.Iter` 更新，`finishTurn` 归零。
- `long usageIn`、`long usageOut`——会话累计 token 用量，每个 `Event.UsageReport` 累加。
- `List<ToolDisplay> curTools`——替换 ch03 的单个 `ToolDisplay curTool`，支持并发批多个在执行的工具行。
- `CancelToken turnCancel`——本轮取消句柄（每次 `submit` 重新建），Esc / Ctrl+C 触发；程序级退出走全局 shutdown hook。

**`submit`（`AgentEvent 队列.java` 或 `CortexModel.onSubmit`）：**

1. `/exit` → 退出（沿用）。
2. `/plan` → `this.mode = Mode.PLAN`；提交一行提示块到 scrollback（如「已进入计划模式（只读工具）」）；回空闲态。
3. `/do` → `this.mode = Mode.NORMAL`；`conv.addUser(Prompt.EXECUTE_DIRECTIVE)`；走与普通提交相同的启动流程（不把 `/do` 本身入历史）。
4. 普通文本 → `conv.addUser(text)`。
5. 启动：`this.turnCancel = new CancelToken()`；`BlockingQueue<AgentEvent> events = new Agent(client, registry, protocol, selectedProvider).run(conv, this.mode, this.turnCancel)`；`state = SessionState.STREAMING`；`iter = 0`；用 `events.subscribe(agent.run(conv, queue))` 接管事件。

**`AgentEvent 队列.onNext` 分派（用 switch pattern matching）：**
按事件子类型分派——

- `AgentEvent.ErrorEvent f` → 红色错误块入 scrollback、回 IDLE。
- `AgentEvent.ToolResultEvent t` →
  - `Phase.START`：若 `curReply` 非空先把 preamble 提交 scrollback 并清空；`curTools.add(new ToolDisplay(name, args))`。
  - `Phase.END`：**FIFO 弹出队首** `curTools.remove(0)`（因 agent 保证 START 与 END 都按调用序发出，结束序 == 入队序，弹首即对应工具，无需按 name 匹配，重名工具也不会错位）；用其 args 定型工具行，按序 append toolLine + toolResultSummary 到 scrollback。
- `Event.UsageReport u` → `usageIn += u.usage().input(); usageOut += u.usage().output();`
- `Event.Notice n` → 灰色系统提示块入 scrollback。
- `Event.Iter i` → `this.iter = i.iter();`
- `AgentEvent.LoopComplete` → 把 `curReply` 用 Mordant 渲染落 scrollback；`finishTurn()`。
- `AgentEvent.StreamTextDelta d` → 累积 `curReply`，刷新 streamingLabel。

> JLine/tui.tea UI 线程切换：每个 onNext 内部都用 `program.send(new AgentEventMessage(() -> ...)` 把 UI 改动切回 GUI 线程，业务态字段（`iter`、`usageIn/usageOut`、`curTools`）也在 GUI 线程内变更——避免与渲染竞争（N6）。

**按键（JLine/tui.tea `KeyStroke` 全局过滤）：**

- `Ctrl+C`：`SessionState.STREAMING` → `turnCancel.cancel()`（取消本轮，不退出），继续等 onComplete；其余状态 → `screen.stopScreen(); System.exit(0)`（退出）。
- `Esc`：`SessionState.STREAMING` → `turnCancel.cancel()`；其余忽略。

**`Styles.java + MarkdownRenderer.java`：**

- `statusBar`：左侧在 provider 名后附模式标记（`Mode.PLAN` 显示「PLAN」徽标）；右侧在 model 名旁附累计用量 `↑{in} ↓{out} tok`（数值用紧凑格式，如 `1.2k`）。保持单行。
- 流式动态区：`curTools` 非空时逐行渲染 `● name(args)` + Running…（多个并发工具多行）；否则渲染「Imagining… (Ns · 第 N 轮)」（`iter>0` 时附轮次）。
- `toolLine` / `toolResultSummary` 沿用 ch03。

**`finishTurn`：** 清 `curReply`、`curTools.clear()`、`iter=0`、`turnCancel=null`，回 `SessionState.IDLE`（`mode`、`usageIn/usageOut` 不清——跨轮保持）。

## 模块交互

```
用户提交 /do 或普通文本
  └─ CortexModel.onSubmit:
       ├─ /plan → mode=PLAN,回 IDLE
       ├─ /do   → mode=NORMAL; conv.addUser(EXECUTE_DIRECTIVE)
       ├─ 文本  → conv.addUser(text)
       └─ turnCancel = new CancelToken();
          events = new Agent(client, registry, protocol, selectedProvider).run(conv, mode, turnCancel)
            └─ Agent.run (virtual thread, ReAct 循环):
                 for iter:
                   ├─ emit Iter
                   ├─ 请求: provider.stream(conv.messages(), defs(mode), suffix(mode))
                   │     └─ 适配器: 注入 tools + (SYSTEM_PROMPT+suffix) → 流式拼接
                   │          → AgentEvent.StreamText / ToolCallComplete / UsageEvent / Done|Failed
                   │     → agent 转发 TextDelta(preamble)、收集 calls、记录 usage
                   ├─ emit UsageReport
                   ├─ 无 calls → conv.addAssistant(final); emit Done; 停
                   └─ 有 calls:
                        ├─ conv.addAssistantWithToolCallComplete(preamble, calls)
                        ├─ executeBatched: 连续只读并发 / 有副作用串行
                        │     (Start 事件按序 → 执行 → End 事件按序)
                        ├─ conv.addToolResults(results)
                        └─ 下一轮 iter
  └─ AgentEvent 队列.onNext (pattern match):
       TextDelta→curReply;Tool→curTools/scrollback;UsageReport→累加;
       Iter→this.iter;Notice→灰提示;Done→提交最终答复+finishTurn
  └─ Ctrl+C / Esc(streaming) → turnCancel.cancel() → run 收尾历史 → bus.close()
       → AgentEvent 队列.onComplete → finishTurn → IDLE
```

并发模型：`conv` 任一时刻只被 `run` 的主虚拟线程触碰（`onSubmit` 在交给 `run` 前 `addUser`，之后不再触碰；执行批的工作线程只写各自 `results[k]`，不碰 `conv`）。`messages()` 返回不可变副本。TUI 仅按事件渲染。满足 N2/N6。

## 文件组织

```
cortex/
├── build.gradle.kts                         — 修改：新增 JUnit `assertj`（可选）以便并发断言；其余依赖沿用
├── src/main/java/com/cortex/
│   ├── llm/
│   │   ├── StreamEvent.java        — 修改:sealed 新增 UsageEvent;新增 Usage record
│   │   ├── Provider.java           — 修改:stream 加 systemSuffix 形参
│   │   ├── AnthropicProvider.java  — 修改:effectiveSystem(suffix);流结束上抛 Usage
│   │   └── OpenAIProvider.java     — 修改:streamOptions.includeUsage;toOpenAIMessages 拼 suffix;上抛 Usage
│   ├── tool/
│   │   ├── Tool.java               — 修改:接口加 readOnly()
│   │   ├── Registry.java           — 修改:readOnlyDefinitions、isReadOnly
│   │   └── {ReadFileTool,WriteFileTool,EditFileTool,BashTool,GlobTool,GrepTool}.java
│   │                               — 修改:各加 readOnly() 实现
│   ├── agent/
│   │   ├── Agent.java              — 重写:ReAct 循环、Mode、executeBatched、UsageReport/Iter/Notice 事件、历史收尾
│   │   ├── Event.java              — 修改:sealed 接口新增 UsageReport、Iter、Notice 子类型(以及 Usage record)
│   │   ├── Mode.java               — 新增:enum {NORMAL, PLAN}
│   │   └── CancelToken.java        — 新增:per-turn 取消句柄(volatile + 派生 timeout)
│   ├── conversation/
│   │   └── Conversation.java       — 修改:lastRole()
│   ├── prompt/
│   │   └── Prompt.java             — 修改:PLAN_MODE_REMINDER、EXECUTE_DIRECTIVE;SYSTEM_PROMPT 增循环约定
│   └── tui/
│       ├── CortexModel.java             — 修改:字段增 mode/iter/usage/curTools/turnCancel;按键拆分 Esc/Ctrl+C
│       ├── AgentEvent 队列.java         — 修改:onNext 用 switch pattern match 分派 UsageReport/Iter/Notice/Tool/Text
│       └── Styles.java + MarkdownRenderer.java               — 修改:状态栏模式徽标+累计用量;动态区迭代轮次+多并发工具行
├── src/test/java/dev/cortex/
│   ├── agent/AgentTest.java        — 扩展:多轮 fake provider(`List<List<StreamEvent>>` 多次 stream)、并发分批、停止条件、Plan 工具集
│   └── conversation/ConversationTest.java
│                                   — 扩展:lastRole 断言
└── src/test/java/dev/cortex/smoke/SmokeCortex.java
                                    — 修改:Agent.run 调用处补 mode 实参(Mode.NORMAL)
```

> 注：`Cortex.java` 已在 ch03 注入 `ToolRegistry`，ch04 无需改动；`mode` 状态存于 `CortexModel`，不经 `Cortex`。

### 签名变更的调用方清单（实测核对，确保编译不漏）

ch04 改了两个签名，必须同步所有调用方/实现方，否则编译断：

- **`Provider.stream` 增 `String systemSuffix`（第 3 形参）**：
  - 实现方：`AnthropicProvider`、`OpenAIProvider`。
  - 调用方：`Agent.streamOnce`（唯一直接调用方）。
  - 测试实现方：`AgentTest.FakeProvider#stream`（也实现该接口，签名须同步）。
  - **`SmokeMain` 不直接调 `stream`**（它走 `Agent.run`），无需为 `systemSuffix` 改动。
- **`Agent.run` 增 `Mode mode` 与 `CancelToken cancel`（新增第 2、3 形参）**：
  - 调用方：`CortexModel` / `AgentEvent 队列` 内（`onSubmit`）、`SmokeMain`（旧调用 `agent.run(conv)`）、`AgentTest`（各用例）。三者都要补 `mode` / `cancel` 实参（smoke / 旧用例传 `Mode.NORMAL` + `new CancelToken()`）。

## 技术决策

| 决策点                     | 选择                                                                                     | 理由                                                                                                                                                                                                  |
| -------------------------- | ---------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Loop 放哪                  | 重写 `Agent.run` 为循环，签名加 `mode` 与 `CancelToken`                                  | 循环编排天然属 agent 包；TUI 维持纯渲染器。`run` 已返回事件 `BlockingQueue`，循环只是把单轮的两次 `streamOnce` 推广为 `for`，改动收敛在一个包。                                                       |
| 不用 SDK 内置 tool-runner  | 坚持手写循环 + stable streaming                                                          | 沿用 ch03 决策；自写循环才能精确控制停止条件、保序分批、取消与历史收尾，SDK 的自动 runner 把这些黑盒化。                                                                                              |
| 停止条件之「连续未知工具」 | 连续 `MAX_UNKNOWN_RUN=3` 轮「整轮只产生未知工具调用」即停                                | 单次未知工具靠 registry 的「未知工具」结构化错误回灌即可让模型纠偏；只有连续多轮全错才说明在对幻觉工具空转，需兜底。混入任一已注册工具即重置计数（视为有进展）。                                      |
| 迭代上限值                 | `MAX_ITERATIONS=25`，内置常量                                                            | 兜底安全网，避免失控烧 token；25 足够覆盖正常多步任务。spec 明确不配置化，与 ch03 超时不配化一致。                                                                                                    |
| 并发分批粒度               | 「连续只读」合批并发，有副作用单个串行，保持调用序                                       | 用户选定的「保序分批」：read 之后的 write 不会被提前；相邻只读才并发加速。`BashTool` 保守归有副作用（可含任意写操作）。                                                                               |
| 并发原语                   | virtual thread + `CountDownLatch` + 每个 worker 独占下标                                 | virtual thread 起停成本极低、阻塞工具调用天然受益；只写自己下标的 `results[k]` 无需锁；`CountDownLatch` 在主流程汇合。备选 `StructuredTaskScope` 仍为 incubator，先不用。                             |
| 并发的事件顺序             | 开始事件按序、结束事件按序，并发只在执行环节                                             | 满足 N3（scrollback 不交错）：UI 看到的工具行顺序始终是模型调用序；并发对用户透明，只体现为更快。每个 worker 只写自己下标的 `results[k]`，无竞争（N6）。                                              |
| 取消机制                   | per-turn `CancelToken`；Esc / Ctrl+C(streaming) 取消，Ctrl+C(idle) 退出                  | Java 没有 Go 的 ctx 树，自定义轻量 token（`volatile boolean cancelled` + 派生 timeout + 可选回调）即可表达「取消本轮但不退程序」语义。工具与 SDK 订阅在阻塞处轮询 / 调 `subscription.cancel()`。      |
| 取消后历史一致             | 已发起工具补「已取消」结果 + `ensureAssistantTail` 收尾                                  | F6：取消可能停在「assistant 含 tool_use 但缺 tool_result」或「user 之后无 assistant」处；补齐工具结果 + 保证 assistant 文本尾巴，下一轮请求才不会因悬空 tool_use / 连续同角色被 API 拒（400）。       |
| 用量提取位置               | 适配器在流结束后从累加器读 usage 并经 `StreamEvent.UsageEvent` 上抛                      | 两 SDK 的流式 usage 都只在流结束的累加器里完整（Anthropic `MessageAccumulator.message().usage()`、OpenAI 需 `includeUsage=true` 后读累加器 `CompletionUsage`）；逐 delta 不含。统一在 Done 前发一次。 |
| 累计用量口径               | 状态栏显示「会话累计计费 token」= 每轮 input+output 之和                                 | 多轮 Loop 每轮都重发完整历史，各轮 input 重复计费；按轮累加正是实际消耗/成本口径，对用户最有意义。                                                                                                    |
| Plan Mode 系统提示注入     | `Provider.stream` 加 `String systemSuffix` 形参                                          | 系统提示在适配器内注入，要让计划态约束生效必须穿过 `stream`。加一个字符串形参最小且显式；备选「请求 options record」更可扩展但改动面更大，YAGNI 下不引入。                                            |
| Plan Mode 工具集           | 计划态只注入 `readOnlyDefinitions()`                                                     | 物理上不给模型写/执行工具，即便提示被忽略也无法改动；只读分类靠 `Tool.readOnly()`。                                                                                                                   |
| `/do` 语义                 | 切回 Normal + 注入 `EXECUTE_DIRECTIVE` 用户消息 + 立即启动 Loop                          | 用户选定「切回全工具并立即执行」；复用已在历史里的计划，`/do` 不入历史，只把执行指令作为用户消息驱动模型开干。                                                                                        |
| 模式状态存放               | 存于 `CortexModel`，不进 `ConversationManager`                                          | `ConversationManager` 是历史、`messages()` 返回副本，放不住可变模式；模式是会话级 UI 状态，跨轮保持，归 TUI 最自然。                                                                                  |
| 多并发工具的 UI            | `List<ToolDisplay> curTools` 取代单个 `ToolDisplay`                                      | 并发批同时有多个工具在跑，动态区需多行展示；结束事件按序逐个落 scrollback。                                                                                                                           |
| 进度事件                   | 每轮起始 emit `Event.Iter(n)`，UI 显示「第 N 轮」                                        | F9 让用户感知多轮推进；用 sealed 子类型分派，与 ch03 的事件惯例一致。                                                                                                                                 |
| 通知 vs 历史               | 上限/未知工具的提示同时 emit `Event.Notice` 与 `ensureAssistantTail` 写入 assistant 历史 | UI 要让用户看到为何停；写入历史是为满足 `ensureAssistantTail`（角色交替），二者用同一文案，避免历史里留空 assistant 回合。                                                                            |
