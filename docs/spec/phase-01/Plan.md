# 多协议 LLM 终端对话客户端 Plan

## 技术栈
- 语言：Java 21（LTS；启用 virtual threads；使用 record、sealed interface、pattern matching、switch 模式匹配）
- 构建：Gradle（`build.gradle.kts`，Shadow plugin 打 fat jar；目标 JDK 21）
- TUI：JLine 3（`org.jline:jline`）+ 自建 `com.mewcode.tui.tea` 包（Bubble Tea 风格）——
  `Program` 管理 raw mode、按键读取、内联渲染（cursor-up 覆写）；
  `Model` 接口（`init/update/view`）驱动状态机；
  `Command.println` 将完成内容写入终端 scrollback；
  `Style`/`ANSI256Color` 用 ANSI 转义序列渲染彩色文本
- markdown 渲染：Mordant（`com.github.ajalt.mordant:mordant-markdown`）直接输出 ANSI 富文本
- 配置：SnakeYAML（`org.yaml:snakeyaml`）解析为 `Map<String,Object>`，手动绑定到 POJO
- LLM 通信：官方 Java SDK —— `com.anthropic:anthropic-java`、`com.openai:openai-java`
  （两个 SDK 都提供流式：`client.messages().createStreaming(params)` 返回 `StreamResponse`，
   用 `.stream().iterator()` 逐事件消费；内部已处理 SSE）
- 并发：Java 21 virtual thread + `BlockingQueue<StreamEvent>`，
  零外部框架依赖；取消用 `Thread.interrupt()`

## 架构概览（分层）
1. 入口层 `com.mewcode.MewCode` —— 加载配置、启动 TUI Program。
2. 配置层 `com.mewcode.config` —— 读取并校验 `.mewcode/config.yaml`，给出 providers 列表。
3. LLM 协议层 `com.mewcode.llm` —— 定义协议无关的 `LlmClient` 接口与统一消息/流式事件类型；
   `AnthropicClient`、`OpenAiClient` 两个适配器各自封装官方 SDK、统一吐出文本增量（思考增量内部丢弃）。
4. 会话层 `com.mewcode.conversation` —— 进程内维护多轮历史，提供完整上下文。
5. 提示词/资源 `com.mewcode.prompt` —— 内置 system prompt 与启动 banner（ASCII 猫）。
6. TUI 框架层 `com.mewcode.tui.tea` —— 基于 JLine 的 Bubble Tea 风格运行时：
   `Program`（内联渲染 + 事件循环）、`Model`/`Command`/`Message`/`UpdateResult`（Elm 架构）、
   `Style`/`ANSI256Color`（终端样式）。
7. TUI 应用层 `com.mewcode.tui` —— `MewCodeModel`（实现 Model 接口），含状态机（选择/空闲/流式）、
   输入缓冲、对话显示、spinner+计时、provider 选择列表、scrollback 提交机制。

## 数据流（一轮对话）
用户输入 → MewCodeModel 提交 → `ConversationManager` 追加 user 消息 → 调 `LlmClient.stream(conv, tools)`
→ 得到 `BlockingQueue<StreamEvent>` → virtual thread 消费事件 → 通过 `AgentEvent` 队列投递给 TUI 主循环
→ MewCodeModel.update() 处理事件、更新 streamBuf → view() 重绘
→ 流式结束 → `Command.println(commitText)` 将完成内容写入终端 scrollback
→ `ConversationManager` 追加 assistant 消息 → 回到空闲。

## 核心数据结构与接口


// ───────── config 层 ─────────
package com.mewcode.config;

public class ProviderConfig {
    String name;       // 状态栏左侧显示
    String protocol;   // "anthropic" | "openai" | "openai-compat"
    String baseUrl;    // 空则用 SDK 默认端点
    String apiKey;
    String model;      // 状态栏右侧显示
    boolean thinking;  // 仅 anthropic 生效
}

public class AppConfig {
    List<ProviderConfig> providers;
}

public final class ConfigLoader {
    public static AppConfig load(String path); // 加载 + 校验
}

// ───────── llm 层（协议无关）─────────
package com.mewcode.llm;

public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ThinkingDelta(String text) implements StreamEvent {}
    record StreamEnd(String stopReason, int inputTokens, int outputTokens) implements StreamEvent {}
    record Error(String message) implements StreamEvent {}
    // ... ToolCallStart, ToolCallDelta, ToolCallComplete 等
}

public interface LlmClient {
    BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools);
}

// ───────── conversation 层 ─────────
package com.mewcode.conversation;

public class ConversationManager {
    public void addUserMessage(String text);
    public void addAssistantMessage(String text);
    public List<Message> getMessages();
}

