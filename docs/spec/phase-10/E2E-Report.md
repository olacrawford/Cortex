# Skill 系统 端到端验证报告

> 环境：macOS tmux（200×50），JDK Temurin 21，独立工作区 `/tmp/cortex10-e2e`（真实 provider 配置）。
> 启动：`tmux new-session -d -s cortex10 -c /tmp/cortex10-e2e -x 200 -y 50 "java -jar build/libs/cortex.jar 2>/tmp/cortex10.err"`。
> 单测：`./gradlew test` 全绿（263 个，含阶段 10 新增 SkillCatalogTest / SkillExecutorTest / SkillInstallerTest）。

## 场景 1：空技能时 /skills

```
⊕ 无已安装技能。
```

（引导文案为中文约定版本，对应 checklist 引用的 "No skills installed." 项。）

## 场景 2：创建测试技能 → 重启 → 注册可见

`.cortex/skills/test-skill/SKILL.md`（frontmatter + body）创建后重启：

```
$ /skills
⊕ test-skill

$ /help
/test-skill 一个用于阶段10端到端验证的测试技能 [skill]
```

`[skill]` 后缀与 /help 字典序混排 ✓（F11/N7）。

## 场景 3：执行技能（inline）

`/test-skill` 回车：

```
❯ /test-skill
⊕ skill(test-skill) Successfully loaded skill
DEFAULT   deepseek-v4-flash  ↑1.5k ↓80 tok
```

- UI 成功提示（F12）✓
- SOP 作为 user 消息写入会话 JSONL 并触发真实 LLM 回合（模型调用 bash 列目录并总结）✓
- 回合结束后空闲态再执行第二次（流式期间按键被忽略，属阶段 3 既有语义）✓

## 场景 4：热重载（不重启改 SKILL.md）

SKILL.md body 改为「热重载验证：请只回复四个字——重载成功…」后直接再执行 `/test-skill`：

```
{"role":"user","content":"热重载验证：请只回复四个字——重载成功，不要执行任何工具。",…}
● 重载成功  (1s)
```

`getFull` 每次执行重读 body（F4），新内容立即生效 ✓。

## 场景 5：远程安装（InstallSkillTool）

自然语言发起：

```
❯ 请帮我安装这个技能：https://github.com/anthropics/skills/tree/main/skills/pdf
● install_skill({"url": "https://github.com/anthropics/skills/tree/main/skills/pdf"})
  是否继续?
  > 1. 允许本次 …
```

- 模型正确选择 install_skill 工具并传参 ✓
- 写盘 + 网络工具受权限门约束（DEFAULT 下 Ask，批准后执行）✓
- 安装结果（GitHub Contents API 递归拉取 → 暂存 → 原子落位）：

```
  ⎿ 已安装技能 pdf 到 /Users/ibupro/.cortex/skills，斜杠命令已就绪。
```

- 免重启验证：`/skills` 立即列出 `⊕ pdf`（reload + 重新注册回调生效，F13）✓
- 带参数调用验证：`/pdf --help` → `⊕ skill(pdf) Successfully loaded skill`（参数经 `## User Request` 段注入）✓

## 收尾

- `/exit` 干净退出（tmux server 关闭）✓
- `wc -c /tmp/cortex10.err` = 0 ✓
- 测试产物清理：`~/.cortex/skills/pdf` 已删除（远程安装实测后清理）；`/tmp/cortex10-e2e` 保留供复查。

## 实现映射说明（相对 checklist 的差异）

- 包/路径映射：`com.mewcode.*` → `com.cortex.*`；技能目录 `.mewcode/skills/` → `.cortex/skills/`（用户层 `~/.cortex/skills/`）。
- Checklist 中引用的参考实现行号（`MewCodeModel.java:494` 等）不适用于本仓库，接入点对应为：编目加载在 `Cortex.main`（`loadCatalog(root)`，两层一次完成）、命令注册在 `CortexModel.activate → wireSkillsToAgent / registerSkillCommand`、分发分支在 `CortexModel.dispatchSlash`（`description().endsWith("[skill]")`）。
- Plan.md 中的 LoadSkillTool（系统工具 + Active Skills 环境块注入）未纳入本期：Checklist（完成定义）未含该项；`buildActiveContext` 已实现并由单测覆盖，激活态记录在 `CortexModel.activeSkillNames`（/clear 时清空），供后续接入。
- fork 模式为程序化 API（`SkillExecutor.executeFork` + `SkillForkHost`，单测覆盖），斜杠触发 fork 技能时提示走程序化调用，TUI 未接入（与 spec 主流程一致）。
- 工具名沿用本仓库 snake_case 约定：`install_skill`（checklist 写作 `InstallSkill`）。
