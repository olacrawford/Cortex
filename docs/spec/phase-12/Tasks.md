# SubAgent 机制 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/mewcode/subagent/package-info.java` | 包注释 |
| 新建 | `src/main/java/com/mewcode/subagent/Definition.java` | Definition record / Source enum |
| 新建 | `src/main/java/com/mewcode/subagent/Parser.java` | parseFrontmatterAndBody + validateMeta |
| 新建 | `src/test/java/dev/mewcode/subagent/ParserTest.java` | 解析与字段校验单测 |
| 新建 | `src/main/java/com/mewcode/subagent/Catalog.java` | Catalog + load / resolve / list / forkDefinition |
| 新建 | `src/test/java/dev/mewcode/subagent/CatalogTest.java` | 多来源加载与覆盖测试 |
| 新建 | `src/main/java/com/mewcode/subagent/BuiltinLoader.java` | classpath resource 加载 + builtinDefinitions() |
| 新建 | `src/main/resources/subagent/builtin/general-purpose.md` | 内置 general-purpose 定义 |
| 新建 | `src/main/resources/subagent/builtin/explore.md` | 内置 Explore 定义 |
| 新建 | `src/main/resources/subagent/builtin/plan.md` | 内置 Plan 定义 |
| 新建 | `src/main/java/com/mewcode/subagent/LaunchFork.java` | LaunchFork / 公用 wiring 辅助函数 |
| 新建 | `src/test/java/dev/mewcode/subagent/LaunchForkTest.java` | LaunchFork 流程测试 |
| 新建 | `src/main/java/com/mewcode/task/package-info.java` | 包注释 |
| 新建 | `src/main/java/com/mewcode/task/Manager.java` | Manager + BackgroundTask + launch / adopt / stop / sendMessage / subscribeDone |
| 新建 | `src/main/java/com/mewcode/task/Status.java` | enum Status |
| 新建 | `src/main/java/com/mewcode/task/Usage.java` | record Usage |
| 新建 | `src/main/java/com/mewcode/task/PartialState.java` | record PartialState |
| 新建 | `src/test/java/dev/mewcode/task/ManagerTest.java` | 后台任务全生命周期测试 |
| 新建 | `src/main/java/com/mewcode/task/TaskListTool.java` | TaskList 工具 |
| 新建 | `src/main/java/com/mewcode/task/TaskGetTool.java` | TaskGet 工具 |
| 新建 | `src/main/java/com/mewcode/task/TaskStopTool.java` | TaskStop 工具 |
| 新建 | `src/main/java/com/mewcode/task/SendMessageTool.java` | SendMessage 工具 |
| 新建 | `src/test/java/dev/mewcode/task/ToolsTest.java` | 4 个工具的单测 |
| 新建 | `src/main/java/com/mewcode/agent/RunToCompletion.java` | runToCompletion 方法实现(可作为 Agent.java 的同包补充) |
| 新建 | `src/test/java/dev/mewcode/agent/RunToCompletionTest.java` | runToCompletion / dontAsk / maxTurns 测试 |
| 新建 | `src/main/java/com/mewcode/agent/Fork.java` | buildForkedMessages + isForkContext + FORK_BOILERPLATE |
| 新建 | `src/test/java/dev/mewcode/agent/ForkTest.java` | Fork 消息构造与上下文识别测试 |
| 新建 | `src/main/java/com/mewcode/agent/AgentTool.java` | AgentTool + execute |
| 新建 | `src/test/java/dev/mewcode/agent/AgentToolTest.java` | Agent 工具调用、嵌套阻断、超时切后台测试 |
| 新建 | `src/main/java/com/mewcode/agent/ApprovalUpgrader.java` | ApprovalUpgrader 接口 + DEFAULT 实现 |
| 新建 | `src/main/java/com/mewcode/agent/AgentCatalogPort.java` | 接口,断开 agent ↔ subagent 循环依赖 |
| 新建 | `src/main/java/com/mewcode/agent/TaskManagerPort.java` | 接口,断开 agent ↔ task 循环依赖 |
| 新建 | `src/main/java/com/mewcode/tool/Filter.java` | ALL_AGENT_DISALLOWED / ASYNC_AGENT_ALLOWED / applyAgentToolFilter |
| 新建 | `src/test/java/dev/mewcode/tool/FilterTest.java` | 过滤多层防线测试 |
| 新建 | `src/main/java/com/mewcode/tui/Tasks.java` | consumeTaskDone + buildTaskNotification + ESC 切后台辅助 |
| 修改 | `src/main/java/com/mewcode/agent/Agent.java` | 加 systemPrompt/maxTurns/permissionMode/dontAsk/approvalUpgrader 字段;run 抽 runIter;runGuarded 加 dontAsk 短路 + approvalUpgrader 升级 |
| 修改 | `src/main/java/com/mewcode/agent/Agent.java`(Builder 内部类) | 加 systemPrompt / maxTurns / permissionMode / dontAsk / approvalUpgrader / provider 选项 |
| 修改 | `src/test/java/dev/mewcode/agent/AgentTest.java` | 不破坏既有测试 |
| 修改 | `src/main/java/com/mewcode/tool/ToolRegistry.java` | 不动(过滤逻辑在 Filter.java) |
| 修改 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | TuiParams 加 taskMgr/subAgentCatalog;MewCodeModel 持有;init 启 consumeTaskDone;AgentTool 注册后 setParent |
| 修改 | `src/main/java/com/mewcode/tui/Stream.java` | updateStreaming 加 ESC → adoptRunning 分支 |
| 修改 | `src/main/java/com/mewcode/tui/SkillFork.java` | 改造为调 subagent.LaunchFork.launch |
| 修改 | `src/test/java/dev/mewcode/tui/MewCodeModelTest.java` | 补 ESC 切后台、task-notification 注入测试 |
| 修改 | `src/main/java/com/mewcode/config/Config.java` | 加 enableSubAgentBackground(Boolean,默认 true) |
| 修改 | `src/main/java/com/mewcode/MewCode.java` | Catalog.load / new Manager / 4 个 task 工具注册 / AgentTool 注册 + setParent;taskMgr / subAgentCatalog 传给 MewCodeModel |

## T1: subagent 包的 Definition 与 Source 类型

**文件:** `src/main/java/com/mewcode/subagent/Definition.java`
**依赖:** 无
**步骤:**
1. 新建包 `com.mewcode.subagent`,加 `Definition.java`,声明 `enum Source` 类型与四个常量:
   - `BUILTIN`
   - `USER`
   - `PROJECT`
   - `PLUGIN`(占位)
2. `Source.toString()` 返回 `"builtin" / "user" / "project" / "plugin"`(用 switch 表达式)
3. 声明 `record Definition`,字段如 plan.md 所述:`name / description / tools / disallowedTools / model / maxTurns / permissionMode / dontAsk / background / systemPrompt / filePath / source`
4. 在 record 类注释里每个字段语义,引用 spec F4
5. `Definition.isFork()` 返回 `"__fork__".equals(name)`(便于 forkDefinition 判别)

**验证:** `./gradlew compileJava -pl . -am` 编译通过

