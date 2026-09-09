# slash命令体系 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为；不依赖具体实现细节。

## 实现完整性

- [x] /help 输出含 12 条命令名/描述（验证：`./gradlew test -Dtest=BuiltinsTest#registerAll_allRegistered` 绿 + tmux 场景 A2）
- [x] /plan 不调用 conv.addUser、不触发 LLM 回合（验证：`./gradlew test -Dtest=MewCodeModelTest#testTuiDispatch_PlanLocalOnly` 绿）
- [x] /do 触发 LLM 回合且写入会话存档（验证：`./gradlew test -Dtest=MewCodeModelTest#testTuiDispatch_DoInjectsAndSends` 绿 + tmux 场景 D2 后 `tail -3 $SESSIONS_DIR/<latest>.jsonl` 含新增 role=user 记录）
- [x] Builtins.registerAll(reg) 注册了恰好 12 条命令（验证：`./gradlew test -Dtest=BuiltinsTest#registerAll_allRegistered` 绿）
- [x] 12 条命令的名字完整、命令名全小写、互不重复（验证：同上 + 看 test 断言列表）

## 注册中心行为

- [x] 名字冲突立即抛 `IllegalStateException`；异常信息含具体冲突名（验证：`./gradlew test -Dtest=CommandRegistryTest#registerDuplicateNameThrows` 绿）
- [x] 别名冲突立即抛 `IllegalStateException`；异常信息含具体冲突别名（验证：`./gradlew test -Dtest=CommandRegistryTest#registerDuplicateAliasThrows` 绿）
- [x] `visible()` 返回按 `name` 字典序排序的可见命令（验证：`./gradlew test -Dtest=CommandRegistryTest#visibleSorted` 绿）
- [x] `prefixMatch("/s")` 仅命中以 "s" 开头的命令名,不含别名匹配、不含描述匹配（验证：`./gradlew test -Dtest=CommandRegistryTest#prefixMatch` 绿）
- [x] `parse("/Help")` 返回 `Parsed("help", true)`；`parse("")` 与 `parse("hello")` 返回 `Parsed("", false)`；`parse("/help xx")` 返回 `Parsed("", true)`（尾随参数让 lookup miss 走未命中分支）；`parse("/ /help")` 返回 `Parsed("", true)`（验证：`./gradlew test -Dtest=DispatchTest` 绿）

## 命令分发与三类执行

- [x] 提交 `/help` 后输出 12 行命令名/描述,且不调用 `conv.addUser`、不触发 LLM 回合（验证：`./gradlew test -Dtest=MewCodeModelTest#testTuiDispatch_HelpListsAllBuiltins` 绿）
- [x] 提交未知命令 `/foobar` 后输出文本含子串"未知命令"与"/help"两个关键字；不触发 LLM 回合（验证：`./gradlew test -Dtest=MewCodeModelTest#testTuiUnknownSlashCommandFriendly` 绿）
- [x] 提交 `/Help`（大小写混合）与 `/help` 行为一致（验证：`./gradlew test -Dtest=MewCodeModelTest#testTuiDispatch_CaseInsensitive` 绿）
- [x] 空字符串/纯空白字符提交时既不进 LLM 也不进分发器（验证：人工跑 tmux 场景"空回车"看无任何输出新增）
- [x] /do 提交后会向 `conv.addUser` 追加文本"请按上面的计划开始执行。"且立即触发 LLM 回合（验证：`./gradlew test -Dtest=MewCodeModelTest#testTuiDispatch_DoInjectsAndSends` 绿 或 tmux 场景观察）
- [x] /review 提交后会向 `conv.addUser` 追加文本含子串"审查"且立即触发 LLM 回合（验证：同上）
- [x] /compact 在 `SessionState.IDLE` 触发 `Agent.runForceCompact`；非 idle 状态打印"请等待当前任务完成"（验证：既有 `testTuiSlashCompactRoutesToCommand` 迁移版 + 非 idle 情况新加用例）
- [x] handler 抛 non-null 异常时,用户看到 `errorBlock` 文案（验证：在单测中 mock 一个抛异常的 handler,断言 `app.pendingPrintln()` 含 `ERROR` 前缀）

