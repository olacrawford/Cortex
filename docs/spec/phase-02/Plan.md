# 工具系统 Plan

> 基于已批准的 spec.md。本文档与语言相关（Java 21 / Gradle）。SDK 类型已对 `com.anthropic:anthropic-java` 2.x、`com.openai:openai-java` 4.x 实测核对。

## 架构概览

在 ch02「provider → conversation → tui」三件套之上，新增两个包并扩展三处：

- **`com.cortex.tool`（新建）**：统一工具抽象 `Tool`、执行结果 `ToolResult`、注册中心 `ToolRegistry`、6 个核心工具。零外部依赖（仅 JDK + Jackson，沿用 SDK 已有传递依赖），不感知 LLM 协议。
- **`com.cortex.agent`（新建）**：承载「单轮闭环」编排——请求#1（带工具）→ 收集工具调用 → 注册中心执行 → 结果回灌进 `ConversationManager` → 请求#2（续答）→ 最终文本 → 停。对外吐出一条 `BlockingQueue<AgentEvent>` 供 TUI 订阅渲染。只依赖 `llm`、`tool`、`conversation`，不 import anthropic-java / openai-java，保持协议无关。
- **`com.cortex.llm`（扩展）**：`Message`/`StreamEvent` 增加工具变体；新增协议无关类型 `ToolCall`/`ToolResult`/`Map<String, Object>/*tool schema*/` 与 `Role.TOOL` 枚举值；`Provider.stream` 增加 `tools` 参数；两个适配器注入工具定义、解析流式工具调用、回灌工具结果。
- **`com.cortex.conversation`（扩展）**：新增「assistant 工具调用回合」与「工具结果回合」的追加方法。
- **`com.cortex.prompt`（扩展）**：`SYSTEM_PROMPT` 增补 Agent 角色与工具使用约定。
- **`com.cortex.tui`（扩展）**：提交逻辑改走 `Agent.run`；订阅 `BlockingQueue<agent.Event>` 处理工具事件；渲染 Claude Code 风格工具行与执行指示。
- **`com.cortex.Cortex`（扩展）**：构造 `ToolRegistry.createDefault()` 并注入 `CortexModel`。

依赖方向（无环）：`tool → llm`；`conversation → llm`；`agent → {llm, tool, conversation}`；`tui → {agent, tool, conversation, llm, prompt}`；`llm → {config, prompt}`。

## 核心数据结构

### llm 包（扩展）

```java
package com.cortex.llm;

// Role 增加 TOOL：携带工具执行结果的回合。
public enum Role { USER, ASSISTANT, TOOL }

// ToolCall 协议无关地承载模型发起的一次工具调用（流式拼接完成后）。
public // ToolCallComplete(toolId, toolName, arguments) — 
        String id,          // provider 侧调用 id；回灌结果时配对
        String name,        // 工具名（注册中心按名查找）
        String args    // 拼接完成的 JSON 参数（原始字符串）
) {}

// ToolResult 协议无关地承载一次工具执行结果。
public record ToolResult(
        String toolCallId,  // 对应 ToolCall.id
        String content,     // 执行产出（成功内容或结构化错误文本）
        boolean isError     // 是否为错误结果（F9）
) {}

// Map<String, Object>/*tool schema*/ 注册中心导出的协议无关工具定义。
public record Map<String, Object>/*tool schema*/(
        String name,
        String description,
        Map<String, Object> inputSchema   // 完整 JSON Schema 对象：type/properties/required
) {}

// Message 扩展：assistant 回合可带 toolCalls；TOOL 回合带 toolResults。
public record Message(
        Role role,
        String content,
        List<ToolCall> toolCalls,       // 仅 assistant：本回合请求的工具调用，可空
        List<ToolResult> toolResults    // 仅 TOOL：工具执行结果，一条消息可含多个
) {
    public static Message user(String text)      { return new Message(Role.USER, text, List.of(), List.of()); }
    public static Message assistant(String text) { return new Message(Role.ASSISTANT, text, List.of(), List.of()); }
}

// StreamEvent 扩展：在 TextDelta/Done/Failed 之外，新增 ToolCallComplete。
public sealed interface StreamEvent
        permits AgentEvent.StreamText, AgentEvent.ToolUseEvent, AgentEvent.LoopComplete, AgentEvent.ErrorEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ToolCallStart(toolId, toolName) / ToolCallDelta(text) / ToolCallComplete(toolId, toolName, arguments) implements StreamEvent {}  // Done 之前发出
    record Done() implements StreamEvent {}
    record Failed(Throwable error) implements StreamEvent {}
}
```