## T2: subagent 解析器

**文件:** `src/main/java/com/mewcode/subagent/Parser.java`
**依赖:** T1
**步骤:**
1. 新建 `Parser.java`,从 `skills.Parser` 复制 `parseFrontmatterAndBody` 与 `UTF8_BOM` 常量(几乎 ✓ 不变,改包名)
2. 声明 `static final java.util.regex.Pattern AGENT_NAME_REGEX = Pattern.compile("^[A-Za-z][A-Za-z0-9-_]{0,31}$")`(大小写都允许,与 ch13 README 的 `Explore`/`Plan` 一致)
3. 实现 `static Definition parseDefinition(byte[] data, String filePath, Source source) throws ParserException`:
   - 调 `parseFrontmatterAndBody` 拿 frontmatter `Map<String,Object>` + body
   - SnakeYAML Engine 解析出 `Map<String,Object>` 后手动映射到一个临时类 `AgentFm`:
     ```java
     record AgentFm(
         String name,
         String description,
         java.util.List<String> tools,
         java.util.List<String> disallowedTools,
         String model,
         int maxTurns,
         String permissionMode,
         boolean background
     ) {}
     ```
   - 校验 `name` 非空且匹配 `AGENT_NAME_REGEX`
   - 校验 `description` 非空
   - 校验 `model`:空 / `"inherit"` / `"haiku"` / `"sonnet"` / `"opus"` 之一,其它 stderr 警告并改为 `"inherit"`
   - 解析 `permissionMode`:`"dontAsk"` 单独识别 → `Definition.dontAsk=true, Definition.permissionMode=PermissionMode.DEFAULT`;否则调 `PermissionMode.parse`,失败 stderr 警告并改为 `DEFAULT`
   - 把 fm 字段映射到 Definition 字段(`systemPrompt = body`,`filePath = filePath`,`source = source`)
4. 实现 `static Definition parseFile(java.nio.file.Path path, Source source) throws IOException, ParserException`:`Files.readAllBytes` + `parseDefinition`

**验证:** `./gradlew test -Dtest=ParserTest` 通过(对应 T3 的测试)

## T3: subagent 解析器测试

**文件:** `src/test/java/dev/mewcode/subagent/ParserTest.java`
**依赖:** T2
**步骤:**
1. JUnit 5 `@ParameterizedTest` + `@MethodSource`:正常完整 frontmatter / 仅必填 / model 非法 → 警告 fallback / permissionMode=dontAsk → dontAsk=true / 缺 name 报错 / 缺 description 报错 / frontmatter 未关闭 → 异常
2. body 区段提取:验证 `---` 后的内容(去 BOM 去前导换行)被完整取到 `systemPrompt`
3. 测试 `parseFile` 读取一个 testdata 下的 `.md` 文件(放在 `src/test/resources/subagent/testdata/`)
4. 每个用例附 `fail("case " + name + ": ...")` 描述

**验证:** `./gradlew test -Dtest=ParserTest` 全部通过

## T4: 内置 Agent 定义文件

**文件:** `src/main/resources/subagent/builtin/{general-purpose,explore,plan}.md`
**依赖:** 无
**步骤:**
1. 创建目录 `src/main/resources/subagent/builtin/`
2. `general-purpose.md`:
   ```yaml
   ---
   name: general-purpose
   description: 通用子 Agent,拥有全部工具,用于需要完整能力但独立上下文的场景
   maxTurns: 30
   ---

   你是 MewCode 的通用 Agent。根据用户的消息,使用可用工具完成任务。
   把任务做完,不要过度设计,但也不要做一半就停。
   完成后用简洁的报告回复:做了什么、关键发现。
   调用方会把结果转述给用户,所以只需要包含要点。
   ```
3. `explore.md`:
   ```yaml
   ---
   name: Explore
   description: 只读代码探索 Agent,适合搜索、阅读、理清调用链;不能修改文件
   disallowedTools:
     - write_file
     - edit_file
   model: haiku
   maxTurns: 30
   ---

   你是一个文件搜索专家。这是一个只读探索任务。
   严禁:创建文件、修改文件、删除文件、执行任何改变系统状态的命令。
   工具策略:Glob 做文件模式匹配、Grep 搜索文件内容、Read 读取已知路径、Bash 仅用于只读操作(ls、git log、find、cat)。
   尽可能并行发起多个工具调用。高效完成搜索请求,清晰报告发现。
   ```
4. `plan.md`:
   ```yaml
   ---
   name: Plan
   description: 计划 Agent,分析需求、制定执行计划,但不直接执行;主 Agent 拿到计划后逐步执行
   disallowedTools:
     - write_file
     - edit_file
     - Agent
   maxTurns: 15
   permissionMode: plan
   ---

   你是一个软件架构师和规划专家。这是一个只读规划任务。
   严禁:创建文件、修改文件、删除文件、执行任何改变系统状态的命令。
   工作流程:① 理解需求 ② 用搜索工具充分探索代码库 ③ 设计方案 ④ 输出分步实现计划。
   回复末尾必须列出 3-5 个对实现最关键的文件路径。
   ```

**验证:** 三个 `.md` 文件存在,frontmatter 合法;`Parser.parseFile` 测试不报错

## T5: subagent classpath resource 加载

**文件:** `src/main/java/com/mewcode/subagent/BuiltinLoader.java`
**依赖:** T2, T4
**步骤:**
1. 新建 `BuiltinLoader.java`,实现 `static java.util.List<Definition> builtinDefinitions()`:
   - 文件名清单写死:`"general-purpose.md"`, `"explore.md"`, `"plan.md"`(顺序无关,因为后面 catalog.list 会排序)
   - 对每个名字:`InputStream in = BuiltinLoader.class.getResourceAsStream("/subagent/builtin/" + name)`
     - `in == null` → 抛 `RuntimeException("builtin agent missing: " + name)`
     - 读完字节后调 `Parser.parseDefinition(bytes, "classpath:subagent/builtin/" + name, Source.BUILTIN)`
   - 解析失败 → 抛 `RuntimeException`(代码 bug,启动期失败即灾难)
2. 返回 `List<Definition>`,按 name 升序

> 备注:`getResourceAsStream` 路径必须以 `/` 开头(从 classpath 根读),Gradle 会把 `src/main/resources` 打入 jar 根。

**验证:** `./gradlew test -Dtest=CatalogTest` 中 builtin 部分通过(T7)

## T6: Catalog 与三层加载

**文件:** `src/main/java/com/mewcode/subagent/Catalog.java`
**依赖:** T1, T2, T5
**步骤:**
1. 新建 `Catalog.java`,声明:
   ```java
   public final class Catalog {
       private final Object lock = new Object();
       private final java.util.Map<String, Definition> defs = new java.util.HashMap<>();
       private final java.util.EnumMap<Source, java.util.List<Definition>> bySource =
               new java.util.EnumMap<>(Source.class);
   }
   ```