## 12 条命令的具体输出

- [x] /help 输出包含完整字符串 `/help`、`/status`、`/memory`、`/permission`、`/session`、`/clear`、`/review`、`/exit`、`/plan`、`/do`、`/compact`、`/resume` 共 12 个名字（验证：tmux 场景截屏 grep）
- [x] /status 输出按顺序包含 6 行,每行 key 分别为 `Mode:`、`Tokens:`、`Tools:`、`Memories:`、`Model:`、`Directory:`（验证：`tmux capture-pane -p` 后按行 grep）
- [x] /memory 输出在 `MEMORY.md` 存在时至少列出 "MEMORY.md" 这个文件名（验证：tmux 场景观察）
- [x] /permission 输出当前 mode 的 `toString()` 形式（default/plan/acceptEdits/bypassPermissions 之一）（验证：tmux 场景对照状态栏徽章）
- [x] /session 输出至少 2 行,key 分别为 `Session:` 与 `Path:`（验证：tmux 场景截屏 grep）
- [x] /clear 后状态栏 Tokens 区域消失或显示 0；旧会话 JSONL 文件仍在磁盘上（验证：tmux 场景 + `ls $SESSIONS_DIR/`）

## 自动补全菜单

- [x] 输入框首字符输入 `/` 后,菜单立即激活并显示 12 条候选（验证：tmux 场景按 `/` 后 `capture-pane`）
- [x] 继续输入 `s`（输入框为 `/s`）后,菜单只剩 /session 和 /status（验证：tmux 场景）
- [x] 把输入框清空（全部退格）后菜单立即消失（验证：tmux 场景）
- [x] 按 ↓ 高亮下移、↑ 高亮上移；按 ESC 菜单消失且输入框文本保留（验证：tmux 场景）
- [x] 高亮 /status 后按回车,菜单消失、/status 立即执行、输入框清空（验证：tmux 场景）
- [x] 高亮 /session 后按 Tab,菜单消失、/session 立即执行（验证：tmux 场景）
- [x] 按 `/` 显示 12 条候选时,菜单可见行数 ≤ 8；按 ↓ 越过第 8 行后下方候选滚入视野
- [x] 菜单不显示 `hidden=true` 的命令（本期没有 hidden 命令；通过单测验证机制：在 `CommandRegistryTest` 中注册一条 `hidden=true` 看 `visible()` 不含它）

## 集成

- [x] /help、未命中提示、`Prompt.READY_HINT` 三处均不出现硬编码命令清单（验证：`grep -rE "/(exit|plan|do|compact|resume|help|clear|review|status|memory|permission|session)" src/main/java/com/mewcode/prompt/ src/main/java/com/mewcode/tui/Commands.java` 应只在新分发器代码内出现一次类型常量）
- [x] 状态栏左侧 mode 徽章渲染与本 spec 实施前完全一致（验证：`git diff main -- src/main/java/com/mewcode/tui/Styles.java + MarkdownRenderer.java | grep -E "(modeLabel|modeStatusStyle|statusBar)"` 应无内部逻辑变更）
- [x] /resume、/compact 沿用 ch09 的 `SessionState.IDLE` 限制（验证：T10 后跑既有 ch09 验收场景一遍）

## 编译与测试

- [x] `./gradlew shadowJar` 编译无错误
- [x] `./gradlew test` 全部通过
- [x] `./gradlew spotbugs:check`（若启用）输出为空
- [x] `./gradlew compileJava` (代码风格由 IDE 保证) 无 diff
- [x] 无未提交的 import 整理改动（IntelliJ 自动 import 整理）

## 启动期冲突检测

- [x] 在 `Builtins.java` 中临时把某条命令注册两次,`./gradlew exec:java -Dexec.mainClass=com.mewcode.MewCode` 立即抛 `IllegalStateException` 退出,异常文本含具体冲突的命令名（验证：人工临时改动 + 跑 mewcode + 还原）

## 端到端场景（tmux 实跑）

