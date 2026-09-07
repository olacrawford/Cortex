# 工具系统 Tasks

> 基于已批准的 spec.md + plan.md。任务有序，每步留绿编译。验证一律「先跑命令看输出，再下结论」。顶层包 `com.cortex`（Java 21 / Gradle）。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `src/main/java/com/cortex/llm/Message.java` | 增 toolCalls/toolResults 字段与便捷工厂 |
| 修改 | `src/main/java/com/cortex/llm/StreamEvent.java` | sealed 增 ToolCallComplete 变体 |
| 修改 | `src/main/java/com/cortex/llm/Provider.java` | stream(...) 加 tools 参数 |
| 新建 | `src/main/java/com/cortex/llm/ToolCall.java` | record |
| 新建 | `src/main/java/com/cortex/llm/ToolResult.java` | record |
| 新建 | `src/main/java/com/cortex/llm/Map<String, Object>/*tool schema*/.java` | record |
| 修改 | `src/main/java/com/cortex/llm/AnthropicProvider.java` | 注入 tools、MessageAccumulator 解析、tool_use/tool_result 回灌 |
| 修改 | `src/main/java/com/cortex/llm/OpenAIProvider.java` | 注入 tools、ChatCompletionAccumulator 解析、assistant.tool_calls/tool 消息回灌 |
| 新建 | `src/main/java/com/cortex/tool/Tool.java` | 接口 |
| 新建 | `src/main/java/com/cortex/tool/Result.java` | record |
| 新建 | `src/main/java/com/cortex/tool/Truncate.java` | 行/字节截断工具函数 |
| 新建 | `src/main/java/com/cortex/tool/Registry.java` | register/get/definitions/execute/defaultRegistry/DEFAULT_TIMEOUT |
| 新建 | `src/main/java/com/cortex/tool/{ReadFile,WriteFile,EditFile,Bash,Glob,Grep}Tool.java` | 6 个核心工具 |
| 新建 | `src/test/java/dev/cortex/tool/RegistryTest.java` | 注册中心 + 各工具单测 |
| 新建 | `src/main/java/com/cortex/agent/Phase.java` | enum |
| 新建 | `src/main/java/com/cortex/agent/ToolEvent.java` | record |
| 新建 | `src/main/java/com/cortex/agent/Event.java` | sealed |
| 新建 | `src/main/java/com/cortex/agent/Agent.java` | run() 单轮闭环 |
| 新建 | `src/test/java/dev/cortex/agent/AgentTest.java` | fake Provider 单轮闭环（AC8/AC9） |
| 修改 | `src/main/java/com/cortex/conversation/Conversation.java` | addAssistantWithToolCallComplete、addToolResults |
| 修改 | `src/main/java/com/cortex/prompt/Prompt.java` | SYSTEM_PROMPT 增 Agent 角色与工具约定 |
| 修改 | `src/main/java/com/cortex/tui/CortexModel.java` | 构造接 registry、submit 走 Agent.run |
| 新建 | `src/main/java/com/cortex/tui/EventPump.java` | BlockingQueue.poll()<agent.Event>（替代 AgentEvent 队列） |
| 修改 | `src/main/java/com/cortex/tui/Styles.java + MarkdownRenderer.java` | toolLine / toolResultSummary / 执行行 |
| 修改 | `src/main/java/com/cortex/Cortex.java` | 构造 Registry.defaultRegistry 注入 CortexModel |

## T1: 扩展 llm 协议无关类型

**文件：** `src/main/java/com/cortex/llm/{Message,StreamEvent,ToolCall,ToolResult,Map<String, Object>/*tool schema*/}.java`
**依赖：** 无
**步骤：**
1. 新建 `ToolCall(String id, String name, String args)`、`ToolResult(String toolCallId, String content, boolean isError)`、`Map<String, Object>/*tool schema*/(String name, String description, Map<String,Object> inputSchema)` 三个 record（各带中文注释）。
2. `Role` 枚举增加 `TOOL` 值（携带工具执行结果的回合）。
3. 重写 `Message` record：增字段 `List<ToolCall> toolCalls`、`List<ToolResult> toolResults`；保留便捷静态工厂 `Message.user(text)` / `Message.assistant(text)` 用 `List.of()` 填空。
4. 重写 `StreamEvent` sealed：新增 `record ToolCallStart(toolId, toolName) / ToolCallDelta(text) / ToolCallComplete(toolId, toolName, arguments) implements StreamEvent {}` 成员，permits 列表追加。更新 javadoc 说明四态语义。

