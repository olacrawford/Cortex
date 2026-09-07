# 多协议 LLM 终端对话客户端 Tasks

> 顶层包：`com.mewcode`（Java 21 / Gradle）。构建产物在 `build/libs/mewcode.jar`。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `build.gradle.kts` | Gradle 构建、JDK 21、依赖、Shadow plugin |
| 新建 | `.mewcode/config.yaml.example` | 配置模板 |
| 修改 | `.gitignore` | 忽略 `.mewcode/config.yaml`、`build/` |
| 新建 | `src/main/java/com/mewcode/config/AppConfig.java` | 配置根类 |
| 新建 | `src/main/java/com/mewcode/config/ProviderConfig.java` | provider 配置 |
| 新建 | `src/main/java/com/mewcode/config/ConfigLoader.java` | YAML 加载与校验 |
| 新建 | `src/main/java/com/mewcode/prompt/PromptBuilder.java` | system prompt 构建 |
| 新建 | `src/main/java/com/mewcode/llm/StreamEvent.java` | sealed StreamEvent |
| 新建 | `src/main/java/com/mewcode/llm/LlmClient.java` | 统一接口 + 工厂 |
| 新建 | `src/main/java/com/mewcode/conversation/ConversationManager.java` | 多轮历史 |
| 新建 | `src/main/java/com/mewcode/conversation/Message.java` | 消息类型 |
| 新建 | `src/main/java/com/mewcode/llm/AnthropicClient.java` | anthropic 适配器 |
| 新建 | `src/main/java/com/mewcode/llm/OpenAiClient.java` | openai 适配器 |
| 新建 | `src/main/java/com/mewcode/tui/tea/*.java` | Bubble Tea 框架（11 个文件） |
| 新建 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | 主 TUI 模型 |
| 新建 | `src/main/java/com/mewcode/tui/Styles.java` | 全局样式 |
| 新建 | `src/main/java/com/mewcode/tui/MarkdownRenderer.java` | Mordant markdown 渲染 |
| 新建 | `src/main/java/com/mewcode/MewCode.java` | 入口装配 |
| 新建 | `src/test/java/com/mewcode/config/ConfigLoaderTest.java` | config 单测 |
| 新建 | `src/test/java/com/mewcode/conversation/ConversationTest.java` | conversation 单测 |

---

## T1: 初始化 Gradle 工程与依赖
**文件：** `build.gradle.kts`、`src/main/java/com/mewcode/MewCode.java`（临时占位）
**依赖：** 无
**步骤：**
1. 创建 `build.gradle.kts`：plugins `java`、`application`、`com.gradleup.shadow`；
   `java.toolchain.languageVersion = JavaLanguageVersion.of(21)`；
   `application.mainClass = "com.mewcode.MewCode"`。
2. 加依赖：
   - TUI：`org.jline:jline`
   - markdown：`com.github.ajalt.mordant:mordant` + `mordant-markdown`
   - SDK：`com.anthropic:anthropic-java`、`com.openai:openai-java`
   - 配置：`org.yaml:snakeyaml`
   - JSON：`com.fasterxml.jackson.core:jackson-databind`
   - 测试：`org.junit.jupiter:junit-jupiter`（testImplementation）
3. 配 `tasks.shadowJar`：`archiveBaseName = "mewcode"`、`mergeServiceFiles()`。
4. 写临时 `MewCode.java`，`main` 打印版本字符串。

**验证：** `./gradlew shadowJar` 成功；`java -jar build/libs/mewcode.jar` 打印版本。

## T2: config 包
**文件：** `src/main/java/com/mewcode/config/{AppConfig,ProviderConfig,ConfigLoader}.java`
**依赖：** T1
**步骤：**
1. 定义 `ProviderConfig`（POJO，getter/setter）：name、protocol、baseUrl、apiKey、model、thinking。
2. 定义 `AppConfig`：`List<ProviderConfig> providers`。
3. 实现 `ConfigLoader.load(String path)`：
   用 `org.yaml.snakeyaml.Yaml` 解析为 `Map<String,Object>`，
   读 `providers` 列表，逐项映射到 ProviderConfig。
4. 校验：列表非空；逐项 name/protocol/apiKey/model 非空；
   protocol ∈ {anthropic, openai, openai-compat}。失败抛 `ConfigException`。

**验证：** 单测 `ConfigLoaderTest`；`./gradlew test --tests '*ConfigLoaderTest'`。

## T3: 配置模板与忽略
**文件：** `.mewcode/config.yaml.example`、`.gitignore`
**依赖：** T2
**步骤：**
1. 写 `.mewcode/config.yaml.example`：含 anthropic 条目（`thinking: true`）+ 注释掉的 openai 条目。
2. `.gitignore` 追加 `.mewcode/config.yaml`、`build/`、`.gradle/`、`.idea/`。

**验证：** 复制 example 为 config.yaml 后 `ConfigLoader.load` 通过。

## T4: prompt 包
**文件：** `src/main/java/com/mewcode/prompt/PromptBuilder.java`
**依赖：** T1
**步骤：**
1. 实现 `buildSystemPrompt(env, options)` 拼接系统提示词各段。
2. banner 渲染在 MewCodeModel 内联实现（`renderBanner()` 方法）。

**验证：** `./gradlew compileJava`。