2. 实现 `public static Catalog load(java.nio.file.Path root)`:
   - `Catalog c = new Catalog();`
   - 加载 builtin → `c.addAll(BuiltinLoader.builtinDefinitions(), Source.BUILTIN)`
   - 加载 user → `c.addAll(loadFromDir(Path.of(System.getProperty("user.home"), ".mewcode/agents"), Source.USER), Source.USER)`
   - 加载 project → `c.addAll(loadFromDir(root.resolve(".mewcode/agents"), Source.PROJECT), Source.PROJECT)`
   - plugin 层本期跳过
3. 实现 `private static List<Definition> loadFromDir(Path dir, Source source)`:
   - 目录不存在 → 返回 `List.of()`
   - `Files.list(dir).filter(p -> p.toString().endsWith(".md"))` 遍历,逐个 `Parser.parseFile`;失败 stderr 警告并跳过
   - 返回 list
4. 实现 `private void addAll(List<Definition> defs, Source source)`:
   - 同名时高优先级覆盖(因为按 builtin → user → project 顺序加载,后加的优先级更高,直接 `defs.put(name, def)`)
   - 同时往 `bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(def)`
5. 实现 `public Optional<Definition> resolve(String name)`
6. 实现 `public List<Definition> list()`(按 name 升序)
7. 实现 `public List<Definition> listBySource(Source s)`
8. 实现 `public Definition forkDefinition()`:
   ```java
   return new Definition(
           "__fork__",
           "Fork-based subagent",
           List.of(), List.of(),     // tools / disallowedTools 留空 -> 工具集继承父
           "inherit", 25,
           PermissionMode.DEFAULT, false, false,
           "", "", Source.BUILTIN);
   ```

**验证:** `./gradlew test -Dtest=CatalogTest` 通过

## T7: Catalog 测试

**文件:** `src/test/java/dev/mewcode/subagent/CatalogTest.java`
**依赖:** T6
**步骤:**
1. 测试 `BuiltinLoader.builtinDefinitions()` 返回 3 个 def(general-purpose / Explore / Plan)
2. 测试三层覆盖:用 `@TempDir` 造一个项目 root 与一个 HOME 路径(用 `System.setProperty("user.home", ...)` 临时改),分别放 `explore.md`
3. 验证 `resolve("Explore")` 在三种情形下返回的 `source` 正确(都有 → PROJECT;只有 user+builtin → USER;只有 builtin → BUILTIN)
4. 测试 `forkDefinition()` 返回 `isFork()==true`
5. 测试加载错误处理:放一个非法 frontmatter 文件,加载后该文件 <em>*被跳过*</em>,其他文件仍正常

**验证:** `./gradlew test -Dtest=CatalogTest` 全部通过

## T8: 工具过滤多层防线

**文件:** `src/main/java/com/mewcode/tool/Filter.java`
**依赖:** 无
**步骤:**
1. 新建 `Filter.java`,声明三个常量:
   ```java
   public static final List<String> ALL_AGENT_DISALLOWED_TOOLS = List.of("Agent");
   public static final List<String> CUSTOM_AGENT_DISALLOWED_TOOLS = List.of();
   public static final List<String> ASYNC_AGENT_ALLOWED_TOOLS = List.of(
           "ReadFile", "WriteFile", "EditFile",
           "Glob", "Grep",
           "Bash",
           "load_skill", "install_skill"
   );
   ```
2. 声明 `record FilterParams`:
   ```java
   public record FilterParams(
       List<String> all,        // registry 的全部工具名
       int source,              // 1=BUILTIN, 2=USER, 3=PROJECT, 4=PLUGIN(数值需与 Source.ordinal()+1 对齐,这里用 int 避免反向依赖)
       boolean background,
       List<String> allowed,    // Agent 定义的 tools 白名单
       List<String> disallowed  // Agent 定义的 disallowedTools 黑名单
   ) {}
   ```
3. 实现 `public static List<String> applyAgentToolFilter(FilterParams p)`:
   按 spec F30 顺序:
   - 起点 = `new ArrayList<>(p.all())` 副本
   - 过滤 1:去除 `ALL_AGENT_DISALLOWED_TOOLS`
   - 过滤 2:若 `p.source() >= 2`(非 BUILTIN),再去除 `CUSTOM_AGENT_DISALLOWED_TOOLS`(本期为空,跳过)
   - 过滤 3:若 `p.background()`,与 `ASYNC_AGENT_ALLOWED_TOOLS ∪ {name | isMcpOrSkill(name)}` 取交集
   - 过滤 4:去除 `p.disallowed()`
   - 过滤 5:若 `!p.allowed().isEmpty()`,与之取交集
4. 辅助函数 `static boolean isMcpOrSkill(String name)`:`name.startsWith("mcp__")` || ... skill 工具的识别本期暂不接入(主 Registry 不区分,先按名字前缀 + 内置基础工具白名单兜底)

**验证:** `./gradlew compileJava -pl . -am` 编译通过

## T9: 工具过滤测试

**文件:** `src/test/java/dev/mewcode/tool/FilterTest.java`
**依赖:** T8
**步骤:**
1. `@ParameterizedTest` 覆盖各组合:
   - 默认:无后台、无白名单、无黑名单 → 去 Agent 即可
   - 后台:取 `ASYNC_AGENT_ALLOWED_TOOLS` 交集
   - 黑名单:`disallowed=List.of("Bash")` → 不含 bash
   - 白名单:`allowed=List.of("ReadFile","Grep")` → 仅这两个
   - 黑+白:白名单先收窄,黑名单再剔除
   - 后台 + MCP 工具:MCP 工具(`mcp__xxx`)被保留(白名单 OK)
2. 单独测试 `isMcpOrSkill` 边界

**验证:** `./gradlew test -Dtest=FilterTest` 通过

## T10: Agent 包扩展 - 新增 Builder 选项

**文件:** `src/main/java/com/mewcode/agent/Agent.java`
**依赖:** 无
**步骤:**
1. 在 `Agent` 类加字段:
   ```java
   private final String systemPrompt;       // 非空覆盖默认 system prompt
   private final int maxTurns;              // 0=用全局 MAX_ITERATIONS
   private final PermissionMode permissionMode;
   private final boolean permissionModeSet; // 区分零值与未设置
   private final boolean dontAsk;
   private final ApprovalUpgrader approvalUpgrader;
   ```
2. 在 `Agent.Builder` 加 6 个新选项:
   ```java
   public Builder systemPrompt(String s) { this.systemPrompt = s; return this; }
   public Builder maxTurns(int n) { if (n > 0) this.maxTurns = n; return this; }
   public Builder permissionMode(PermissionMode m) {
       this.permissionMode = m; this.permissionModeSet = true; return this;
   }
   public Builder dontAsk(boolean b) { this.dontAsk = b; return this; }
   public Builder approvalUpgrader(ApprovalUpgrader fn) { this.approvalUpgrader = fn; return this; }
   public Builder provider(Provider p) { this.provider = p; return this; }
   ```
3. 加 javadoc 解释每个选项语义

**验证:** `./gradlew compileJava` 编译通过

## T11: ApprovalUpgrader 接口