**验证：** `./gradlew -q -DskipTests compile` 通过（此步只加字段/类型，Provider 签名先不动，向后兼容）。

## T2: tool 接口与注册中心骨架

**文件：** `src/main/java/com/cortex/tool/{Tool,Result,,Truncate,Registry}.java`
**依赖：** T1
**步骤：**
1. `Result.java`：record `Result(String content, boolean isError)`，加静态工厂 `ok(...)` / `error(...)`。
2. `Tool.java`：interface `Tool { String name(); String description(); Map<String,Object> schema(); ToolResult execute(Map<String, Object> args); }`。
4. `Truncate.java`：`public static String byLinesAndBytes(String s, int maxLines, int maxBytes)`，超出尾部加 `\n[truncated]`。
5. `Registry.java`：字段 `List<String> order`、`Map<String,Tool> tools`；方法 `register(Tool)`、`Optional<Tool> get(String)`、`List<Map<String, Object>> getAllSchemas(String protocol)`（按 order 把每工具 name/description/parameters 组成 `Map<String, Object>/*tool schema*/`）、`ToolResult execute(, String name, String args)`（`get` 未命中返回 `ToolResult.error("未知工具: " + name)`，命中则调 `t.execute(ctx, args)`，`args` 为 null/空串归一为 `"{}"`）；常量 `Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30)`。**暂不写** `createDefault()`。

**验证：** `./gradlew -q -DskipTests compile` 通过。

## T3: read_file 工具

**文件：** `src/main/java/com/cortex/tool/ReadFileTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `ReadFileArgs(String path)`；类实现 `Tool`。
2. `schema()` 返回手写 schema：`type:object`、`properties.path{type:string, description:"要读取的文件路径"}`、`required:["path"]`（`LinkedHashMap`）。
3. `execute`：用 Jackson `ObjectMapper.readValue` 解析参数（空 args 当 `{}`）；`Files.readString(Path.of(args.path()))`；`Files.isDirectory(...)` / 不存在 / `AccessDeniedException` 等 → `ToolResult.error(...)`；成功按行加行号（`String.format("%6d\t%s", lineNo, line)`），经 `Truncate.byLinesAndBytes(s, 2000, 256*1024)`。

**验证：** `./gradlew compileJava`；手测读本文件出现行号、读不存在文件得 isError（在 T9 补单测后跑完整 `./gradlew test -Dtest=RegistryTest#readFile*`）。

## T4: write_file 工具

**文件：** `src/main/java/com/cortex/tool/WriteFileTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `WriteFileArgs(String path, String content)`；类实现 `Tool`。
2. `schema()`：`path`、`content` 均必填。
3. `execute`：`Files.createDirectories(p.getParent())` 后 `Files.writeString(p, content)`（覆盖）；成功返回 `ToolResult.success("已写入 " + path + "（" + bytes + " 字节）")`；任何 `IOException` → `ToolResult.error(e.getMessage())`。

**验证：** `./gradlew compileJava`；T9 后单测写嵌套路径检查磁盘。

## T5: edit_file 工具

**文件：** `src/main/java/com/cortex/tool/EditFileTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `EditFileArgs(String path, String oldString, String newString)`；类实现 `Tool`。
2. `schema()`：三字段必填，描述说明唯一匹配语义。
3. `execute`：读文件失败 isError；`int n = countOccurrences(content, old);`（`split(Pattern.quote(old), -1).length - 1`）；`n==0`→`ToolResult.error("未找到匹配的内容")`；`n>1`→`ToolResult.error("匹配到 %d 处，old_string 不唯一，请提供更长上下文使其唯一".formatted(n))`；`n==1`→`content.replace(old, newStr)` 后 `Files.writeString(...)`，返回成功。

