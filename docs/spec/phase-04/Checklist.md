# 系统提示工程化 Checklist

> 每一项通过运行代码或观察行为来验证,聚焦系统行为;括号内为验证方式与对应需求。
>
> 验证记录（2026-09-08，feature/phase04）：单测 PromptTest/EnvironmentTest/ReminderTest/AnthropicSystemTest/AgentTest 全绿；
> tmux 端到端用 `.cortex/config.yaml` 的 openai 兼容端点（deepseek）跑场景 2–6，场景 1 以 SmokeMain 在
> openai 兼容端点验证（`cache_read=1280`，次轮命中）；anthropic 腿因无 key 未验证。
> 文档中的 `Cortex` / `Provider` / `mvn` / `llm.System` 等命名按实际代码适配（`Cortex` / `LlmClient` / Gradle / `llm.SystemPrompt`）。

## 实现完整性

- [x] 模块化装配:系统提示由按优先级排列的固定模块拼成、模块间空行分隔(验证:`PromptTest` 断言身份段在工具使用段之前、以空行分隔)。(AC1/F1)
- [x] 挂载即扩展:新增一个模块只需出现在模块列表,装配自动按优先级插入,不改 `assembleSystem` 逻辑(验证:`PromptTest` 传入额外模块断言其落在预期位置)。(AC1/F1)
- [x] 可选空槽:自定义指令/已激活 Skill/长期记忆 content 为空时装配跳过、不留多余空行(验证:`PromptTest` 断言空模块不出现、无连续空行)。(AC2/F1)
- [x] 缓存确定性:连续两次构造稳定系统提示逐字节相等;改变环境信息不改变稳定块(验证:`PromptTest` 断言两次 `Prompt.buildSystemPrompt()` 相等、稳定块不含 date/git/cwd)。(AC5/F3/N1)
- [x] 环境信息呈现:系统提示第二段含工作目录、平台、当前日期、git 状态、版本、模型;与稳定块分属不同内容块(验证:`EnvironmentTest` 断言 `render()` 含各项;`AnthropicSystemTest` 请求 `system` 为两个 `TextBlockParam`)。(AC3/F2)
- [x] 双重强化:关键约定(优先用专用工具、编辑前必先读)在工具描述与系统提示模块文本中均出现(验证:`PromptTest` 断言系统提示含相关语句;检索 `EditFileTool`/`BashTool` 描述含强化语句)。(AC7/F5)
- [x] 补充消息注入机制:`Reminder.plan(...)` 输出以 `<system-reminder>` 标签包裹(验证:`ReminderTest` 断言标签存在)。(AC8/F6)
- [x] 缓存字段解析:provider 用量对外暴露缓存写/读;Anthropic 取 `cacheCreationInputTokens/cacheReadInputTokens`,OpenAI 取 `promptTokensDetails.cachedTokens`,缺字段为 0 不报错(验证:`AgentTest` fake 发缓存用量断言 `Event.Usage` 透传;SmokeMain 打印真实字段)。(AC6/F4/N6)
- [x] Anthropic 缓存断点真实发出:稳定块 `TextBlockParam.cacheControl` 非空、环境块为空(验证:`AnthropicSystemTest` 断言——守护「cacheControl 字段挂错位置」回归)。(AC4/F3)

## 集成

- [x] 规划模式按轮次注入:`/plan` 后 iter1 注入完整提醒、间隔轮(每 4)重复完整、其余轮精简;reminder 不写入持久历史(验证:`AgentTest` 多轮脚本断言各轮 `req.reminder()` 详略 + `conv.messages()` 不含 reminder 文本)。(AC9/F6/F7)
- [x] 规划模式工具集:规划模式 `req.tools()` 仅只读、普通模式全量(验证:`AgentTest` 断言两模式 tools 差异)。(AC9/F7)
- [x] 稳定系统提示跨模式一致:普通与规划模式 `req.system().stable()` 相同(规划提醒已移出系统通道)(验证:`AgentTest` 断言两模式 stable 相等)。(F7/N1)
- [x] 历史合法:注入 reminder 后请求消息序列角色合法(Anthropic 并入末条 user、不产生连续 user)(验证:`AgentTest` 断言织入后末条仍为单一 user 回合;端到端 plan 多轮不报 400)。(AC12/N3)
- [ ] 跨协议一致:anthropic 与 openai(含兼容 base_url)都装配同一系统提示 + 环境段、注入同一 reminder(验证:两配置各跑多轮;anthropic 看缓存命中、openai 看 `cachedTokens`)。(AC10/F8) —— **anthropic 腿未验证（无 key）；openai 兼容腿已验证（SmokeMain 次轮 cache_read=1280）**
- [x] 不破坏 ch04:多轮连环、用户取消、流出错恢复、历史一致仍成立(验证:跑 ch04 端到端关键场景;`./gradlew test` 通过)。(AC11/N2)
- [x] 环境采集降级:非 git 目录/git 不可用时环境段对应项省略、不卡界面、请求正常发起(验证:在非 git 临时目录跑 TUI 观察)。(AC13/N4)
- [x] 界面不阻塞:环境采集(含 git 外调)不冻结界面、不显著拖慢首字(验证:正常目录跑,观察发起延迟无异常)。(N4)

## 编译与测试

- [x] `./gradlew compileJava` 无错误。
- [x] `./gradlew test` 通过(`ConfigLoaderTest`、`ConversationTest`、`PromptTest`、`EnvironmentTest`、`ReminderTest`、`AnthropicSystemTest`、`AgentTest`、`RegistryTest`)。
- [x] 代码规范随 IDE 保证（gradle 项目，无 spotless 配置）。(AC14/N7)
- [x] 多次 `./gradlew test -Dtest='AgentTest'` 稳定通过(无偶发竞争失败)。(N2/N6)
- [x] 密钥不回显:对话区、环境段与任何输出均不出现 `api_key`(验证:通读输出、检索无明文 key;环境段不含环境变量)。(AC14/N5)

## 端到端场景(tmux 实跑)

- [ ] 场景 1(缓存命中,Anthropic):同一会话连发两条消息 → smoke/调试打印首轮 `cache_write > 0`、次轮 `cache_read > 0`,证明稳定前缀被缓存复用。(AC4/F3) —— **anthropic 无 key 未跑；openai 兼容等价验证通过：SmokeMain 第 1 轮 cache_write/read=0，第 2 轮 cache_read=1280**
- [x] 场景 2(规划模式按轮次):`/plan` 发一个需多步只读调研的任务 → 模型仅用只读工具产出计划、按轮注入提醒（节奏由 AgentTest 确定性验证）；`/do` 切回全工具并按计划执行（write_file 落盘验证）。(AC9/F7)
- [x] 场景 3(reminder 不被当用户输入):规划模式下模型不复述/回应 `<system-reminder>` 内容,而是据其约束行事(验证:端到端全程检索输出 0 次出现标签内容)。(AC8/F6)
- [x] 场景 4(环境感知):问「我现在在哪个目录、什么平台、今天几号」→ 模型据环境段正确回答（另用 bash 复核日期）。(AC3/F2)
- [x] 场景 5(取消后可继续):规划模式多轮中途按 Esc 取消 → 回空闲态、再发一条继续对话不报 400（模型准确报告被取消任务的进度）。(AC12/N3)
- [x] 场景 6(非 git 目录降级):在非 git 目录启动 → 环境段省略 git 状态（模型主动指出「环境信息里并没有提供 git 仓库状态」）、正常对话。(AC13/N4)