**文件:** `src/main/java/com/mewcode/agent/ApprovalUpgrader.java`
**依赖:** T10
**步骤:**
1. 新建文件,声明:
   ```java
   @FunctionalInterface
   public interface ApprovalUpgrader {
       Optional<Outcome> upgrade(AtomicBoolean cancelFlag, ApprovalRequest req);
       ApprovalUpgrader DEFAULT = (cancel, req) -> Optional.empty();
   }
   ```
2. javadoc 解释:子 Agent 把审批请求升级到父 TUI 的回调;返回 `Optional.empty()` 时调用方应走默认 emit Approval 路径

**验证:** `./gradlew compileJava` 编译通过

## T12: Fork 路径辅助函数

**文件:** `src/main/java/com/mewcode/agent/Fork.java`
**依赖:** 无(纯函数)
**步骤:**
1. 新建 `Fork.java`,声明常量:
   ```java
   public static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

   public static final String FORK_BOILERPLATE = """
           <fork_boilerplate>
           你是一个 Fork 出来的工作进程。你不是主 Agent。
           规则(不可协商):
           1. 不能再 Fork(调用 Agent 工具会被拦截)。
           2. 不要对话、不要提问、不要请求确认。
           3. 直接使用工具:读文件、搜索代码、做修改。
           4. 严格限制在你被分配的任务范围内。
           5. 最终报告以 "Scope:" 开头,500 字以内。
           </fork_boilerplate>

           """;
   ```
2. 实现 `public static List<Message> buildForkedMessages(List<Message> parentMsgs, String task)`:
   - 深拷贝 `parentMsgs`(参考 `Conversation.fromMessages` 的拷贝逻辑):每个 Message 复制 role/content/toolCalls/toolResults
   - 扫描末尾 assistant 消息的 `toolCalls`:对于每个未配对的 `toolCallId`,在 cloned 末尾追加 `ROLE_TOOL` 消息(每个 ID 一条 placeholder `ToolResult{content:"[forked, skipped]", isError:true}`)
     - 配对检查:看看 cloned 后续是否有 `ROLE_TOOL` 消息消费这些 ID
   - 追加最后一条 user 消息:`content = FORK_BOILERPLATE + task`
3. 实现 `public static boolean isForkContext(List<Message> msgs)`:
   - 遍历 `msgs`,若 user/tool/assistant 消息内容含 `FORK_BOILERPLATE_TAG` → 返回 true
   - 默认 false

**验证:** `./gradlew test -Dtest=ForkTest` 通过(T13)

## T13: Fork 辅助函数测试

**文件:** `src/test/java/dev/mewcode/agent/ForkTest.java`
**依赖:** T12
**步骤:**
1. 测试 `buildForkedMessages` 空 parent → 返回单条 user 消息含 Boilerplate + task
2. 测试 parent 末尾有完整 assistant + tool_result 配对:cloned 末尾 == parent 末尾 + 一条 user
3. 测试 parent 末尾 assistant 有 2 个 tool_use 没配对:cloned 中追加 1 条 `ROLE_TOOL`(2 个 placeholder ToolResult)再追加 1 条 user
4. 测试 `isForkContext`:消息中含 Boilerplate → true;不含 → false

**验证:** `./gradlew test -Dtest=ForkTest` 通过

## T14: runGuarded 加 dontAsk 短路与 approvalUpgrader

**文件:** `src/main/java/com/mewcode/agent/Agent.java`
**依赖:** T10, T11
**步骤:**
1. 修改 `runGuarded`,在 `case ASK:` 分支里:
   ```java
   case ASK -> {
       // 子 Agent dontAsk 模式:直接 Allow
       if (a.dontAsk) {
           return runTool(ctx, c);
       }
       // 子 Agent 升级到父 TUI 审批
       if (a.approvalUpgrader != null) {
           var maybe = a.approvalUpgrader.upgrade(cancelFlag, new ApprovalRequest(
                   c.name(), argsPreview(c.input()), reason, null /* upgrader 内部处理 respond */));
           if (maybe.isPresent()) {
               return switch (maybe.get()) {
                   case ALLOW_ONCE     -> runTool(ctx, c);
                   case ALLOW_FOREVER  -> { eng.persistLocalAllow(c); yield runTool(ctx, c); }
                   default              -> denyResult(c.id(), "用户拒绝了本次调用");
               };
           }
       }
       // 默认路径:emit Approval event(主 Agent inline / Skill fork 都走此)
       Outcome o = requestApproval(ctx, c, reason, sub);
       ...
   }
   ```
2. 修改 `check` 调用前,如果子 Agent 设了 `permissionMode`(`a.permissionModeSet == true`),用 `a.permissionMode` 覆盖入参 mode
3. 修改 `streamLoop` 拿 defs 处的 `allowedTools` 逻辑(已有,无须改)

**验证:** `./gradlew test -Dtest=AgentTest` 现有测试不破

## T15: runToCompletion 实现

**文件:** `src/main/java/com/mewcode/agent/RunToCompletion.java`(或直接放在 `Agent.java`)
**依赖:** T10, T14
**步骤:**
1. 实现:
   ```java
   public String runToCompletion(AtomicBoolean cancelFlag,
                                 ConversationManager conv,
                                 String task,
                                 BlockingQueue<AgentEvent> events) throws Exception
   ```
2. 逻辑:
   - 把 task 作为 user 消息:`if (!task.isEmpty()) conv.addUser(task);`(注意 conv 可能已经被 Fork 路径预装填,task=="" 时不追加)
   - 计算 maxTurns:`int turns = this.maxTurns; if (turns == 0) turns = MAX_ITERATIONS;`
   - 复用 `run` 的循环逻辑:但不用 publisher 返回事件,内部消费;改为返回 finalText + 抛异常
   - 拆出 helper `runIter(cancelFlag, conv, mode, iter, defs, sys, envText, reminder, eventsPub) -> RunIterResult(text, calls, done)` 让 `run` 和 `runToCompletion` 都调
   - `run` 改造为调 `runIter` 逐轮;`runToCompletion` 也是
   - 子 Agent 模式:`PermissionMode mode = PermissionMode.DEFAULT; if (this.permissionModeSet) mode = this.permissionMode;`
3. 退出条件:`done == true`(模型不再调工具)→ 返回 finalText;触达 turns → 抛 `MaxTurnsReachedException`(消息附 finalText);`cancelFlag.get()` → 抛 `CancellationException`;出错 → 原样抛
4. 在每轮内继续做 hook 调度(PreToolUse / PostToolUse / Stop 等),但 SubAgent 不触发 memory update
5. events publisher 转发:把 Tool / Text / Approval 事件 `events.submit(...)` 出去(供 TaskManager / TUI 接收)

**验证:** `./gradlew test -Dtest=RunToCompletionTest` 通过(T16)

## T16: runToCompletion 测试