`Provider.stream` 签名变更：

```java
BlockingQueue<StreamEvent> stream(List<Message> messages, List<Map<String, Object>/*tool schema*/> tools);
```

`tools` 为空表示本次请求不带工具。续答请求（请求#2）仍传入 `tools`（与真实协议一致），但编排层忽略其再次返回的工具调用（单轮）。

### tool 包（新建）

```java
package com.cortex.tool;

// Result 工具执行结果——永远以值类型返回，从不抛 checked exception。
public record ToolResult(String output, boolean isError) {
    public static Result ok(String content)    { return new Result(content, false); }
    public static Result error(String content) { return new Result(content, true);  }
}

// Tool 统一工具抽象（F1）。
public interface Tool {
    String name();                           // 模型看到的工具名，如 "ReadFile"
    String description();                    // 给模型的用途说明
    Map<String, Object> schema();        // 手写 JSON Schema（type/properties/required/description）
    ToolResult execute(Map<String, Object> args);  // 解析失败/IO 失败一律包成 ToolResult.error(...)
}

// // 取消信号通过 virtual thread 的 Thread.interrupt() 传递。
public // 取消机制：virtual thread 的 Thread.interrupt()

// Registry 集中登记、按名查找、导出定义、按名执行。
public final class ToolRegistry {
    private final List<String> order = new ArrayList<>();        // 保持注册顺序，导出稳定
    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool t)                                   { ... }
    public Optional<Tool> get(String name)                         { ... }
    public List<Map<String, Object>> getAllSchemas(String protocol)                       { ... }  // F3/AC1：按序导出
    public ToolResult execute(Map<String, Object> args, String name, String args) { ... } // F5/F9：未知工具兜底为 error

    public static Registry createDefault() { ... }  // 构造并注册 6 个工具

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30); // N1，不可配
}
```

每个工具私有入参 record，`execute` 内用 Jackson 把 `args` 反序列化到该 record，解析失败转为 `ToolResult.error(...)`：

| 工具名 | 参数（JSON Schema） | 成功结果 | 错误结果 |
|--------|--------------------|---------|---------|
| `ReadFile` | `path`(必填) | 带行号文本（`%6d\t` 风格，≤2000 行 / ≤256KB，超出截断标注 `[truncated]`） | 不存在/不可读/是目录 |
| `WriteFile` | `path`(必填)、`content`(必填) | `Files.createDirectories(...)` 建父目录后 `Files.writeString(...)` 覆盖，返回路径与字节数 | 写入失败 |
| `EditFile` | `path`、`old_string`、`new_string`(均必填) | 子串出现次数==1 时唯一替换并写回 | 0 处→「未找到匹配」；>1 处→「匹配到 N 处，old_string 不唯一，请提供更长上下文」 |
| `Bash` | `command`(必填) | 按平台选 shell（Unix `sh -c` / Windows `cmd /C`）`ProcessBuilder` 执行；带 `Process.waitFor(timeout)` 超时；返回 stdout/stderr/exit_code（合并视图截断 ~30000 字符） | 超时（isError）；命令非零退出按结果回灌 |
| `Glob` | `pattern`(必填，如 `**/*.java`)、`path`(可选，默认 cwd) | 匹配路径列表（≤100，排序） | 无匹配返回空说明（非 isError） |
| `Grep` | `pattern`(必填，Java `Pattern` 正则)、`path`(可选)、`Glob`(可选文件名过滤) | `file:line:content` 列表（≤100，超出标注） | 正则非法（isError）；无命中返回空说明 |

### agent 包（新建）

