# slash命令体系 端到端验证报告

> 环境：macOS tmux（200×50），JDK Temurin 21（`/Library/Java/JavaVirtualMachines/temurin-21.jdk`），
> 独立工作区 `/tmp/cortex09-e2e`（含 `.cortex/config.yaml` 真实 provider 与 `.cortex/memory/MEMORY.md`）。
> 启动命令：`tmux new-session -d -s cortex09 -c /tmp/cortex09-e2e -x 200 -y 50 "java -jar build/libs/cortex.jar 2>/tmp/cortex09.err"`。
> 单测：`./gradlew test` 全绿（CommandRegistryTest / DispatchTest / BuiltinsTest / SlashDispatchTest 为阶段 9 新增）。

## 场景 A：启动与 /help

**A1 启动**（banner + 就绪提示引导 /help + 状态栏 DEFAULT 徽章）：

```
   Cortex v0.1.0 — LLM Terminal Coding Agent

工作目录: /private/tmp/cortex09-e2e
就绪。输入 /help 查看可用命令。
────────────────────────────────────……──────
❯ Send a message...
────────────────────────────────────……──────
DEFAULT                                                        deepseek-v4-flash
```

**A2 /help**（12 条命令、字典序、两列对齐）：

```
⊕ /clear      清空当前会话并开启新会话
/compact    手动压缩当前上下文
/do         退出计划模式并开始执行计划
/exit       退出 Cortex
/help       查看全部可用命令
/memory     查看已加载的记忆文件
/permission 查看当前权限模式
/plan       进入计划模式
/resume     恢复历史会话
/review     请求审查当前上下文中的代码变更
/session    查看当前会话信息
/status     查看运行状态（模式/用量/工具/记忆/模型/目录）
```

## 场景 B：纯本地命令

**B1 /status**（6 行 key:value 固定顺序，纯本地 Tokens 保持 0）：

```
⊕ Mode:      default
Tokens:    0 in / 0 out
Tools:     6 enabled
Memories:  1 files
Model:     deepseek-v4-flash
Directory: /private/tmp/cortex09-e2e
```

**B2 /permission** → `⊕ default`（与状态栏徽章一致）。
**B3 /memory** → `⊕ MEMORY.md`（存在时）；目录为空时 → `⊕ 无已加载的记忆文件`（两种均实测）。
**B4 /session**：

```
⊕ Session: 20260909-224121-bd17
Path: <workspace>/.cortex/sessions/20260909-224121-bd17/conversation.jsonl
```

**B5** 本地命令执行后状态栏 Tokens 计数保持不变（0 in / 0 out），纯本地不耗 token ✓。

## 场景 C：自动补全菜单

**C1 仅按 `/`**（12 条候选超出 MAX_ROWS=8，首屏 8 条 + 滚动提示，紧贴输入框下方）：

```
❯ /
▸ /clear      清空当前会话并开启新会话
  /compact    手动压缩当前上下文
  /do         退出计划模式并开始执行计划
  /exit       退出 Cortex
  /help       查看全部可用命令
  /memory     查看已加载的记忆文件
  /permission 查看当前权限模式
  /plan       进入计划模式
  ↓ 4 more
```

**C2 输入 `/s`**（前缀过滤）：

```
❯ /s
▸ /session 查看当前会话信息
  /status  查看运行状态（模式/用量/工具/记忆/模型/目录）
```

**C3/C4 ↓ + 回车执行次条**：高亮移到 /status 后回车，输出 B1 的 6 字段内容，菜单关闭、输入框清空 ✓。
**C5 ESC**：菜单消失、输入框保留 `❯ /s` ✓。
**C6 退格删空**：菜单立即消失 ✓。
（零匹配：输入 `/sx` 显示"（无匹配命令）"，回车走未命中提示分支 ✓。）

## 场景 D：影响界面命令

**D1 /plan**：输出"⊕ 已进入计划模式……"，状态栏徽章变 PLAN；再 /do 后回到 DEFAULT ✓。
**D2 /do**：scrollback 出现 `❯ 请按上面的计划开始执行。`，状态栏进入流式；JSONL 实时落盘：

```
{"role":"user","content":"请按上面的计划开始执行。","ts":1788965001}
{"role":"assistant","content":"","tool_calls":[{"id":"call_00_ESB74…","name":"bash",…},{"id":"call_01_…","name":"glob",…}],"ts":1788965003}
```

（DEFAULT 模式下 bash 触发三选一批准菜单 → 批准后回合完成，状态栏 `DEFAULT ↑9.8k ↓1.2k tok` ✓）
**D3 /compact**：`⊕ 已压缩,token 从 7907 降至 7400`，且会话 JSONL 追加 `{"type":"compact",…}` 标记 ✓。
**D4 /clear**：`⊕ 已结束当前会话,开启新会话 20260909-224505-3624`；sessions 目录 2→3（旧会话保留 + 新会话开启），旧 JSONL 完整保留 ✓。

## 场景 E：提示词命令

**E1 /review**：scrollback 出现 `❯ /review`，状态栏进入流式、AI 开始审查（调用 bash 收集上下文）；恢复的会话 JSONL 追加：

```
{"role":"user","content":"请审查当前上下文中的代码变更与已读取的文件，指出潜在 bug、可读性问题和可简化处。","ts":1788965266}
```

## 场景 F：/resume 恢复旧会话

**F1**：`/resume` 列出 3 个会话（标题/模型/大小），↓ 选中 24030B 的旧会话回车：

```
⊕ 已恢复会话 20260909-224258-de29，共 8 条消息
```

8 条 = 从最后一个 compact 标记之后加载的消息数（沿用 ch09 约束）✓。恢复后 /review 注入的消息追加到旧会话 JSONL（行数递增）✓。

## 场景 G：未命中与异常

**G1 未知命令** `/foobar`：`⊕ 未知命令: /foobar,输入 /help 查看可用命令`；Tokens 计数不变、无 assistant 输出、JSONL 无新增 ✓。
**G2 空回车 / 纯空白**：无任何 notice、无新 user 消息（capture 中 ⊕ 计数为 0）✓。

## 场景 H：启动期冲突检测

**H1**：临时在 `Builtins.registerAll` 中重复注册 `help` → 进程立即终止：

```
启动失败: 命令名/别名冲突: help
```

（还原 `Builtins.java` 后重新构建，启动正常。）

## 收尾

- **/exit**：`tmux send-keys '/exit' Enter` → 2 秒后 `tmux has-session` 报 `no server running`，进程干净退出 ✓。
- **错误日志**：`wc -c /tmp/cortex09.err` = 0 ✓。
- 测试产物清理：repo 内误启动产生的 `.cortex/sessions/20260909-224121-bd17` 已删除（该目录被 gitignore，未进入版本控制）。