**文件:** `src/test/java/dev/mewcode/agent/RunToCompletionTest.java`
**依赖:** T15
**步骤:**
1. 用 mock provider(已有 testhelpers)模拟一个回合返回纯文本的子 Agent → `runToCompletion` 返回 `"ok"`,不抛异常
2. 模拟一个回合返回 tool_use(已知工具),下一轮返回纯文本 → 工具被执行、finalText="..."
3. 模拟模型一直调工具不出文本,触达 `maxTurns=3` → 抛 `MaxTurnsReachedException`
4. 测试 dontAsk:子 Agent 设 `dontAsk(true)` + 模型调一个 Ask 级工具(如 bash) → 工具被自动放行执行
5. 测试 approvalUpgrader 回调被命中:子 Agent 设了 upgrader,Ask 时 upgrader 被调用(用 mock upgrader 验证)
6. 测试 events publisher 转发:运行子 Agent 时通过 BlockingQueue.poll() 把 events 收集到 list,断言含 Tool/Text 事件

**验证:** `./gradlew test -Dtest=RunToCompletionTest` 全部通过

## T17: Agent 工具实现

**文件:** `src/main/java/com/mewcode/agent/AgentTool.java`
**依赖:** T8, T12, T15
**步骤:**
1. 新建文件,声明:
   ```java
   public final class AgentTool implements Tool {
       private final AgentCatalogPort catalog;  // 接口,避免反向依赖 subagent 包
       private final TaskManagerPort taskMgr;
       private volatile Agent parent;
       private final boolean bgEnabled;
   }

   // src/main/java/com/mewcode/agent/AgentCatalogPort.java
   public interface AgentCatalogPort {
       Optional<Definition> resolve(String name); // Definition 类型见下
       Definition forkDefinition();
       List<Definition> list();
   }

   // src/main/java/com/mewcode/agent/TaskManagerPort.java
   public interface TaskManagerPort {
       String launch(AtomicBoolean parentCancel, Agent ag, ConversationManager conv, String name, String task);
       String adoptRunning(AtomicBoolean parentCancel, Agent ag, ConversationManager conv, String name,
                           Flow.Subscription eventSub, AtomicBoolean cancelFlag, PartialState partial);
       Optional<Outcome> upgradeApproval(AtomicBoolean cancelFlag, ApprovalRequest req);
   }
   ```
2. **解决循环依赖**:agent 包不直接 import subagent 包,而是通过 port 接口反向适配;`subagent.Catalog implements AgentCatalogPort`,`task.Manager implements TaskManagerPort`。`Definition` 类型可以直接被 agent 包引用——subagent.Definition 只引用 `permission`,没问题。直接 `import com.mewcode.subagent.Definition`。
3. **AgentTool 接口实现**:
   - `name()` = `"Agent"`
   - `description()` 动态:基础描述 + `"subagent_type 可选值:" + String.join(", ", catalog.list().stream().map(Definition::name).toList())`
   - `schema()`:按 spec F1 写 JSON Schema(Jackson `ObjectNode`)
   - `readOnly()` = `false`
   - `execute(ctx, args)`:
4. **execute 主流程**:
   ```java
   AgentArgs aArgs = mapper.treeToValue(args, AgentArgs.class);
   if (aArgs.prompt() == null || aArgs.prompt().isEmpty()) return ToolResult.error("prompt is required");
   if (aArgs.description() == null || aArgs.description().isEmpty()) return ToolResult.error("description is required");

   // 防嵌套
   if (isSubAgentContext(ctx)) return ToolResult.error("subagent cannot spawn Agent");
   var parentConv = parentConvOf(ctx);
   if (parentConv != null && Fork.isForkContext(parentConv.messages()))
       return ToolResult.error("Fork subagent cannot spawn Agent (boilerplate detected)");

   // resolve 定义
   Definition def;
   if (aArgs.subagentType() != null && !aArgs.subagentType().isEmpty()) {
       def = catalog.resolve(aArgs.subagentType())
                    .orElseThrow(() -> new IllegalArgumentException("unknown subagent_type: " + aArgs.subagentType()));
   } else {
       def = catalog.forkDefinition();
   }

   // 决定后台
   boolean background = def.background() || aArgs.runInBackground() || def.isFork();
   if (background && !bgEnabled) return ToolResult.error("background mode is disabled by config");

   // 工具过滤
   var allowed = Filter.applyAgentToolFilter(new Filter.FilterParams(
           registryAllNames(parent.registry()),
           def.source().ordinal() + 1,
           background,
           def.tools(),
           def.disallowedTools()));

   // provider
   Provider provider = parent.provider();
   // (model 字段切换 provider 的逻辑暂从简:本期不实现按模型切换,后续完善)

   // 构造子 Agent
   SessionRuntime subRuntime = new SessionRuntime(200_000);
   Agent subAgent = Agent.builder()
           .provider(provider).registry(parent.registry()).version(parent.version()).engine(parent.engine())
           .runtime(subRuntime)
           .allowedTools(allowed)
           .systemPrompt(def.systemPrompt())
           .maxTurns(def.maxTurns())
           .permissionMode(def.permissionMode())
           .dontAsk(def.dontAsk())
           .approvalUpgrader(taskMgr::upgradeApproval)
           .hookEngine(parent.hookEngine())
           .build();
   // 标记子 Agent 上下文(让递归 Agent 工具调用被拦截)
   var childCtx = withSubAgentContext(ctx);

   // 子 Conv
   Conversation subConv = new Conversation();
   if (def.isFork()) {
       var parentMsgs = parentConvOf(ctx).messages();
       var forked = Fork.buildForkedMessages(parentMsgs, aArgs.prompt());
       subConv = Conversation.fromMessages(forked);
   }

   // 后台路径
   if (background) {
       String taskId = taskMgr.launch(parent.cancelFlag(), subAgent, subConv, aArgs.name(), aArgs.prompt());
       return ToolResult.success(String.format("{\"task_id\":\"%s\",\"status\":\"async_launched\"}", taskId));
   }

   // 前台路径
   AtomicBoolean cancelFlag = new AtomicBoolean();
   ScheduledFuture<?> timeoutHandle = scheduler.schedule(
           () -> cancelFlag.set(true), AUTO_BACKGROUND_MS, TimeUnit.MILLISECONDS);
   BlockingQueue<AgentEvent> events = new BlockingQueue<>();
   PartialState partial = new PartialState("", 0, "", new Usage(0,0,0,0));
   Thread.startVirtualThread(() -> aggregatePartial(events, partial));

   try {
       String finalText = subAgent.runToCompletion(cancelFlag, subConv, aArgs.prompt(), events);
       timeoutHandle.cancel(false);
       events.close();
       return ToolResult.success(finalText);
   } catch (CancellationException ce) {
       events.close();
       String taskId = taskMgr.adoptRunning(parent.cancelFlag(), subAgent, subConv, aArgs.name(),
                                           null /* already done? */, cancelFlag, partial);
       return ToolResult.success(String.format("{\"task_id\":\"%s\",\"status\":\"timed_out_to_background\"}", taskId));
   } catch (Exception e) {
       events.close();
       return ToolResult.error("subagent error: " + e.getMessage());
   }
   ```
5. 实现辅助函数:`isSubAgentContext / withSubAgentContext / parentConvOf / aggregatePartial`
6. 提供 `setParent(Agent a)` 让 Main 在 `new MewCodeModel(...)` 之后回填 parent 引用

**验证:** `./gradlew test -Dtest=AgentToolTest` 通过(T18)