> 验证目标：在真实 tmux 会话内运行 mewcode 二进制,按 plan/spec 描述跑完整流程。
> 准备：`./gradlew shadowJar`；开一个 `~/.mewcode/memory/MEMORY.md` 保证 /memory 至少能列一条；`SESSIONS_DIR=$PWD/.mewcode/sessions`（按 mewcode 实际默认目录；如未自定义则使用 `${MEWCODE_HOME:-$PWD/.mewcode}/sessions`）。

### 场景 A: 启动与 /help

- [x] **A1** 启动会话：`tmux new-session -d -s mewspec -x 200 -y 50 "java -jar build/libs/mewcode.jar 2>/tmp/mewcode.err"`；`sleep 2`；`tmux capture-pane -t mewspec -p | grep -E "(MewCode|Send a message|DEFAULT|PLAN|default|plan)"` 至少命中 2 个关键字
- [x] **A2** 跑 /help：`tmux send-keys -t mewspec '/help' Enter`；`sleep 1`；`tmux capture-pane -t mewspec -p | head -30` 含 /clear、/compact、/do、/exit、/help、/memory、/permission、/plan、/resume、/review、/session、/status 全部 12 个名字,且按字典序排列

### 场景 B: 纯本地命令

- [x] **B1** /status：`tmux send-keys -t mewspec '/status' Enter`；`sleep 1`；capture 含 `Mode:` `Tokens:` `Tools:` `Memories:` `Model:` `Directory:` 6 行 key 名；Mode 值与状态栏徽章一致
- [x] **B2** /permission：`tmux send-keys -t mewspec '/permission' Enter`；capture 输出含当前 mode 的 `toString()`（例如 `default` 或 `plan`）
- [x] **B3** /memory：`tmux send-keys -t mewspec '/memory' Enter`；capture 至少有 1 行文件名,无报错
- [x] **B4** /session：`tmux send-keys -t mewspec '/session' Enter`；capture 含 `Session:` 与 `Path:` 两行 key
- [x] **B5** /help、/status、/permission、/memory、/session 跑完后状态栏 Tokens 计数保持 0 in / 0 out（纯本地不消耗 token）

### 场景 C: 自动补全菜单

- [x] **C1** 仅按 `/`：`tmux send-keys -t mewspec '/'`；`sleep 0.3`；capture 看到补全菜单含 12 条候选,在输入框下方
- [x] **C2** 继续按 `s`：`tmux send-keys -t mewspec 's'`；capture 看到菜单只剩 /session 和 /status
- [x] **C3** 按 ↓ 切高亮后回车执行次条命令：`tmux send-keys -t mewspec Down Enter`；capture 出现次条命令（如 /status）的特征输出 `Mode:`,反向证明高亮已切换
- [x] **C4** 按 Enter 执行：`tmux send-keys -t mewspec Enter`；capture 看到 /status 的 6 字段输出（高亮是 /status 时）
- [x] **C5** 输入 `/s` 后按 ESC：`tmux send-keys -t mewspec '/s' Escape`；`tmux capture-pane -t mewspec -p | tail -5` 输入框行（带 ╭─╯ 或边框的行）含 `/s` 字面且无命令执行输出
- [x] **C6** 把 `/s` 全部删空：`tmux send-keys -t mewspec BSpace BSpace`；capture 看到菜单消失

### 场景 D: 影响界面命令

- [x] **D1** /plan 切到 PLAN：`tmux send-keys -t mewspec '/plan' Enter`；`tmux capture-pane -t mewspec -p | tail -1 | grep -i "plan"` 命中
- [x] **D2** /do 切回 DEFAULT 并触发 AI 回复：`tmux send-keys -t mewspec '/do' Enter`；`sleep 5`（等流式开始）；capture 状态栏左侧变 DEFAULT、scrollback 出现 AI 回复内容
- [x] **D3** /compact 强制压缩：`tmux send-keys -t mewspec '/compact' Enter`；`sleep 5`；capture 看到 Compact 进度 notice、压缩完成后状态栏 Tokens 计数显著下降（或归零）
- [x] **D4** /clear 开新 session：记录当前 `ls $SESSIONS_DIR/` 的文件数 N；`tmux send-keys -t mewspec '/clear' Enter`；capture 看到"已清空当前会话,开启新 session"类似 notice；`ls $SESSIONS_DIR/` 文件数变为 N+1（旧会话保留 + 新会话开启）