// ───────── tui.tea 框架层 ─────────
package com.mewcode.tui.tea;

public interface Message {}
public record KeyPressMessage(String key, char[] runes) implements Message {}
public record WindowSizeMessage(int width, int height) implements Message {}
public record QuitMessage() implements Message {}

public sealed interface Command {
    static Command tick(Duration delay, Function<Instant, Message> fn);
    static Command println(String text);    // 写入终端 scrollback
    static Command checkWindowSize();
    static Command batch(Command... cmds);
}

public record UpdateResult<M extends Model>(M model, Command command) {}

public interface Model {
    Command init();
    UpdateResult<? extends Model> update(Message msg);
    String view();
    default String dumpHistory() { return ""; }
}

public class Program {
    // JLine Terminal + raw mode + 内联渲染（cursor-up 覆写）
    public void run();
    public void send(Message msg);          // 外部线程投递消息
    public int getAvailableHeight();        // view 可用行数
}

// ───────── tui 应用层 ─────────
package com.mewcode.tui;

public enum AppState { PROVIDER_SELECT, CHAT, RESUME }

public class MewCodeModel implements Model {
    // 实现 Bubble Tea 的 init/update/view 三方法
    // update 处理按键、流式事件、窗口大小变化
    // view 渲染：banner + 消息 + 工具块 + spinner + 输入框 + 状态栏
    // committedUpTo 跟踪已提交到 scrollback 的消息边界
}


## 模块设计

### 模块 config
职责：读取并校验 `.mewcode/config.yaml`，产出 providers 列表。
对外接口：`ConfigLoader.load(path)`；`AppConfig.getProviders()`。
校验规则：列表非空；每项 name/protocol/apiKey/model 非空；protocol ∈ {anthropic, openai, openai-compat}。
任一不满足 → 抛出 `ConfigException`，消息形如 `providers[1].api_key 不能为空`。
依赖：`org.yaml:snakeyaml`、JDK `java.nio.file`。

### 模块 llm
职责：定义协议无关的 `LlmClient` 接口与统一消息/事件类型；按 protocol 构造适配器。
对外接口：`LlmClient` 接口、`StreamEvent` sealed 接口、`LlmClient.create(cfg, systemPrompt)` 工厂方法。
子单元：
  - `AnthropicClient`（封装 anthropic-java）：把对话历史转为 SDK 的 `MessageParam`，
    注入 system prompt、按 `thinking` 设置扩展思考参数；
    用 `client.messages().createStreaming(params)` 拿到 `StreamResponse`，
    在 virtual thread 中用 `.stream().iterator()` 逐事件消费，
    解出 `TextDelta` 推入 `BlockingQueue<StreamEvent>`，思考增量丢弃。
  - `OpenAiClient`（封装 openai-java）：历史转 `ChatCompletionMessage`，
    `baseUrl` 非空时覆盖端点（兼容端点如 DeepSeek、MiniMax）；
    流式迭代 `chunk.choices().get(0).delta().content()` 推 `TextDelta`。
共同点：各适配器在 virtual thread 中运行流式循环，通过 `BlockingQueue` 与调用方通信。
依赖：`com.anthropic:anthropic-java`、`com.openai:openai-java`、本项目 `prompt`、`config`。

### 模块 conversation
职责：进程内维护单会话多轮历史（user/assistant/tool 交替）。
对外接口：`addUserMessage`、`addAssistantMessage`、`getMessages()`。
依赖：无外部依赖。

### 模块 prompt
职责：提供内置 system prompt 与 ASCII 猫 banner 文本。
对外接口：`PromptBuilder.buildSystemPrompt()`、banner 渲染由 `MewCodeModel.renderBanner()` 内联。
依赖：无。

### 模块 tui.tea（框架层）
职责：提供 Bubble Tea 风格的 TUI 运行时框架，基于 JLine Terminal。
对外接口：`Program(model).run()`、`Model` 接口。
内部职责：
  - `Program`：管理 JLine Terminal raw mode、按键读取（ESC 序列解析）、
    SIGINT 处理、窗口大小变化监听、定时器调度（`Command.Tick`）。
  - 内联渲染：每帧 cursor-up 回到 view 起始行覆写，不用 `\033[H`，不破坏终端已有内容。
  - `Command.println`：清除当前 view 区域 → 写入文本到终端（进入 scrollback）→ 重绘 view。
  - 退出时调用 `model.dumpHistory()` 输出干净的对话历史到终端。
依赖：`org.jline:jline`。