## T18: Agent 工具测试

**文件:** `src/test/java/dev/mewcode/agent/AgentToolTest.java`
**依赖:** T17
**步骤:**
1. 测试 missing prompt → 返回错误
2. 测试 unknown `subagent_type` → 返回错误
3. 测试 known `subagent_type`(用一个 mock catalog 注入)→ 子 Agent 跑动并返回结果
4. 测试 `run_in_background=true` → 返回 `async_launched` JSON
5. 测试嵌套:用 `withSubAgentContext` 包 ctx 后调 execute → 返回错误
6. 测试 `isForkContext` 兜底:用 forked subConv 调,Agent 工具拦截
7. 测试 `enableSubAgentBackground=false` 时 background 路径报错

**验证:** `./gradlew test -Dtest=AgentToolTest` 全部通过

## T19: task 包基础结构

**文件:** `src/main/java/com/mewcode/task/Manager.java`
**依赖:** T10, T15
**步骤:**
1. 新建包 `com.mewcode.task`,加 `package-info.java` 与 `Manager.java`
2. 声明 `enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }`(单独 `Status.java`)
3. 声明 `record Usage(long input, long output, long cacheWrite, long cacheRead)`(对齐 `agent.Usage`)
4. 声明 `BackgroundTask` 类(字段如 plan.md;字段大多 volatile;`getters` 不可省略)
5. 声明 `record PartialState(...)`
6. 声明 `Manager` 类:
   ```java
   private final Object mu = new Object();
   private final Map<String, BackgroundTask> tasks = new HashMap<>();
   private final Map<String, String> byName = new HashMap<>();
   private final BlockingQueue<String> donePub = new BlockingQueue<>(
       Executors.newVirtualThreadPerTaskExecutor(), 32);
   private final AtomicLong counter = new AtomicLong();
   ```
7. 实现 `public Manager()`:默认构造
8. 实现 `private String nextId()`:`counter.incrementAndGet()` 后格式化为 `task_<8 字节十六进制>`(用 `(Long.toHexString(System.nanoTime() ^ counter.get()) & 0xFFFFFFFFL)` 等取低 4 字节足够)
9. 实现 `get(id)` / `list()` / `subscribeDone()` 等查询方法,返回 `Optional` / 不可变 List

**验证:** `./gradlew compileJava` 通过

## T20: Manager.launch 实现

**文件:** `src/main/java/com/mewcode/task/Manager.java`
**依赖:** T19
**步骤:**
1. 实现:
   ```java
   public String launch(AtomicBoolean parentCancel, Agent ag, ConversationManager conv, String name, String taskText) {
       String id = nextId();
       AtomicBoolean cancelFlag = new AtomicBoolean();
       BackgroundTask bt = new BackgroundTask(id, name, ag, conv, taskText,
               Status.RUNNING, Instant.now(), cancelFlag);
       synchronized (mu) {
           tasks.put(id, bt);
           if (name != null && !name.isEmpty()) byName.put(name, id);  // 后启动覆盖前
       }

       Thread.startVirtualThread(() -> {
           BlockingQueue<AgentEvent> events = new BlockingQueue<>();
           Thread.startVirtualThread(() -> aggregateTaskEvents(events, bt));
           try {
               String text = ag.runToCompletion(cancelFlag, conv, taskText, events);
               bt.endTime = Instant.now();
               bt.status = Status.COMPLETED;
               bt.result = text;
           } catch (CancellationException ce) {
               bt.endTime = Instant.now();
               bt.status = Status.CANCELLED;
           } catch (Throwable t) {
               bt.endTime = Instant.now();
               bt.status = Status.FAILED;
               bt.err = t;
           } finally {
               events.close();
               if (!donePub.offer(id, 0, TimeUnit.MILLISECONDS, (sub, item) -> false)) {
                   System.err.printf("task manager: done publisher full, dropping notification for %s%n", id);
               }
           }
       });
       return id;
   }
   ```
2. 实现 `aggregateTaskEvents(BlockingQueue<AgentEvent> pub, BackgroundTask bt)`:订阅 publisher,每个 Tool PhaseStart 累加 `toolCount` + 更新 `lastActivity`;每个 Usage 累加到 `bt.usage`

**验证:** `./gradlew test -Dtest=ManagerTest` 通过(T22)

## T21: Manager.stop / adoptRunning / sendMessage / upgradeApproval

**文件:** `src/main/java/com/mewcode/task/Manager.java`
**依赖:** T20
**步骤:**
1. 实现 `boolean stop(String id)`:查 tasks → 调 `bt.cancelFlag.set(true)`;返回是否找到
2. 实现 `adoptRunning(...)`:与 launch 类似但接收已 derive 的 ag/conv/cancelFlag/eventSub;创建 BackgroundTask,把 PartialState 字段复制进去,起 virtual thread 继续消费 events publisher 并跑动(注意此时 ag.runToCompletion 已经在前台启动;前台 cancelFlag 被置 true 后子线程 done;Adopt 实际上是开一个 virtual thread 继续消费 events publisher 直到关闭)
   - 简化方案:adopt 不再调 runToCompletion(因为 runToCompletion 已在前台启动);只是注册 BackgroundTask 状态、聚合事件、等 events publisher 关闭后写终态、submit 到 donePub
   - cancelFlag 是新的 derive AtomicBoolean,stop 时用
3. 实现 `sendMessage(parentCancel, name, message)`:
   - 查 `byName` → id
   - 查 `get(id)` → bt;bt.status != COMPLETED → 抛 `TaskBusyException`
   - `bt.conv.addUser(message); bt.status = Status.RUNNING; bt.endTime` 不重置
   - 重新起 virtual thread 跑 `runToCompletion`(同样的 ag/conv);跑完逻辑同 launch
   - 返回 id
4. 实现 `Optional<Outcome> upgradeApproval(AtomicBoolean cancelFlag, ApprovalRequest req)`:把 req 转发到一个全局 publisher(`BlockingQueue<ApprovalRequest> approvalPub`);TUI 订阅;返回 `Optional.empty()` 时调用方走默认路径
   - 简化:本期 `upgradeApproval` 直接返回 `Optional.empty()`——让 Approval 走到子 Agent 自己的 publisher,TUI 通过 events 转发感知

**验证:** `./gradlew test -Dtest=ManagerTest -Dtest.method=stop` 通过

## T22: task 包测试

**文件:** `src/test/java/dev/mewcode/task/ManagerTest.java`
**依赖:** T20, T21
**步骤:**
1. 用 mock provider + mock agent 模拟一个 subAgent → launch → 用 `BlockingQueue.poll()` 等 donePub → 验证 `status==COMPLETED`,result 正确
2. 用一个故意 throw 的 mock agent → launch → donePub 收到 → `status==FAILED`,err 非空
3. stop:launch 后立刻 stop → donePub 收到 → `status==CANCELLED`
4. sendMessage:launch + 等 COMPLETED → sendMessage 重新跑 → 拿到新结果
5. byName 覆盖:launch 两次同 name → 后启动覆盖

**验证:** `./gradlew test -Dtest=ManagerTest` 全部通过