**验证：** `./gradlew compileJava`；T9 后单测覆盖 0/1/多三情形。

## T6: bash 工具

**文件：** `src/main/java/com/cortex/tool/BashTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `BashArgs(String command)`；类实现 `Tool`。
2. `schema()`：`command` 必填。
3. `execute`：按 `System.getProperty("os.name").toLowerCase().contains("win")` 选 shell——Windows `new ProcessBuilder("cmd","/C",cmd)`，其余 `new ProcessBuilder("sh","-c",cmd)`；`redirectErrorStream(true)`；启动后用 `Thread.ofVirtual().start(...)` 异步读 `process.getInputStream()` 到 `ByteArrayOutputStream`；`process.waitFor(DEFAULT_TIMEOUT.toMillis(), MILLISECONDS)` 为 false → `process.destroyForcibly()` + `ToolResult.error("命令超时")`（isError）；否则等待 reader thread `join`，返回含 stdout/exit_code 的文本（经 `Truncate` ~30000 字符），非零退出不设 isError（按结果回灌让模型判断）。
4. `ctx.cancelled().get()` 时优先返回中断结果。

**验证：** `./gradlew compileJava`；T9 后单测 `echo hi` 与超时命令（注入极短 timeout 跑 `sleep`）。

## T7: glob 工具

**文件：** `src/main/java/com/cortex/tool/GlobTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `GlobArgs(String pattern, String path)`；类实现 `Tool`。
2. `schema()`：`pattern` 必填，`path` 可选（默认 `.`）。
3. `execute`：`Files.walk(Path.of(args.path()==null?".":args.path()))`，对每个文件相对路径做支持 `**` 的段匹配（自实现 `matchGlob(pattern, relPath)`：把 pattern 与 path 按 `/` 切段，`**` 匹配零个或多个段，其余段用 `PathMatcher.glob:`）；收集匹配（≤100，按字典序排序）；遍历中 `if (ctx.cancelled().get()) break;`；无匹配返回 `ToolResult.success("无匹配")`（非 isError）。

**验证：** `./gradlew compileJava`；T9 后单测 `**/*.java` 能命中 `src/main/...` 下文件。

## T8: grep 工具