### 模块 tui（应用层）
职责：实现 `Model` 接口，承载选择/对话/流式/错误的全部交互与渲染。
对外接口：`new MewCodeModel(providers, mcpServers, hooks)` + `Program(model).run()`。
内部职责：
  - `init()` 返回 `Command.checkWindowSize()`，触发首次窗口大小检测和 banner 输出。
  - `update(msg)` 按状态分派：PROVIDER_SELECT（方向键+回车选择）、CHAT（输入/发送/流式事件处理）。
  - `view()` 渲染：banner（仅首屏）+ 未提交消息 + 活跃工具块 + 流式缓冲 + spinner
    + 分隔线 + 输入框 + slash 菜单 + 分隔线 + 状态栏。
  - scrollback 提交：`committedUpTo` 跟踪已提交消息边界；
    LoopComplete/ToolResult 时调 `renderMessagesRange()` → `Command.println()` 提交到 scrollback。
  - `dumpHistory()`：输出 banner + 全部消息的干净渲染，用于退出时的终端历史。
依赖：`com.github.ajalt.mordant:mordant-markdown`，本项目 `llm`、`conversation`、`config`、`prompt`。

### 入口 `com.mewcode.MewCode`
职责：装配与启动。
流程：`ConfigLoader.load(...)` → `new MewCodeModel(providers)` → `new Program(model).run()`。
失败处理：配置错误打印可读信息并 `System.exit(1)`。
依赖：`config`、`tui`。

## 模块交互

### 调用链（启动）

MewCode.main → ConfigLoader.load(".mewcode/config.yaml")
     → 若抛异常：打印可读错误、System.exit(1)
     → new MewCodeModel(providers, mcpServers, hooks)
     → new Program(model)
     → model.setProgram(program)
     → program.run()
       → init() → checkWindowSize → WindowSizeMessage → banner println
       → providers.size()==1：initializeProvider()，进 CHAT
       → providers.size() >1：进 PROVIDER_SELECT


### 时序（多 provider 选择）

PROVIDER_SELECT:
  view 显示 banner + 各 provider 的 name(model)
  用户方向键移动、Enter 选定
  → initializeProvider() 构造 LlmClient
  → 状态栏更新为 provider.name / provider.model
  → banner println 到 scrollback
  → 进 CHAT


### 时序（一轮对话，核心）

CHAT（空闲）:
  用户在输入缓冲键入文本，Enter 提交
  → chatMessages.add(user) → committedUpTo = size → Command.println(userLine)
  → conv.addUserMessage(text)
  → agent.run(conv, queue)   // virtual thread
  → Command.tick(50ms) 开始轮询 AgentEvent
  → streaming = true，切入流式

CHAT（流式）:
  每 50ms AgentEventMessage 触发 update()：
    - StreamText → streamBuf.append
    - ToolUseEvent → toolBlocks 更新
    - ToolResultEvent → flush streamBuf → commit 到 scrollback
    - LoopComplete → flush → commit → streaming = false
    - ErrorEvent → chatMessages.add(error) → commit
  view() 重绘：未提交消息 + 活跃工具块 + streamBuf + spinner + 输入框 + 状态栏
  内联渲染器 cursor-up 覆写前一帧


### 时序（退出）

任意状态：Ctrl+C
  → KeyPressMessage("ctrl+c")
  → 非流式：Command(QuitMessage::new) → Program 退出循环
  → 流式：savePartialResponse() 中断流
  → Program finally：clearView() + dumpHistory() 到终端 + 恢复光标


### 数据流图

config.yaml ──ConfigLoader.load──> AppConfig ──LlmClient.create──> LlmClient
用户输入 ──> ConversationManager.addUserMessage ──> Agent.run(conv, queue)
Agent ──virtual thread──> LlmClient.stream ──> BlockingQueue<StreamEvent>
Agent ──消费 StreamEvent──> BlockingQueue<AgentEvent>
Program ──poll AgentEvent──> MewCodeModel.update() → view() → 内联渲染
LoopComplete ──renderMessagesRange──> Command.println ──> 终端 scrollback


## 文件组织