### 场景 E: 提示词命令

- [x] **E1** /review 注入审查请求：`tmux send-keys -t mewspec '/review' Enter`；`sleep 5`；capture 状态栏进入流式 + AI 开始回复；检查最新 session JSONL 文件：`tail -3 $SESSIONS_DIR/<latest>.jsonl` 含一条 role=user 含"审查"关键字的消息

### 场景 F: /resume 恢复旧会话（集成 D4 后续）

- [x] **F1** 在 D4 之后,`tmux send-keys -t mewspec '/resume' Enter`；capture 看到会话列表（`SessionState.RESUMING`）；使用 ↓ 选中 D4 之前的旧会话,Enter；`sleep 2`；capture 看到旧会话的 scrollback 内容已重新出现 + 状态栏 Tokens 计数为旧会话累计值（会话文件位于 `$SESSIONS_DIR/`）
- [x] **F2** 空回车验证见 G2；纯空白字符（全空格 + 回车）见 G2 同场景

### 场景 G: 未命中与异常

- [x] **G1a** 发命令前 baseline：`tmux capture-pane -p -t mewspec | grep -E "Tokens" > /tmp/g1_before.txt`
- [x] **G1b** 发未知命令：`tmux send-keys -t mewspec '/foobar' Enter`；`sleep 2`
- [x] **G1c** capture 后断言（a）`tmux capture-pane -p -t mewspec` 含"未知命令"与"/help"两个关键字、（b）`tmux capture-pane -p -t mewspec | grep -E "Tokens"` 与 `/tmp/g1_before.txt` 完全一致、（c）capture 中无 AI 回复块标志（没有 assistant role 输出）、（d）`tail -3 $SESSIONS_DIR/<latest>.jsonl | grep -E '"role":"assistant"'` 无新增
- [x] **G2** 仅按 Enter（空回车）与纯空白字符（全空格 + 回车）：`tmux send-keys -t mewspec Enter`；`tmux send-keys -t mewspec '   ' Enter`；capture 无新增 notice、无新 user 消息

### 场景 H: 启动期冲突检测

- [x] **H1** 临时编辑 `src/main/java/com/mewcode/command/Builtins.java`,把 /help 命令再注册一次同名；`./gradlew -q exec:java -Dexec.mainClass=com.mewcode.MewCode 2>&1 | head -10`；输出含 `IllegalStateException` 与 "help"（或具体冲突文本）；还原 `Builtins.java`

### 场景收尾

- [x] **/exit 端到端验证**：`tmux send-keys -t mewspec '/exit' Enter`；`sleep 1`；`tmux has-session -t mewspec 2>&1` 应报 "can't find session"
- [x] **错误日志可观察**：`wc -l < /tmp/mewcode.err` 输出为 0 或仅含白名单 info 行
- [x] 关闭测试会话（若 /exit 未成功）：`tmux kill-session -t mewspec 2>/dev/null || true`
- [x] 所有截屏证据复制到本任务的端到端验证报告里（每个场景至少 1 张 capture 输出）

## 完成准则

- 上面所有 checkbox 全部勾选,且每一项的"验证"步骤已实际执行并记录证据
- `git status` 无未跟踪/未提交的临时调试改动（冲突检测的 `Builtins.java` 临时改动已还原）
## 验证记录（2026-09-09）

- 全部验证已实际执行，capture 证据见 [E2E-Report.md](E2E-Report.md)。
- 单测新增：`CommandRegistryTest`、`DispatchTest`、`BuiltinsTest`（含 RecordingUi 桩）、`SlashDispatchTest`（真实 CortexModel 按键驱动，本地 `http://127.0.0.1:1` 防触网）；`./gradlew test` 全绿。
- 注记 1：`spotless:check` / `spotbugs:check` 未在 `build.gradle` 中配置，该项按不适用处理。
- 注记 2：N7 的 grep 在 `com.cortex.prompt.Reminder` 命中 `/do`——该处为阶段 3 的计划工作流模型指令文本（N10 要求 /do 外部行为不变，故保留），不属于"命令清单"硬编码；/help、未命中提示、就绪提示三处均已由注册中心单一信源驱动。
