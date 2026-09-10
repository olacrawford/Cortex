# Worktree 隔离 端到端验证报告

> 环境：macOS tmux（200×50），JDK Temurin 21，独立工作区 `/tmp/cortex13-e2e`（真实 git 仓库 + 真实
> provider 配置 deepseek-v4-flash；git 提交含 README.md / scratch_ch14.txt / .env(ignored) + .worktreeinclude）。
> 单测：`./gradlew test` 全绿（新增 WorktreeSlugTest / SessionStoreTest / GitHelperTest / WorktreeManagerTest /
> WorktreeCreateTest / WorktreeLifecycleTest / WorktreeSweepTest / PostCreationSetupTest / ToolContextTest /
> ToolCwdTest / WorktreeCommandTest / AgentWorktreeRunnerTest / AgentToolWorktreeTest + ParserTest.isolation 用例）。
> 实跑期间发现并修复一个真实缺陷（见文末「实跑修复」）。

## 启动期（stderr 观察通道）

```
[worktree] warn: 建议 .gitignore 加入 .cortex/worktrees/ 与 .cortex/worktree_session.json（避免 Worktree 副本被 git 追踪）
```

E2E 仓库 .gitignore 未含 worktree 条目 → 精确警告（F35，只警告不修改）✓。

## 场景 1：isolation:worktree 子 Agent 写文件不影响主目录（AC15/AC16/AC22）

输入「用 worktree-writer 把 scratch_ch14.txt 覆盖为 SUBAGENT」：

```
● Agent({"subagent_type": "worktree-writer", "description": "用 write_file 覆盖 scratch_ch1…)
  ⎿ 已完成。我用 write_file 把 scratch_ch14.txt 覆盖为内容 SUBAGENT
     （写入路径：/private/tmp/cortex13-e2e/.cortex/worktrees/agent-aaf33b27/scratch_ch14.txt，9 字节）。
[Worktree 保留: /private/tmp/cortex13-e2e/.cortex/worktrees/agent-aaf33b27 ,分支 worktree-agent-aaf33b27]
```

- 主目录 `scratch_ch14.txt` 仍为 `MAIN`；Worktree 副本为 `SUBAGENT` ✓
- 有未提交修改 → autoCleanup 保留并追加 `[Worktree 保留: <path>,分支 <branch>]` ✓（G9/AC15）

## 场景 2：只读 isolation 任务自动清理

输入「用 worktree-writer 读 README.md 并总结，不要写任何文件」：

- tool_result 为总结文本，**不含**「Worktree 保留」字样 ✓
- `.cortex/worktrees/` 目录清空（agent-a* 已被 autoCleanup 删除）✓（AC12 对应场景）

## 场景 3：/worktree create + list 手动管理（AC17）

```
⊕ Worktree 已创建: /private/tmp/cortex13-e2e/.cortex/worktrees/demo-feature (分支 worktree-demo-feature)
⊕ demo-feature  /private/tmp/cortex13-e2e/.cortex/worktrees/demo-feature  worktree-demo-feature  [manual]
```

- 目录落地、分支 `worktree-demo-feature` 检出 ✓
- list 行含 `[manual]` 标记（手动创建不走自动清理）✓

## 场景 4：/worktree exit 变更保护（AC18/G8）

在 demo-feature 内制造修改后：

```
✖ worktree 有未提交修改或新增 commit，拒绝删除: …/worktrees/demo-feature（可加 --discard 强制删除）
⊕ 已退出并删除 Worktree: …/worktrees/demo-feature（分支 worktree-demo-feature）
```

- 未加 `--discard`：拒绝删除、目录仍在 ✓
- 加 `--discard`：删除成功，目录与分支均消失（`git branch --list worktree-demo*` 为空）✓

## 场景 5：explicit cwd——enter 后工具调用落在 Worktree 内（AC13/AC14/F28）

```
❯ /worktree enter cwd-test
❯ 用 read_file 读 probe.txt（相对路径）
  ⎿ 1	in-worktree-only          ← 读到的是 Worktree 副本内的文件（主目录无此文件）
❯ /worktree exit
❯ 再用 read_file 读一次 probe.txt
  ⎿ 文件不存在: probe.txt      ← cwd 还原到主目录后不可达
```

- enter 后 read_file 以 Worktree 路径解析相对路径；主 Agent 用 bash `ls` 复核主目录无此文件 ✓
- exit 后 cwd 还原，同一路径读取报不存在 ✓；全程 JVM 进程未 chdir（N4）✓

## 场景 6：Slug 校验阻止路径遍历（AC1/G2）

```
❯ /worktree create ../etc
✖ 无效的名称，已拒绝: worktree 名称的路径段不允许 . 或 ..: "../etc"     （未创建 .cortex/etc）
❯ /worktree create ..
✖ 无效的名称，已拒绝: worktree 名称的路径段不允许 . 或 ..: ".."
❯ /worktree create normal_one
⊕ Worktree 已创建: …/worktrees/normal_one (分支 worktree-normal_one)
```

路径遍历输入全部拒绝且无目录泄漏；合法名创建成功、随后 remove 干净（`git worktree list` 只剩主仓）✓。

## 实跑修复（E2E 发现的真实缺陷）

**运行时副本污染变更检查**：设置 A 把 `.cortex/config.yaml` 复制进 Worktree 后，该文件成为
未跟踪文件，`git status --porcelain` 非空 → `hasWorktreeChanges` 恒 true → 场景 2 的只读任务
Worktree 永远保留（autoCleanup 失效）。修复：`GitHelper.hasWorktreeChanges` 用 pathspec
`:(exclude).cortex` 排除运行时副本目录，真实用户变更仍正常触发保留。补充单测
（WorktreeCreateTest.创建后设置A 断言 status 排除后干净）后全量测试复跑全绿，场景 2 在新 jar 上
重新实跑验证通过。

> 附注：实跑中发现「流式期间输入的斜杠命令会被忽略」是阶段9 既有行为（流式态不收输入），
> 非本章缺陷；等待回合结束后重发即可。