```java
package com.cortex.agent;

import java.util.concurrent.Flow;

// Agent 持有 provider 与注册中心，执行单轮闭环。
public final class Agent {
    private final Provider provider;
    private final ToolRegistry registry;
    public Agent(LlmClient client, ToolRegistry registry) { ... }

    // 执行单轮闭环，返回事件 Publisher。订阅者一般是 TUI 的 EventPump。
    public BlockingQueue<AgentEvent> run(ConversationManager conv) { ... }
}

// Phase 工具事件阶段。
public enum Phase { START, END }

// ToolEvent 一次工具调用的开始/结束（供 TUI 渲染工具行与结果摘要）。
public record ToolEvent(
        String name,
        String args,     // 参数预览（用于 ● name(args)）
        Phase phase,
        String result,   // END：结果摘要
        boolean isError  // END：是否错误
) {}

// Event 单轮闭环对外事件流；sealed 让 TUI 按变体分派渲染。
public sealed interface AgentEvent
        permits AgentEvent.StreamText, AgentEvent.ToolResultEvent, AgentEvent.LoopComplete, AgentEvent.ErrorEvent {
    record Text(String delta) implements Event {}             // 文本增量（preamble 或最终答复）
    record Tool(ToolEvent event) implements Event {}          // 工具调用开始/结束
    record Done() implements Event {}                          // 本轮结束
    record Failed(Throwable error) implements Event {}         // 出错（不中断会话）
}
```

`Agent.run` 内部用 `BlockingQueue<AgentEvent>` 作为输出端，开一个 virtual thread 跑「请求#1 → 执行工具 → 请求#2」整条链路，sub-publisher 订阅与等待逻辑都在 virtual thread 内同步推进。

## 模块设计

### com.cortex.tool

**职责：** 提供 6 个工具的统一抽象与执行；集中登记与导出；所有失败包成 `ToolResult.error(...)` 而非抛异常（F1/F2/F9/N4）。
**对外接口：** `Tool`、`Result`、`ToolRegistry`、`Registry.defaultRegistry`。
**依赖：** JDK（`java.nio.file`、`java.util.regex`、`java.lang.ProcessBuilder`、`java.util.concurrent`）、Jackson（解析参数 JSON，跟随 anthropic-java/openai-java 已有依赖，不新增坐标）、`com.cortex.llm`（仅为 `definitions()` 返回 `List<Map<String, Object>/*tool schema*/>`）。
**关键实现点：**
- Schema 手写为 `Map<String, Object>`（`LinkedHashMap` 保序）：OpenAI 直接整体传给 `FunctionDefinition.parameters(...)`；Anthropic 由 llm 适配器取 `"properties"`/`"required"`。
- `ReadFile` 带行号、行/字节上限、`[truncated]` 标注（N5/AC2）。`Files.readString` 后按 `\n` 切分，超过 2000 行截断。
- `EditFile` 唯一匹配语义 + 含计数的可区分错误（AC4）；用 `content.split(oldString, -1).length - 1` 统计次数。
- `Bash` 用 `ProcessBuilder`：Windows `new ProcessBuilder("cmd","/C",cmd)`，其余 `new ProcessBuilder("sh","-c",cmd)`；`redirectErrorStream(true)` 合并；`process.waitFor(DEFAULT_TIMEOUT.toMillis(), MILLISECONDS)` 超时 → `process.destroyForcibly()` 后 `ToolResult.error("命令超时")`（AC5/N1）。捕获 stdout 用单独 virtual thread 读取 `getInputStream()` 避免管道阻塞。
- `Glob`：基于 `Files.walk` + 自实现段匹配（Java `PathMatcher` 的 `glob:` 语法对 `**` 仅支持目录单层，需自写跨任意层级版本）。
- `Grep`：`Pattern.compile(...)` 失败 → `Result.error`；`Files.walk` 遍历 + `Files.lines` 逐行扫；循环中检查 `ctx.cancelled().get()`。
- 空 `args`（OpenAI 可能给空串而非 `"{}"`）按 `"{}"` 处理，避免误报参数错误。

### com.cortex.agent

