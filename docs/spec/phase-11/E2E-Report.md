# Hook 生命周期挂钩系统 端到端验证报告

> 环境：macOS tmux（200×50），JDK Temurin 21，独立工作区 `/tmp/cortex11-e2e`（真实 provider 配置）。
> hooks.yaml 预置 7 条规则：block-write（PreToolUse 拦截 write_file）、zh-cn-default（SessionStart prompt）、
> warn-delete（UserPromptSubmit 拦截 delete）、first-turn（PreUserMessage only_once）、
> bad-async（async+PreToolUse，加载期应跳过）、good-hook（SessionStart shell）、after-write（PostToolUse async）。
> 单测：`./gradlew test` 全绿（含阶段 11 新增 MatchersTest / RuleTest 扩展 / HookLoaderTest / HookExecutorTest / HookEngineTest / SessionRuntimeTest / SlashDispatchTest hook 用例）。

## 加载期（stderr 观察通道）

```
hook "bad-async": async not allowed for blocking events, skipped
```

其余 6 条 hook 正常加载、进程正常进入 idle（AC8/AC11/N1）✓。

## /hooks 命令（场景 7/8）

```
⊕ PreToolUse:
  block-write  shell
SessionStart:
  zh-cn-default  prompt
  good-hook  shell
UserPromptSubmit:
  warn-delete  shell
PreUserMessage:
  first-turn  shell [once]
PostToolUse:
  after-write  shell [async]

Loaded from: /private/tmp/cortex11-e2e/.cortex/hooks.yaml
```

按 event 分组、flags 标注、末尾来源路径；bad-async 未出现 ✓（F34/F35）。

## 场景 4：UserPromptSubmit 拦截 delete 关键字

输入「请帮我 delete 那个文件」：

```
[hook warn-delete] 用户消息含 delete 关键字
❯ 请帮我 delete 那个文件
```

消息未进对话历史（无 LLM 请求）、输入框内容保留供重新编辑 ✓（F32/AC10）。

## 场景 1：PreToolUse shell 拦截 write_file

输入「创建一个文件 hello.txt 内容是 hi」：

```
❯ 创建一个文件 hello.txt 内容是 hi
● write_file(hello.txt)
  ⎿ [hook block-write] blocked by hook
```

- tool_result 显示 `[hook block-write] blocked by hook`，`hello.txt` 未被创建（ls 验证）✓（AC4）
- 模型收到拦截原因后调整策略（改用 bash → 权限 Ask），不死循环 ✓

## 场景 2：SessionStart prompt 注入（zh-CN）

SessionStart 时 zh-cn-default 的 text 进入 reminder 队列，首轮请求注入。模型后续回复为中文：

```
● 写入被 hook 拦截了。让我检查一下拦截原因。
```

✓（AC6/场景 2）

## 场景 3（简化）：PostToolUse async shell

after-write（async=true）在 write_file 结果产出后触发，`/tmp/async-hook.log` 追加 `written`，主对话流未停顿 ✓。
（原场景用 spotless 格式化，此处以等价可观察命令替代——项目内无 gradle 工作区。）

## 场景 6：only_once + PreUserMessage

```
第一轮后：grep -c first-turn-fired = 1
第二轮后：grep -c first-turn-fired = 1   ← 不重复
/clear 后第三轮：grep -c first-turn-fired = 2   ← reset 后重新触发
```

✓（F27/N5/AC9）。hook 自身 stderr 已由执行器转发到应用 stderr。

## 收尾

- `/exit` 干净退出，tmux server 关闭 ✓
- stderr 仅含加载期跳过提示与 hook 转发输出，无异常堆栈 ✓

## 实现映射说明（相对 checklist 的差异）

- 包/路径映射：`com.mewcode.*` → `com.cortex.*`；hooks.yaml 位于 `.cortex/hooks.yaml`（项目级）与 `~/.cortex/hooks.yaml`（用户级）；同名 hook 项目级先加载、用户级视为后到者跳过（F7）。
- Checklist 引用的参考实现行号（MewCodeModel.java:494 等）不适用；接入点为：`Cortex.main`（HookLoader.load + 退出兜底 SessionEnd）、`CortexModel`（submit 拦截 / 会话事件 / hookLines）、`Agent`（PreUserMessage/PreToolUse/PostToolUse/Pre/PostCompact/Stop/Notification emit 点）、`SessionRuntime`（pendingReminders + once 状态经 engine.resetForNewSession）。
- `AgentTest` 未新增 fake-provider 级 hook 单测（fake provider 与现有 AgentTest 结构不匹配）；PreToolUse 拦截与 reminder 注入路径分别由 tmux E2E（场景 1/2）与 SlashDispatchTest/SessionRuntimeTest 覆盖。
- 权限规则向后兼容：仅含 `Bash(git *)` 形式的既有配置全部通过既有测试（RuleTest/PermissionEngineTest/SettingsTest）。