## T23: 4 个后台任务工具

**文件:** `src/main/java/com/mewcode/task/{TaskListTool,TaskGetTool,TaskStopTool,SendMessageTool}.java`
**依赖:** T19, T20, T21
**步骤:**
1. 实现 `TaskListTool`:
   - `name() == "TaskList"`,`readOnly() == true`,`schema()` 空对象
   - `execute`:返回 JSON 形如 `[{"id":"...","name":"...","status":"running","tool_count":3,"last_activity":"Bash"}, ...]`
2. 实现 `TaskGetTool`:
   - `name() == "TaskGet"`,`schema()` 含 `task_id` required
   - `execute`:`m.get(id)` → 全字段 JSON;找不到 → `ToolResult.error(...)`
3. 实现 `TaskStopTool`:
   - `name() == "TaskStop"`,`schema()` 含 `task_id` required
   - `execute`:`m.stop(id)` → `{"status":"cancellation_requested"}` 或错误
4. 实现 `SendMessageTool`:
   - `name() == "SendMessage"`,`schema()` 含 `name` / `message` required
   - `execute`:`m.sendMessage(cancelFlag, name, msg)` → `{"task_id":"...","status":"resumed"}` 或错误
5. 所有工具实现 `SystemTool` 接口标记(`isSystem() == true`),让它们在子 Agent 工具列表中默认豁免

**验证:** `./gradlew test -Dtest=ToolsTest` 通过(T24)

## T24: 4 个工具的单测

**文件:** `src/test/java/dev/mewcode/task/ToolsTest.java`
**依赖:** T23
**步骤:**
1. TaskList:launch 几个任务后调 → 返回 JSON 含所有
2. TaskGet:已知 id → 返回完整字段
3. TaskGet:未知 id → `Result.isError()==true`
4. TaskStop:stop 一个 running task → 返回成功 + task 状态变 CANCELLED
5. SendMessage:launch 一个任务跑完 → SendMessage → 返回新 status

**验证:** `./gradlew test -Dtest=ToolsTest` 全部通过

## T25: TUI 加 taskMgr / subAgentCatalog wiring

**文件:** `src/main/java/com/mewcode/tui/MewCodeModel.java`
**依赖:** T6, T19, T23
**步骤:**
1. 在 `TuiParams` record 加字段:
   ```java
   Manager taskMgr;
   Catalog subAgentCatalog;
   ```
2. 在 `MewCodeModel` 加字段:
   ```java
   private final Manager taskMgr;
   private final Catalog subAgentCatalog;
   ```
3. 在 `MewCodeModel` 构造内:
   - 把 params 字段挂到字段
   - `init()` 末尾启动 `Thread.startVirtualThread(this::consumeTaskDone)`
4. 在 Agent 构造之后(单 provider 路径):
   - 主 Agent 也应该携带 `approvalUpgrader`(其实主 Agent 不需要;但 AgentTool 构造时需要 `ApprovalUpgrader` 给子 Agent 用)
   - AgentTool 的 parent 通过 `setParent(mainAgent)` 回填

**验证:** `./gradlew compileJava` 通过

## T26: task notification 注入

**文件:** `src/main/java/com/mewcode/tui/Tasks.java`
**依赖:** T19, T25
**步骤:**
1. 新建文件,实现:
   ```java
   void consumeTaskDone() {
       taskMgr.subscribeDone().subscribe(new BlockingQueue.poll()<>() {
           Flow.Subscription sub;
           public void onSubscribe(Flow.Subscription s) { this.sub = s; s.request(Long.MAX_VALUE); }
           public void onNext(String id) {
               taskMgr.get(id).ifPresent(bt -> {
                   String notif = buildTaskNotification(bt);
                   if (runtime != null) runtime.appendReminders(List.of(notif));
               });
           }
           public void onError(Throwable t) { System.err.println(t); }
           public void onComplete() {}
       });
   }
   ```
2. 实现 `static String buildTaskNotification(BackgroundTask bt)`:
   ```
   <task-notification>
   Task <id> (name="<name>"): <status>
   Result: <result 或 错误>
   </task-notification>
   ```
3. javadoc 解释行为(F19)

**验证:** `./gradlew compileJava` 通过

## T27: ESC 切后台

**文件:** `src/main/java/com/mewcode/tui/Stream.java`
**依赖:** T19, T25
**步骤:**
1. 在 `updateStreaming` 内对 JLine/tui.tea `KeyType.Escape` 事件:
   ```java
   if (key.getKeyType() == KeyType.Escape && foregroundSubAgent != null) {
       // 移交后台
       String id = taskMgr.adoptRunning(parentCancel,
               foregroundSubAgent.agent(), foregroundSubAgent.conv(), foregroundSubAgent.name(),
               foregroundSubAgent.eventSub(), foregroundSubAgent.cancelFlag(), foregroundSubAgent.partial());
       foregroundSubAgent = null;
       // 显示一条通知
       scrollback.add(noticeBlock("[esc] 子 Agent 切到后台 (task=" + id + ")"));
   }
   ```
2. 增加 `foregroundSubAgent` 字段(record / 内部类)跟踪当前前台子 Agent;AgentTool 开始前台跑动时设置,跑完清除
3. 注意:前台子 Agent 的跑动其实是在 AgentTool 的 execute 内同步阻塞的,主 TUI 此时是 "等 tool_result" 状态。这意味着 ESC 拦截需要在 AgentTool 的 execute 内做(通过共享 `foregroundSubAgent` 状态)

**简化方案:** 由于前台子 Agent 在 AgentTool 同步阻塞内,ESC 切后台需要工具内监听 cancelFlag 一类机制。本期实现保守版:AgentTool 的前台路径只支持「超时自动切后台」,不支持 ESC 切后台;ESC 切后台留待后续 ch14+ 完善。在 plan.md 与 spec.md 里要标注这一变更。

**重要变更:** F17/AC11 调整为:本期 ESC 切后台**不实现**,只实现「超时自动切后台」与「显式 run_in_background」。spec.md 已写出,checklist 跳过 ESC 场景。

修改方向:跳过 T27 的 ESC 部分,只保留 `foregroundSubAgent` 字段供未来扩展。

**验证:** `./gradlew compileJava` 通过

## T28: Skill fork 改造

**文件:** `src/main/java/com/mewcode/tui/SkillFork.java`
**依赖:** T15
**步骤:**
1. 现有 `runSubAgent` 内部已经在用 `subAgent.run`;改造为用 `runToCompletion`:
   ```java
   String runSubAgent(AtomicBoolean cancelFlag, ConversationManager conv, ForkOptions opts) throws Exception {
       if (provider == null) throw new SubAgentNoProviderException();

       Provider prov = provider;
       // (model 切换逻辑保留)

       SessionRuntime subRuntime = new SessionRuntime(200_000);
       Agent subAgent = Agent.builder()
               .provider(prov).registry(registry).version(version).engine(engine)
               .runtime(subRuntime)
               .allowedTools(opts.allowedTools())
               .hookEngine(hookEngine)
               .build();

       // 直接调 runToCompletion(events=null,前台同步)
       return subAgent.runToCompletion(cancelFlag, conv, "" /* 此处 conv 末尾已含 user task */, null);
   }
   ```