**职责：** 单轮闭环编排（F5/F6），保证 AC9 单轮上限；把 provider 的 `StreamEvent` 与工具执行翻译成统一 `Event` 流。
**对外接口：** `Agent`、`Event`、`ToolEvent`、`Phase`。
**依赖：** `llm`、`tool`、`conversation`、JDK `java.util.concurrent.Flow` / `BlockingQueue`。
**run 算法（在 virtual thread 内顺序执行）：**
1. `var defs = registry.getAllSchemas(protocol);`
2. **请求#1**：`streamOnce(conv, defs, out)` → 转发 `TextDelta` 到 `out` 上的 `AgentEvent.StreamText`、累积完整 preamble 文本、收集 `ToolCallComplete`；失败发 `AgentEvent.ErrorEvent` 后结束。
3. 若无 `ToolCallComplete`：`conv.addAssistant(preamble)`，发 `AgentEvent.LoopComplete`，结束（纯文本回合，与 ch02 等价）。
4. 有 `ToolCallComplete`：`conv.addAssistantWithToolCallComplete(preamble, calls)`。
5. 顺序执行每个 call：发 `AgentEvent.ToolResultEvent(START, name, args)` → `` → 提交 virtual thread 执行 `StreamingExecutor.executeAll(calls)` 并 `Future.get(DEFAULT_TIMEOUT)`；超时 → `Thread.interrupt()` + 返回超时 `ToolResult.error("工具执行超时")` → 发 `AgentEvent.ToolResultEvent(END, name, result, isError)` → 收集 ToolResultBlock(toolId, output, isError)。
6. `conv.addToolResults(results)`.
7. **请求#2**：`streamOnce(...)` → 转发最终答复 `AgentEvent.StreamText`、累积 final 文本；**忽略**其返回的任何 `ToolCallComplete`（单轮，AC9）。
8. `conv.addAssistant(final)`，发 `AgentEvent.LoopComplete`。
- 订阅取消（TUI 退出 / Ctrl+C）时 `BlockingQueue.close()`，virtual thread 通过 `Thread.interrupted()` 退出每个阶段；工具执行 future 也会被 `cancel(true)` 中断。

### com.cortex.llm（扩展）

**职责：** 协议无关请求/响应抽象 + 两协议工具调用全流程（F3/F4/F6/F7）。

**AnthropicProvider 关键改动：**
- 请求构造加 `params.tools(toAnthropicTools(tools))`：每项 `ToolUnion.ofTool(Tool.builder().name(d.name()).description(d.description()).inputSchema(JsonObject.fromString(jackson.writeValueAsString(d.inputSchema()))).build())`。
- 流式订阅累加：保留一个 `MessageAccumulator acc = MessageAccumulator.create();`，每次回调 `acc.accumulate(event)`；文本 delta 仍上抛，`InputJsonDelta`/`ThinkingDelta` 不上抛（由 accumulator 缓冲/丢弃）。
- 流结束后若 `acc.finalMessage().stopReason()==StopReason.TOOL_USE`：遍历 `acc.finalMessage().content()`，对 `ContentBlock.ToolUse` 收集 `ToolUseBlock(id, name, input)`，先发 `AgentEvent.ToolUseEvent`，再发 `AgentEvent.LoopComplete`。
- `toAnthropicMessages` 扩展：assistant 回合若有 `toolCalls`，内容数组里追加 `ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(c.id()).name(c.name()).input(JsonObject.fromString(c.args())).build())`（可与文本块共存）；`Role.TOOL` 回合把每个 `ToolResult` 用 `ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(r.toolCallId()).content(r.content()).isError(r.isError()).build())` 拼进**一条 user 消息**（Anthropic 协议要求 tool_result 由 user 角色提交）。

**OpenAIProvider 关键改动：**
- 请求构造加 `params.tools(toOpenAITools(tools))`：每项 `ChatCompletionTool.ofFunction(FunctionDefinition.builder().name(d.name()).description(d.description()).parameters(FunctionParameters.fromMap(d.inputSchema())).build())`。
- 流式订阅累加：用 `ChatCompletionAccumulator acc = ChatCompletionAccumulator.create();` 每次 `acc.accumulate(chunk)`；`chunk.choices().get(0).delta().content()` 非空时仍上抛 `TextDelta`。
- 流结束后读 `acc.chatCompletion().choices().get(0).message().toolCalls()`（不依赖 `JustFinishedToolCall` 之类流式辅助类，因其在多工具下不可靠）；非空则组 `ToolUseBlock(tc.id(), tc.function().name(), tc.function().arguments())`，先发 `AgentEvent.ToolUseEvent` 再发 `AgentEvent.LoopComplete`。`finishReason()=="tool_calls"` 与 acc 是否含工具调用同时判定（兼容端点兜底）。
- `toOpenAIMessages` 扩展：assistant 回合若有 `toolCalls`，**手工**构造 `ChatCompletionAssistantMessageParam.builder().content(text).toolCalls(List.of(ChatCompletionMessageToolCall.ofFunction(...))).build()`（`AssistantMessage(string)` 助手便捷构造不携带工具调用）；`Role.TOOL` 回合每个 `ToolResult` 发一条 `ChatCompletionToolMessageParam.builder().toolCallId(r.toolCallId()).content(r.content()).build()`。