**文件：** `src/main/java/com/cortex/tool/GrepTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `GrepArgs(String pattern, String path, String glob)`；类实现 `Tool`。
2. `schema()`：`pattern` 必填（描述注明 Java `Pattern` 语法），`path`/`Glob` 可选。
3. `execute`：`Pattern.compile(pattern)` 抛 `PatternSyntaxException` → isError；`Files.walk` 遍历（`Glob` 非空时用 `FileSystems.getDefault().getPathMatcher("glob:" + glob)` 按文件名过滤），用 `BufferedReader` 逐行读（限行长 1MB，超出跳过并标注），`pattern.matcher(line).find()` 收集 `file:line:content`（≤100，超出尾部标注）；循环检查 `ctx.cancelled().get()`；无命中返回 `ToolResult.success("无命中")`（非 isError）。

**验证：** `./gradlew compileJava`；T9 后单测搜一个已知关键字命中。

## T9: defaultRegistry 与 tool 单测

**文件：** `src/main/java/com/cortex/tool/Registry.java`、`src/test/java/dev/cortex/tool/RegistryTest.java`
**依赖：** T3–T8
**步骤：**
1. `ToolRegistry.createDefault()`：依次 `register` 6 个工具实例，返回 `ToolRegistry`。
2. `RegistryTest`（JUnit 5）：
   - `definitionsReturnsSixOrdered()`：`createDefault().definitions().size()==6`，按 order 名称序（AC1）。
   - `readFile_exists / missing`、`writeFile_nestedDir`（用 `@TempDir`）、`editFile_zero / unique / multiple`（断言三条文案不同且多于一处含数字）、`bash_echo / timeout`（timeout 用反射或注入子 timeout 的临时常量，跑 `sleep 5`）、`glob_starStarJava`、`grep_keyword`。

**验证：** `./gradlew -Dtest=RegistryTest test` 全通过；输出确认 6 条定义、edit 三情形文案不同。

## T10: Provider.stream 加 tools 参数（注入定义，暂不解析）

**文件：** `src/main/java/com/cortex/llm/{Provider,AnthropicProvider,OpenAIProvider}.java`、`src/main/java/com/cortex/tui/CortexModel.java`
**依赖：** T1
**步骤：**
1. `Provider.stream` 签名改为 `BlockingQueue<StreamEvent> stream(List<Message> messages, List<Map<String, Object>/*tool schema*/> tools)`，更新接口 javadoc。
2. `AnthropicProvider.stream` 加 `tools` 形参；新增 `toAnthropicTools(tools)` 并设 `params.tools(...)`；流解析暂不变。
3. `OpenAIProvider.stream` 同理，新增 `toOpenAITools(tools)` 设 `params.tools(...)`。
4. `CortexModel.submit` 中 `provider.stream(conv.messages())` 暂改为传 `List.of()` 第二参数（T16 会替换为 `Agent.run`）。

**验证：** `./gradlew shadowJar` 成功；用真实 key 跑一条纯文本仍正常（工具定义已随请求发送，模型未必调用）。

## T11: anthropic 适配器解析工具调用 + 回灌

**文件：** `src/main/java/com/cortex/llm/AnthropicProvider.java`
**依赖：** T10
**步骤：**
1. 流式回调外保留一个 `MessageAccumulator acc = MessageAccumulator.create();`，subscribe 回调内 `acc.accumulate(event);`；文本 delta 仍上抛（`event.asContentBlockDelta().delta().asText()` 走 `TextDelta` 上抛，`InputJsonDelta`/`ThinkingDelta` 不上抛）。
2. `onComplete` 时若 `acc.finalMessage().stopReason()==StopReason.TOOL_USE`：遍历 `acc.finalMessage().content()`，`block.asToolUse()` 收集 `new ToolCall(b.id(), b.name(), jackson.writeValueAsString(b.input()))`，先 `pub.submit(new AgentEvent.ToolUseEvent(calls))` 再 `pub.submit(new AgentEvent.LoopComplete())`。
3. `toAnthropicMessages` 扩展：assistant 有 `toolCalls` 时除文本块外 append `ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(c.id()).name(c.name()).input(JsonObject.fromString(c.args())).build())`；`Role.TOOL` 消息把每个 `ToolResult` 用 `ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(r.toolCallId()).content(r.content()).isError(r.isError()).build())` 拼成一条 `MessageParam.user(...)`。

**验证：** `./gradlew compileJava`；`./gradlew compileJava` (代码风格由 IDE 保证) 若启用须无告警（类型断言/字段名正确）。

## T12: openai 适配器解析工具调用 + 回灌

**文件：** `src/main/java/com/cortex/llm/OpenAIProvider.java`
**依赖：** T10
**步骤：**
1. 流式回调外保留 `ChatCompletionAccumulator acc = ChatCompletionAccumulator.create();` 每次 `acc.accumulate(chunk)`；`chunk.choices().get(0).delta().content()` 非空时仍上抛 `TextDelta`。
2. `onComplete` 时读 `acc.chatCompletion().choices().get(0).message().toolCalls()`（非空即组 `new ToolCall(tc.id(), tc.function().name(), normalize(tc.function().arguments()))`），先 `pub.submit(new AgentEvent.ToolUseEvent(calls))` 再 `pub.submit(new AgentEvent.LoopComplete())`。`normalize`：空串/null → `"{}"`。判定可结合 `finishReason=="tool_calls"` 与 acc 是否含工具调用兜底。
3. `toOpenAIMessages` 扩展：assistant 有 `toolCalls` 时手工构造 `ChatCompletionAssistantMessageParam.builder().content(text).toolCalls(List.of(ChatCompletionMessageToolCall.ofFunction(ChatCompletionMessageToolCall.Function.builder().name(c.name()).arguments(c.args()).build(), c.id()))).build()` 后 `ChatCompletionMessageParamUnion.ofAssistant(...)`；`Role.TOOL` 消息每个 `ToolResult` 发 `ChatCompletionToolMessageParam.builder().toolCallId(r.toolCallId()).content(r.content()).build()`。

**验证：** `./gradlew compileJava`；`./gradlew compileJava` (代码风格由 IDE 保证) 无告警。

## T13: conversation 扩展

**文件：** `src/main/java/com/cortex/conversation/Conversation.java`
**依赖：** T1
**步骤：**
1. 新增 `addAssistantWithToolCallComplete(String text, List<ToolCall> calls)`：append `new Message(Role.ASSISTANT, text, List.copyOf(calls), List.of())`。
2. 新增 `addToolResults(List<ToolResult> results)`：append `new Message(Role.TOOL, "", List.of(), List.copyOf(results))`。
3. 保留现有方法不变。

**验证：** `./gradlew -Dtest=ConversationTest test` 通过（补一条断言新方法落库的小测）。

## T14: agent 单轮闭环

**文件：** `src/main/java/com/cortex/agent/{Phase,ToolEvent,Event,Agent}.java`、`src/test/java/dev/cortex/agent/AgentTest.java`
**依赖：** T9, T11, T12, T13
**步骤：**
1. `Phase.java`：enum `START, END`。
2. `ToolEvent.java`：record（见 plan）。
3. `Event.java`：sealed `permits Text, Tool, Done, Failed`，四个嵌套 record。
4. `Agent.java`：构造 `(Provider, Registry)`；`run(ConversationManager conv)` 返回 `BlockingQueue<AgentEvent>`——内部 `BlockingQueue<AgentEvent> pub`，开 `Thread.ofVirtual().start(...)` 跑：streamOnce（订阅 provider 的 publisher，把 TextDelta 转 `AgentEvent.StreamText`、收集 `ToolCallComplete`、`Done` 后 latch.countDown()）；无工具→`conv.addAssistant(preamble)` + `AgentEvent.LoopComplete` + `pub.close()`；有工具→`conv.addAssistantWithToolCallComplete`、顺序执行带 `ExecutorService.newVirtualThreadPerTaskExecutor()` + `Future.get(DEFAULT_TIMEOUT.toMillis(), MILLISECONDS)`、`conv.addToolResults`、请求#2、忽略二轮工具调用、`AgentEvent.LoopComplete`。任一阶段异常 → `AgentEvent.ErrorEvent` 后 `pub.close()`。
5. `AgentEvent.ToolResultEvent` 的 `args` 预览：把 `args` 简化（取 `path`/`command`/`pattern` 等关键字段，截断 80 字符）。
6. `AgentTest`：实现 `Provider` 的 fake，编排两种脚本——(a) 请求#1 返回 1 个工具调用、请求#2 返回文本 → 断言订阅到的 Event 序列含 Tool START/END 与最终 Text、`conv.messages()` 末尾为 assistant 文本（AC8）；(b) 请求#1 返回工具、请求#2 仍返回工具 → 断言只执行一轮、不再触发执行（AC9）。

**验证：** `./gradlew -Dtest=AgentTest test` 全通过；输出确认单轮上限生效。

## T15: prompt 系统提示词扩展

**文件：** `src/main/java/com/cortex/prompt/Prompt.java`
**依赖：** 无
**步骤：**
1. 扩写 `SYSTEM_PROMPT`：说明 Cortex 是能使用工具的 Agent，可读写改文件、执行命令、查找/搜索代码；需要信息或操作时调用相应工具，拿到结果后给出简洁答复。

**验证：** `./gradlew compileJava`；`./gradlew test` 不回归。

## T16: tui 接入 agent + 工具行渲染

**文件：** `src/main/java/com/cortex/tui/{CortexModel,EventPump,View}.java`
**依赖：** T14, T15
**步骤：**
1. `CortexModel` 构造增加 `ToolRegistry registry` 形参与字段；移除原 `AgentEvent 队列` 引用（或重命名为 `EventPump`）；新字段 `ToolDisplay curTool`（record `ToolDisplay(String name, String args)`，置 null 时不渲染执行行）。
2. `EventPump implements BlockingQueue.poll()<agent.Event>`：`onNext` 调 `program.send(new AgentEventMessage(() -> handle(event))`；`onError(t)` 包成 `AgentEvent.ErrorEvent` 走同一路径；`onComplete` 不处理。`handle` 按 sealed 分派：
   - `Text(delta)`：`curReply.append(delta)`；`streamingLabel.setText(curReply.toString())`。
   - `Tool(e)` `phase==START`：若 `curReply.length()>0`，把 preamble 作为 assistant `Label` 追加到 `scrollback` 并 `curReply.setLength(0)`；置 `curTool = new ToolDisplay(e.name(), e.args())`；spinner 文本切到 `Running…`。
   - `Tool(e)` `phase==END`：依次 `scrollback.addComponent(View.toolLine(name, args))`、`scrollback.addComponent(View.toolResultSummary(result, isError))`；清 `curTool`。
   - `Done`：把 `curReply`（最终答复）经 `View.renderMarkdown` 写入 `scrollback`；`finishTurn`（停 spinner 定时器、回 IDLE）。
   - `Failed(err)`：`scrollback.addComponent(View.errorBlock(err))`；`finishTurn`。
3. `CortexModel.submit` 中：`conv.addUser(text)` → `var pub = new Agent(client, registry, protocol, selectedProvider).run(conv); pub.subscribe(new EventPump(this));`，移除 T10 的临时 `List.of()` 调用。
4. `View` 新增：`Label toolLine(String name, String args)`（青/绿 `●` + `name(args)`）、`Component toolResultSummary(String result, boolean isError)`（缩进 `  ⎿ `、灰/红，UI 截断 ~8 行）。状态栏中央 `spinnerLabel` 在 `curTool != null` 时显示 `● name(args)  Running…`，否则沿用 `Imagining… (Ns)`。

**验证：** `./gradlew shadowJar`；`./gradlew compileJava` (代码风格由 IDE 保证) 无告警。

## T17: Main 接线

**文件：** `src/main/java/com/cortex/Cortex.java`
**依赖：** T16
**步骤：**
1. `var registry = ToolRegistry.createDefault();`；`new CortexModel(cfg.providers(), registry).run();`。

**验证：** `./gradlew shadowJar` 通过；`java -jar build/libs/cortex.jar` 启动正常进入对话。

## T18: 全量验证与端到端冒烟

**文件：** 无（验证）
**依赖：** T1–T17
**步骤：**
1. `./gradlew spotless:apply`（若启用）；`./gradlew shadowJar`；`./gradlew test`。
2. 用当前 `.cortex/config.yaml`（openai 兼容端点）跑：问「读 docs/ch03/spec.md 并用一句话总结」→ 观察工具行 `● read_file(...)` + 结果摘要 + 最终答复（AC8/AC11）。
3. 触发各错误：读不存在文件、edit 匹配不到、bash 非零退出 → 错误结构化回灌、程序不退出（AC12）。
4. （可选）若有 anthropic 配置，重复步骤 2 验证跨协议一致（AC10）。

**验证：** 全部命令通过、端到端链路与错误恢复符合预期。

## 执行顺序

```
T1 ─┬─ T2 ─┬─ T3 ─┐
    │       ├─ T4 ─┤
    │       ├─ T5 ─┼─ T9 ─┐
    │       ├─ T6 ─┤      │
    │       ├─ T7 ─┤      │
    │       └─ T8 ─┘      │
    ├─ T10 ─┬─ T11 ──────┤
    │        └─ T12 ─────┤
    ├─ T13 ──────────────┤
    └─ T15               │
                T9,T11,T12,T13 ─→ T14 ─→ T16 ─→ T17 ─→ T18
                                   T15 ──┘
```