2. **注意**:现有 `skills.Executor` 调用前已经把任务作为 user 消息装填到 conv(`buildForkConversation` 末尾 `conv.addUser(rendered)`)。新版 `runToCompletion` 内部又会 `conv.addUser(task)`;若 task=="" 会追加空消息。**改 `runToCompletion` 为允许 task=="" 时不追加**(`if (!task.isEmpty()) conv.addUser(task);`),或者改 `skills.Executor` 不再装填 user 消息让 `runToCompletion` 装填。
3. 选第一种方案——`runToCompletion` 加 if 判断

**验证:** `./gradlew test -Dtest=SkillsTest -Dtest=MewCodeModelTest` 现有测试不破

## T29: AgentTool 注册到 ToolRegistry

**文件:** `src/main/java/com/mewcode/MewCode.java`
**依赖:** T17, T20, T23, T25
**步骤:**
1. 在 `MewCode.java` 适当位置(`skills.Catalog.load` 之后):
   ```java
   Catalog subAgentCatalog = Catalog.load(root);
   Manager taskMgr = new Manager();

   // 4 个 task 工具
   registry.register(new TaskListTool(taskMgr));
   registry.register(new TaskGetTool(taskMgr));
   registry.register(new TaskStopTool(taskMgr));
   registry.register(new SendMessageTool(taskMgr));

   // Agent 工具(parent 暂为 null,稍后 setParent)
   AgentTool agentTool = new AgentTool(subAgentCatalog, taskMgr, null,
           cfg.enableSubAgentBackground());
   registry.register(agentTool);
   ```
2. `new MewCodeModel(...)` 调用扩展 TuiParams:
   ```java
   MewCodeModel app = new MewCodeModel(..., new TuiParams(
           writer, memMgr, instructionText, memoryText,
           sessionsDir, catalog, hookEngine,
           taskMgr, subAgentCatalog));
   ```
3. `new MewCodeModel(...)` 返回后回填 parent:
   ```java
   Agent main = app.mainAgent();
   if (main != null) agentTool.setParent(main);
   ```
4. `MewCodeModel` 加 `public Agent mainAgent()` 方法返回 `this.agent`

**验证:** `./gradlew shadowJar` 编译通过;运行 mewcode 不报错

## T30: config 加 enableSubAgentBackground

**文件:** `src/main/java/com/mewcode/config/Config.java`
**依赖:** 无
**步骤:**
1. 在 `Config` record 加字段:
   ```java
   Boolean enableSubAgentBackground   // null = 默认 true
   ```
2. 加方法:
   ```java
   public boolean effectiveEnableSubAgentBackground() {
       return enableSubAgentBackground == null ? true : enableSubAgentBackground;
   }
   ```
3. 注释说明:默认 true;false 时所有 SubAgent 强制前台,Fork 路径会报错

**验证:** `./gradlew compileJava` 通过

## T31: subagent.LaunchFork 公用 wiring

**文件:** `src/main/java/com/mewcode/subagent/LaunchFork.java`
**依赖:** T6, T15, T17
**步骤:**
1. 新建 `LaunchFork.java`,实现:
   ```java
   public record ForkLaunchOpts(
           List<String> allowedTools,
           String model,
           ConversationManager conv,            // 已装填的子对话
           String systemPrompt,
           boolean background,
           BlockingQueue<AgentEvent> eventsSink,
           LlmClient client,
           ToolToolRegistry registry,
           PermissionEngine engine,
           String version,
           HookEngine hookEngine
   ) {}

   public static String launch(AtomicBoolean cancelFlag, ForkLaunchOpts opts) throws Exception
   ```
2. 实现细节:
   - 构造 SessionRuntime / Agent(类似 AgentTool 的前台路径)
   - 调 `runToCompletion(cancelFlag, opts.conv(), "" /* conv 已含 task */, opts.eventsSink())`
   - 返回 finalText / 抛异常
3. **避免循环依赖**:`subagent.LaunchFork` 引用 agent 包(为构造 Agent);agent 不引用 subagent(AgentTool 是 agent 包内部,工厂签名接受 `AgentCatalogPort` 接口避开 import)
   - 但 AgentTool 内还是要 `import com.mewcode.subagent.Definition`——因为 Definition 类型。这就形成 subagent ← agent 之间的混乱。
   - **拆解方案**:
     - `Definition` 类型放在 subagent 包
     - Catalog 行为通过 agent 包的 `AgentCatalogPort` 接口暴露(只用 `list` 必要方法)
     - `subagent.LaunchFork` 不返回到 agent 中,而是用 agent 暴露的 `runToCompletion` 公共 API
4. 简化:`AgentTool` 直接 `import subagent.Definition`;`subagent.LaunchFork` 也 import agent。**循环依赖!** 这条路走不通。
5. **真正方案**:
   - subagent 包只放 `Definition` / `Catalog` / 加载逻辑(纯数据)
   - `LaunchFork` 放在 agent 包内(因为它要构造 `Agent`)
   - `AgentTool` 也放 agent 包(已有)
   - `tui/SkillFork.java` 调 `agent.LaunchFork.launch(...)`(把 `Definition` 当参数传入)

**重新调整文件结构:**
- 删除 `src/main/java/com/mewcode/subagent/LaunchFork.java`(本任务取消)
- 新建 `src/main/java/com/mewcode/agent/LaunchFork.java` 实现 LaunchFork
- skills 的 fork 回调改为调 `agent.LaunchFork.launch`

**验证:** 见 T28 验证

## T32: 集成测试 - 完整路径

**文件:** `src/test/java/dev/mewcode/agent/AgentToolIntegrationTest.java`(新增)
**依赖:** T17, T20, T29
**步骤:**
1. 端到端 mock:构造一个 mock provider 让主 Agent 调 Agent 工具(`subagent_type="Explore"`),子 Agent 也跑回纯文本
2. 验证 tool_result 包含子 Agent 的 finalText
3. 验证子 Agent 工具调用没看到 Agent 工具(过滤生效)
4. 验证后台路径:`run_in_background=true` → 立即返回 `async_launched` JSON,主 Agent 继续

**验证:** `./gradlew test -Dtest=AgentToolIntegrationTest` 通过

## T33: 编译与综合测试

**依赖:** T1-T32
**步骤:**
1. `./gradlew shadowJar`
2. `./gradlew compileJava` (代码风格由 IDE 保证)(若启用)
3. `./gradlew test`

**验证:** 全部命令通过,无失败用例

## 执行顺序

```
T1 → T2 → T3
       ↘
        T5 → T6 → T7
       ↗
       T4
T8 → T9
T10 → T11 → T14
T10 → T12 → T13
T14, T15 → T16
T8, T12, T15 → T17 → T18
T19 → T20 → T21 → T22
T19 → T20 → T23 → T24
T6, T19, T23 → T25 → T26
T25 → T27(本期跳过 ESC)
T15 → T28
T30 → T29
T29 → T32
所有 → T33
```