### com.cortex.conversation（扩展）

```java
public void addAssistantWithToolCallComplete(String text, List<ToolCall> calls); // assistant 工具调用回合
public void addToolResults(List<ToolResult> results);                       // Role.TOOL 结果回合
```
保留 `addUser`/`addAssistant`/`messages` 不变。

### com.cortex.tui（扩展）

**职责：** 渲染 `agent.Event`（文本/工具行/结果摘要/错误/结束），保持非阻塞（N2）。
- `CortexModel` 构造增加 `ToolRegistry registry` 字段；新 `submit` 流程：`conv.addUser(text)` 后 `new Agent(client, registry, protocol, selectedProvider).run(conv).subscribe(eventPump)`（不再直接调 `Provider.stream`）。
- 新增 `EventPump implements BlockingQueue.poll()<agent.Event>`：`onNext` 调 `program.send(new AgentEventMessage(...)` 切回 UI 线程；`onError` 包成 `AgentEvent.ErrorEvent` 走同一路径；`onComplete` 不处理（`Done` 由 `onNext` 提前发出）。
- 新字段 `curTool: ToolDisplay`（执行中指示：`record ToolDisplay(String name, String args) {}`，非空即渲染执行行）。
- `EventPump.handleEvent` 按 `sealed` 分派：
  - `AgentEvent.StreamText(delta)`：追加 `curReply`，刷新 `streamingLabel`。
  - `AgentEvent.ToolResultEvent(t)` `phase==START`：若 `curReply` 非空，先把 preamble 作为 assistant 块写入 `scrollback` 并清空 `curReply`；置 `curTool = new ToolDisplay(t.name(), t.args())`。
  - `AgentEvent.ToolResultEvent(t)` `phase==END`：把 `View.toolLine(name,args)` 与 `View.toolResultSummary(result,isError)` 顺序追加到 `scrollback`；清 `curTool`。
  - `AgentEvent.LoopComplete`：把 `curReply`（最终答复）经 `View.renderMarkdown` 写入 `scrollback`；`finishTurn`（停 spinner、回 IDLE）。
  - `AgentEvent.ErrorEvent`：`scrollback` 追加 `View.errorBlock(err)`；`finishTurn`。
- `View` 新增：`toolLine(name, args)`（青/绿 `●` + `name(args)` 的 JLine/tui.tea `Label`）、`toolResultSummary(result, isError)`（缩进 `  ⎿ `、灰/红，UI 截断 ~8 行）。`View.statusLine` 在 `curTool != null` 时渲染「`● name(args)` + spinner Running…」，否则沿用 ch02 的「Imagining… (Ns)」。
- 追加块的顺序由 `scrollback` Panel 的 `LinearLayout` 自然保序——只要 `program.send()` 在 UI 线程同步依次 `addComponent`，preamble → 工具行 → 结果摘要 → 最终答复就按序出现。

## 模块交互

```
用户提交
  └─ CortexModel.submit: conv.addUser(text); var pub = new Agent(client, registry, protocol, selectedProvider).run(conv); pub.subscribe(eventPump)
       └─ Agent.run (virtual thread):
            ├─ 请求#1: provider.stream(conv.messages(), registry.getAllSchemas(protocol))
            │     └─ 适配器: 注入 tools → 流式 acc.accumulate → AgentEvent.StreamText / AgentEvent.ToolUseEvent
            │     → Agent 转发 AgentEvent.StreamText(preamble)，收集 calls
            ├─ 无 calls → conv.addAssistant(preamble); AgentEvent.LoopComplete
            └─ 有 calls:
                 ├─ conv.addAssistantWithToolCallComplete(preamble, calls)
                 ├─ for call: AgentEvent.ToolResultEvent(START) → Future<Result> get(超时) → AgentEvent.ToolResultEvent(END)
                 ├─ conv.addToolResults(results)
                 ├─ 请求#2: provider.stream(conv.messages(), defs) → AgentEvent.StreamText(最终答复)
                 │     （适配器把 conv 里的 tool_use/tool_result 回合映射为各自协议格式）
                 └─ conv.addAssistant(final); AgentEvent.LoopComplete
  └─ EventPump.onNext → program.send() → 按 sealed Event 变体渲染（curReply 动态区 / scrollback 追加块）
```