mewcode/
├── build.gradle.kts               — Gradle 构建（JDK 21、依赖、Shadow plugin）
├── src/main/java/com/mewcode/
│   ├── MewCode.java               — 入口：加载配置、启动 Program
│   ├── config/
│   │   ├── AppConfig.java         — 配置根类
│   │   ├── ProviderConfig.java    — provider 配置
│   │   └── ConfigLoader.java      — SnakeYAML 加载与校验
│   ├── llm/
│   │   ├── LlmClient.java        — 统一接口 + 工厂方法
│   │   ├── StreamEvent.java       — sealed interface StreamEvent
│   │   ├── AnthropicClient.java   — anthropic-java 适配
│   │   ├── OpenAiClient.java      — openai-java 适配
│   │   └── OpenAiCompatClient.java — openai-compat 适配
│   ├── conversation/
│   │   ├── ConversationManager.java — 多轮历史管理
│   │   └── Message.java           — 消息类型
│   ├── prompt/
│   │   └── PromptBuilder.java     — system prompt 构建
│   └── tui/
│       ├── tea/                   — Bubble Tea 框架层
│       │   ├── Program.java       — JLine 内联渲染 + 事件循环
│       │   ├── Model.java         — Model 接口
│       │   ├── Command.java       — 异步命令
│       │   ├── Message.java       — 消息基础接口
│       │   ├── KeyPressMessage.java
│       │   ├── WindowSizeMessage.java
│       │   ├── QuitMessage.java
│       │   ├── MouseMessage.java
│       │   ├── UpdateResult.java
│       │   ├── Style.java         — ANSI 256 色样式
│       │   └── ANSI256Color.java
│       ├── MewCodeModel.java      — 主 TUI 模型（Model 实现）
│       ├── Styles.java            — 全局样式常量
│       ├── AppState.java          — enum 状态
│       ├── ChatMessage.java       — 聊天消息展示类
│       ├── SpinnerVerbs.java      — spinner 动词
│       ├── MarkdownRenderer.java  — Mordant markdown 渲染
│       └── dialog/
│           ├── AskUserDialog.java
│           └── PlanApprovalDialog.java
├── src/test/java/com/mewcode/
│   ├── config/ConfigLoaderTest.java
│   └── conversation/ConversationTest.java
├── .mewcode/
│   └── config.yaml                — 运行配置；附 config.yaml.example
└── .gitignore                     — 忽略 .mewcode/config.yaml、build/

说明：
- 依赖版本以 build.gradle.kts 实际为准；核心依赖：
  `org.jline:jline`、`com.anthropic:anthropic-java`、`com.openai:openai-java`、
  `org.yaml:snakeyaml`、`com.github.ajalt.mordant:mordant`+`mordant-markdown`、
  `org.junit.jupiter:junit-jupiter`（testImplementation）。
- tui/tea 为自建 Bubble Tea 框架，不依赖第三方 TUI 库。
- `.mewcode/config.yaml` 含真实密钥，应在 `.gitignore` 忽略；提交一份 `config.yaml.example`。

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 语言 | Java 21（LTS） | 项目既定；virtual thread / record / sealed / pattern matching 都已 GA |
| 构建 | Gradle + Shadow | Kotlin DSL 简洁；Shadow plugin 打 fat jar，`java -jar mewcode.jar` 即可运行 |
| 并发 | virtual thread + `BlockingQueue` | 每个流式请求开一个 virtual thread 成本极低，等价于 goroutine；BlockingQueue 天然线程安全，比 JDK Flow API 更直观 |
| TUI 框架 | JLine 3 + 自建 tea 包 | JLine 是 Java 终端 I/O 事实标准（Kafka/Spring Shell/Gradle 都用）；自建 tea 包复刻 Bubble Tea 的 Model-Update-View 架构，与 Go 版保持一致 |
| 渲染模式 | 内联渲染（cursor-up 覆写） | 与 Bubble Tea 一致：不用 alternate screen，不破坏终端已有内容；println 内容自然进入 scrollback |
| markdown 渲染 | Mordant | 直接输出 ANSI 富文本，API 简单（`MarkdownRenderer.render(md, width)`），无需 AST→富文本的手动转换 |
| LLM 通信 | 官方 SDK（anthropic-java / openai-java） | SDK 内置 SSE 解析，省去手写；StreamResponse.stream().iterator() 逐事件消费，与 BlockingQueue 桥接自然 |
| 协议抽象 | 统一 `LlmClient` 接口 + 三适配器 | 满足 F3/N3；上层不感知协议；openai-compat 复用 openai SDK 仅覆盖 baseUrl |
| scrollback 机制 | committedUpTo + Command.println | 完成消息写入终端 scrollback（用户可鼠标滚轮回看）；view 只渲染未提交内容，与 Claude Code 行为对齐 |
| thinking | 仅 anthropic 生效；openai 忽略 | 思考内容接收即丢弃 |
| 计时 | thinkingStartMs + 50ms poll tick | 自请求即计时（F12） |
| provider 选择 | 单份直进 / 多份方向键列表 | 满足 F2 |
| 历史 | 进程内 `ArrayList<Message>`，单会话 | 满足 F6；不持久化 |
| system prompt | PromptBuilder 构建，LlmClient 注入 | 满足 F4；conversation 保持纯消息 |
| 配置 | `.mewcode/config.yaml` + SnakeYAML；密钥入 `.gitignore` | 用户既定路径；N5 密钥安全 |
| 错误处理 | 运行时错误经 AgentEvent.ErrorEvent 展示，不退出 | 满足 F11 |