## T5: llm 包骨架
**文件：** `src/main/java/com/mewcode/llm/{StreamEvent,LlmClient}.java`
**依赖：** T2
**步骤：**
1. 定义 `sealed interface StreamEvent`：TextDelta、ThinkingDelta、StreamEnd、Error 等。
2. 定义 `LlmClient` 接口：`BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String,Object>> tools)`。
3. 实现 `LlmClient.create(ProviderConfig cfg, String systemPrompt)` 工厂方法：按 protocol 分派。

**验证：** `./gradlew compileJava`。

## T6: conversation 包
**文件：** `src/main/java/com/mewcode/conversation/{ConversationManager,Message}.java`
**依赖：** T5
**步骤：**
1. 定义 `Message`（role、content、工具调用块等）。
2. 实现 `ConversationManager`：`addUserMessage`、`addAssistantMessage`、`getMessages()`。

**验证：** 单测 `ConversationTest`；`./gradlew test --tests '*ConversationTest'`。

## T7: anthropic 适配器
**文件：** `src/main/java/com/mewcode/llm/AnthropicClient.java`
**依赖：** T5、T4
**步骤：**
1. 构造 SDK client：`AnthropicClient.builder().apiKey(...).build()`；baseUrl 覆盖。
2. `stream()` 在 virtual thread 中：组装 `MessageCreateParams`、设 thinking 参数；
   用 `client.messages().createStreaming(params)` 拿到 `StreamResponse`；
   `.stream().iterator()` 逐事件消费，`ContentBlockDelta` → 推 `BlockingQueue`。

**验证：** `./gradlew compileJava`。

## T8: openai 适配器
**文件：** `src/main/java/com/mewcode/llm/OpenAiClient.java`
**依赖：** T5、T4
**步骤：**
1. 类似 T7 模式，用 openai-java SDK 的流式 API。
2. baseUrl 非空时覆盖（支持兼容端点）。

**验证：** `./gradlew compileJava`。

## T9: tui.tea 框架层
**文件：** `src/main/java/com/mewcode/tui/tea/*.java`（11 个文件）
**依赖：** T1
**步骤：**
1. 实现 `Message` 接口、`KeyPressMessage`、`WindowSizeMessage`、`QuitMessage`、`MouseMessage`。
2. 实现 `Command` sealed 接口：Simple、Tick、CheckWindowSize、Batch、PrintLine。
3. 实现 `UpdateResult` record、`Model` 接口（init/update/view/dumpHistory）。
4. 实现 `Style`/`ANSI256Color`：ANSI 转义序列渲染。
5. 实现 `Program`：JLine Terminal raw mode、按键读取（ESC 序列解析）、
   内联渲染（cursor-up 覆写，linesRendered 跟踪）、
   PrintLine（clearView → 写文本 → renderView）、
   SIGINT/WINCH 信号处理、事件循环。

**验证：** `./gradlew compileJava`；写临时 Model 测试 Program 启动退出。

## T10: tui MewCodeModel
**文件：** `src/main/java/com/mewcode/tui/{MewCodeModel,Styles,AppState,ChatMessage,SpinnerVerbs,MarkdownRenderer}.java`
**依赖：** T9、T5、T6、T2
**步骤：**
1. 实现 `MewCodeModel implements Model`：状态机（PROVIDER_SELECT/CHAT/RESUME）。
2. `init()` → `Command.checkWindowSize()`。
3. `update(msg)` 处理：WindowSize、KeyPress（输入/提交/slash/方向键/Ctrl+C）、AgentEvent、鼠标。
4. `view()` 渲染：banner + 未提交消息 + 工具块 + 流式缓冲 + spinner
   + 分隔线 + 输入框 + 分隔线 + slash 菜单 + 状态栏。
5. scrollback 机制：`committedUpTo` + `renderMessagesRange()` + `Command.println()`。
6. `dumpHistory()`：banner + 全部消息的干净渲染。

**验证：** `./gradlew compileJava`。

## T11: 入口装配
**文件：** `src/main/java/com/mewcode/MewCode.java`（替换 T1 占位）
**依赖：** T2、T9、T10
**步骤：**
1. 加载配置 → 创建 MewCodeModel → 创建 Program → `program.run()`。
2. 支持 `-p "prompt"` 非交互模式和 `--remote` 模式。
3. SIGINT handler 注册。

**验证：** `./gradlew shadowJar` 成功；缺配置时打印可读错误。

## T12: 端到端联调
**文件：** 无（运行验证）
**依赖：** T1–T11
**步骤：**
1. anthropic 配置：多轮对话、流式逐字、计时、markdown 定型、thinking 不出现。
2. openai/compat 配置：同样多轮 + 流式。
3. 多 provider：选择列表、状态栏正确。
4. 错误 key：错误显示不退出。
5. scrollback：完成消息进入终端 scrollback，可鼠标滚轮回看。
6. Ctrl+C：安全退出、dumpHistory 输出、终端恢复。

**验证：** 逐条对照 checklist 记录。

## 执行顺序
```
T1 ─┬─ T2 ─── T3
    │      └─ T5 ─┬─ T6
    │             ├─ T7
    │             └─ T8
    ├─ T4
    └─ T9 ── T10
T2,T4,T9,T10 ─ T11
T1..T11 ─ T12
```
（T4 可与 T2/T5 并行；T7、T8 可并行；T9 完成后 T10 即可推进。）