并发：`conv` 在每个时刻只被一个线程触碰——`submit` 在交给 `Agent.run` 前 `addUser`，之后不再触碰；virtual thread 独占后续所有 `conv` 变更。`messages()` 返回 `List.copyOf(...)` 不可变副本。TUI 仅按事件渲染，`curReply` 为自身显示缓冲，与 `conv` 互不干扰（N2）。

## 文件组织

```
cortex/
├── build.gradle.kts                                       — 修改：依赖与 ch02 一致；新增模块不引入新坐标
├── src/main/java/com/cortex/
│   ├── Cortex.java                                 — 修改：构造 ToolRegistry.createDefault() 注入 CortexModel
│   ├── llm/
│   │   ├── Message.java                          — 修改：增 toolCalls/toolResults 字段（保留便捷工厂）
│   │   ├── StreamEvent.java                      — 修改：sealed 增 ToolCallComplete 变体
│   │   ├── Provider.java                         — 修改：stream(...) 加 tools 参数
│   │   ├── ToolCall.java                         — 新建：record
│   │   ├── ToolResult.java                       — 新建：record
│   │   ├── Map<String, Object>/*tool schema*/.java                   — 新建：record
│   │   ├── AnthropicProvider.java                — 修改：toAnthropicTools；MessageAccumulator 解析；stop_reason 上抛 ToolCallComplete；toAnthropicMessages 支持 tool_use/tool_result
│   │   └── OpenAIProvider.java                   — 修改：toOpenAITools；ChatCompletionAccumulator 解析；finish_reason 上抛 ToolCallComplete；toOpenAIMessages 支持 assistant.tool_calls / tool 消息
│   ├── tool/                                     — 新建
│   │   ├── Tool.java                             — 接口
│   │   ├── Result.java                           — record
│   │   ├── Registry.java                         — register/get/definitions/execute/defaultRegistry/DEFAULT_TIMEOUT
│   │   ├── ReadFileTool.java
│   │   ├── WriteFileTool.java
│   │   ├── EditFileTool.java
│   │   ├── BashTool.java
│   │   ├── GlobTool.java
│   │   ├── GrepTool.java
│   │   └── Truncate.java                         — 工具函数：行/字节截断，尾部加 [truncated]
│   ├── agent/                                    — 新建
│   │   ├── Agent.java                            — run() 单轮闭环
│   │   ├── Event.java                            — sealed Event
│   │   ├── ToolEvent.java                        — record
│   │   └── Phase.java                            — enum
│   ├── conversation/
│   │   └── Conversation.java                     — 修改：addAssistantWithToolCallComplete、addToolResults
│   ├── prompt/
│   │   └── Prompt.java                           — 修改：SYSTEM_PROMPT 增 Agent 角色与工具约定
│   └── tui/
│       ├── CortexModel.java                           — 修改：构造接 registry；submit 走 Agent.run
│       ├── EventPump.java                        — 新建（替代 ch02 的 AgentEvent 队列）：BlockingQueue.poll()<agent.Event>
│       └── Styles.java + MarkdownRenderer.java                             — 修改：toolLine / toolResultSummary；执行行渲染
└── src/test/java/dev/cortex/
    ├── tool/RegistryTest.java                    — 注册中心 + 各工具单测
    └── agent/AgentTest.java                      — fake Provider 驱动单轮闭环（AC8/AC9）
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 工具调用循环放哪 | 新建 `com.cortex.agent` 包，TUI 退化为渲染器 | 循环（请求#1→执行→请求#2）无法塞进 ch02 的一次性 `AgentEvent 队列.handleEvent`；独立包可无 UI 单测（AC8/AC9），只依赖 llm+tool+conversation，不泄漏 SDK 类型。命名 `agent` 而非 `runner`：概念即 Agent，本章恰为单轮。 |
| 是否用 SDK 的 Beta tool-runner | 不用，坚持稳定 streaming + 手动单轮 | anthropic-java 的 `ToolRunner` 会自动连环到完成，违反 F6/AC9；且引入 Beta 类型与 ch02 stable 代码不一致。 |
| 工具定义传入哪一层 | `Provider.stream` 第二参数 `List<Map<String, Object>/*tool schema*/>` | 两 SDK 都把 tools 放 per-request params；续答仍需带；保持 Provider 无状态。 |
| 工具参数 Schema 生成 | 每工具手写 `Map<String,Object>`（`LinkedHashMap` 保序） | OpenAI `FunctionParameters.fromMap(...)` 直接用；Anthropic 取 `"properties"`/`"required"`。6 个固定工具手写最直白，描述对模型可读性最关键；不引入 schema 生成库（带 `$id`/`$defs` 还要剥离）。 |
| 流式工具参数拼接 | Anthropic 用 `MessageAccumulator`；OpenAI 用 `ChatCompletionAccumulator` 后读 `message().toolCalls()` | SDK 自带累加器处理分片，避免手写 PartialJSON / 按-index 拼接边界；OpenAI 不依赖 `JustFinishedToolCall`（多工具下不可靠）。 |
| Glob/Grep 实现 | 纯 Java（`Files.walk` + 自实现 `**` / `java.util.regex`） | 零额外依赖、跨平台（Windows 无 grep/rg）；`PathMatcher` 的 `glob:**` 仅支持单层目录，必须自写。 |
| Bash 实现与超时 | 按 `System.getProperty("os.name")` 选 shell（Unix `sh -c` / Windows `cmd /C`）+ `Process.waitFor(timeout)` 30s 超时 | `sh -c`/`cmd /C` 各自支持管道/重定向；`waitFor(timeout)` 超时 → `destroyForcibly()` 杀进程。30s 内置不可配（spec：超时不配置化）。跨平台兼容。stdout 用 virtual thread 异步读避免管道阻塞。 |
| 工具失败的表达 | `execute` 返回 `Result(content, isError)`，从不抛 checked exception | F9/N4：所有失败包成结构化结果回灌，程序不崩，上层无需区分异常路径。 |
| 工具结果在 Message 的形态 | 平铺 `List`（assistant 加 `toolCalls`，`Role.TOOL` 加 `toolResults`） | 两 SDK 工具语义本就是 id 关联的 tool_use/tool_result 列表；通用 content-block 联合属过度设计（本章结果均文本）。适配器吸收差异（Anthropic 结果进 user 消息、OpenAI 用 tool 角色）。 |
| UI 截断 vs 回灌截断 | 两者分离：UI 摘要 ~8 行；回灌为工具级上限（read 2000 行 / bash 30000 字符 等） | AC11/N5 要界面截断，但模型需较完整内容；尾部统一加 `[truncated]` 标注。 |
| 续答请求是否带 tools | 带，但忽略其返回的工具调用 | 与真实协议一致（OpenAI assistant+tool 后不带 tools 也可，但带更稳）；F6/AC9 由 Agent 不再触发执行来保证单轮。 |
| thinking 与工具组合 | 历史含工具交互的请求（续答）不启用 thinking | Anthropic 在 thinking 启用时要求回灌带 tool_use 的 assistant 回合附原 thinking 块（含 signature），而本章按 spec 丢弃 thinking 增量、不留签名；故对这类请求关闭 thinking 以避免 400。 |
| 空最终答复 | 续答为空（仅请求工具被丢弃 / 空完成）时用单轮提示占位并推给 UI | 空 assistant 回合会破坏下一轮请求（Anthropic 要求非空内容 + 角色交替）；占位提示同时满足 AC9 的"单轮上限提示"。 |
| 空参数归一 | OpenAI 侧空 `arguments` 归一为 `"{}"` | 无参工具的 arguments 可能为空串，回灌时须是合法 JSON，否则严格兼容端点对 `"arguments":""` 返回 400。 |
| grep 超长行 | 显式标注未完整搜索 | `Files.lines` 对 >1MB 行会 OOM 或被默认 charset decoder 拒掉；用 `BufferedReader` 限行长（如 1MB），溢出时跳过并尾部标注，避免假"无命中"误导模型。 |
| scrollback 顺序提交 | `program.send()` 内同步多次 `panel.addComponent(...)` | JLine/tui.tea `MultiWindowTextGUI` 的 GUI 线程是单线程串行执行 `program.send()` 任务，一次任务里依次 `addComponent` 即可顺序追加，无需额外屏障。 |
| 工具命名 | `ReadFile`/`WriteFile`/`EditFile`/`Bash`/`Glob`/`Grep` | 符合 OpenAI 函数名规则（`a-zA-Z0-9_-`）与 Claude Code 习惯；TUI 工具行显示 `● name(关键参数)`。 |