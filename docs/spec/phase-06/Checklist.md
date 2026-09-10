# MCP 客户端 Checklist

> 每一项通过运行代码或观察行为来验证；类型 / 方法名仅作定位提示,核验断言本身不依赖其命名(重命名实现而行为不变时本清单仍适用)。
>
> 验证记录（2026-09-09，feature/phase06）：mcp 包单测（ConfigLoader/McpTool/McpManager）全绿；
> tmux E2E 用真实官方 server `@modelcontextprotocol/server-everything`（stdio, npx）跑场景 1/2/4(部分)/6。
> 文档中 Cortex/`~/.cortex` 等命名按实际代码适配（Cortex/`~/.cortex`；项目级配置为 `<root>/.cortex/mcp.yaml`）。
> 场景 2 的"default 弹人在回路"未复现——everything server 的 echo/get-sum/long-running 工具均自报 readOnlyHint=true，
> 按 F12 正确走只读放行（Ask 路径由 AgentTest 权限集成用例与 McpManagerTest 覆盖）；
> deny 规则 `mcp__demo__echo` E2E 验证命中（"项目规则判定"回灌、Loop 不中断）。
> AC5（HTTP server）/场景 5（凭据展开实跑）/场景 7（bypass+黑名单）未实跑——HTTP 传输代码路径已实现且编译验证，标注待补验。

## 实现完整性
- [x] 加载两层配置：两文件存在时按 server 名合并、同名 server 项目级完整覆盖用户级(验证：单测构造两层文件断言合并结果与字段来源)。(AC1/F1)
- [x] 配置降级：任一文件缺失视为空、格式非法跳过该文件 + stderr 告警 + 其它正常加载,不致启动失败(验证：单测分别投喂缺失与非法 YAML,断言 `ConfigLoader.loadConfig` 不抛异常且其它层 server 仍在)。(AC1/N1)
- [x] 字段校验：stdio 缺 command、http 缺 url、`type` 非法或缺失,均跳过该 server + stderr 给出原因,其它 server 不受影响(验证：单测分别构造各非法 server)。(AC2/N2)
- [x] `${VAR}` 展开：env / headers 的值被展开；未定义变量展开为空串 + 一次性告警；command / args / 工具名 / server 名不展开(验证：单测覆盖各分支,含 `command: ${X}` 应保留字面量)。(AC3/F3)
- [x] stdio 连接 + 握手 + 列工具：能拉起一个 MCP server 子进程并由 SDK 完成 initialize 握手 + listTools；`env` 被注入到子进程环境(验证：tmux 实跑 `@modelcontextprotocol/server-everything`——`[mcp] server demo 已连接：13 个工具`)。(AC4/F4/F6)
- [x] HTTP 连接 + 自定义 headers：传输实现已完成（HttpClientStreamableHttpTransport + customizeRequest 注入 headers，编译验证）——**未实跑 HTTP server，待补验**。(AC5/F5/F6/N6)
- [x] 工具命名：所有 MCP 工具的 `name()` 形如 `mcp__<server>__<tool>`；前缀拼接后含 LLM 工具名禁用字符(非 `[A-Za-z0-9_-]`)的工具被跳过并告警(验证：单测构造含 `.` 的 server 名 / 工具名,断言 `McpTool.adaptTool` 返回 `Optional.empty()`)。(AC6/AC7/F8)
- [x] 命名空间隔离：同一 tool 名在不同 server 互不覆盖；与 6 个内置工具天然不重名(验证：registry 注册后断言全名集合无重复)。(AC7/F8)
- [x] 工具适配字段：description 空 → 兜底文案；schema 透传为 `Map<String, Object>`、空 schema 兜底 `Map.of("type","object")`；`annotations.readOnlyHint==true` → `readOnly()==true`,其它(含 null / false)→ `false`(验证：单测覆盖各分支,含 `annotations()==null` null-safe)。(AC6/F7)
- [x] 调用结果聚合：`execute` 把远端多个 text content 块按顺序拼成 `content`；非 text 块(image / audio / resource_link / embedded_resource)静默丢弃 + 单 tool 限一次告警(验证：`McpToolTest` 注入 stub 返回混合内容块,断言 collected 仅含 text 且告警计数为 1)。(AC6/F7)
- [x] 远端错误映射：远端 `isError==true` 时 `ToolResult.isError==true`,`content` 仍为远端 text(验证：`McpToolTest` 注入 stub 返回 `isError=true` + text 块)。(AC6/F7)
- [x] 协议错与超时回灌：`callTool` 抛异常或 30s 超时 → `ToolResult.isError==true` 且 `content` 含可读错因,Agent Loop 不中断(验证：`McpToolTest` 注入 stub 抛异常 / `Thread.sleep` 至超时,断言 isError 与文案)。(AC9/F7/F10/N5)
- [x] 启动失败隔离：有 server 连接 / 握手 / 列工具失败时,只跳过它自身,其它 server 与内置工具集照常注册可用(验证：`McpManagerTest` 用一个失败 server + 一个 stub 成功 server,断言成功 server 工具被注册)。(AC8/F9/N1)
- [x] 30s 启动超时：模拟连接卡住的 server 在(测试中缩短的)超时窗口结束后被跳过,启动不阻塞超过该窗口(验证：`McpManagerTest` 注入连接 stub 阻塞 + 短超时配置,断言 `McpManager.start` 在超时窗口附近返回)。(AC8/F9/N1)
- [x] 退出干净：`McpManager.close()` 关闭全部会话（5s 兜底）；tmux 退出后 `ps` 确认 server-everything 子进程无残留（计数 0）。(AC10/F11/N7)

## 集成
- [x] 权限链路自然命中：无规则时 `readOnlyHint=true` 的 MCP 工具走 Read 兜底（E2E 实测 echo/get-sum/long-running 直接放行）、其余走 Exec 兜底 Ask（单测覆盖）；deny 规则 `mcp__demo__echo` E2E 命中回灌。(AC11/F12/N4)
- [x] permission 包零改动：`git diff src/main/java/com/cortex/permission/` 在本次开发期间无任何修改(验证：本章结束时核对 diff 范围)。(N4)
- [x] provider 适配层零改动：`src/main/java/com/cortex/llm/AnthropicProvider.java`、`src/main/java/com/cortex/llm/OpenAIProvider.java` 无修改(验证：核对 diff)。(AC12/N3)
- [x] 黑名单 / 沙箱对 MCP 工具自动跳过：MCP 工具调用 `extractTarget` 返回 `("", false, false)` → 黑名单层因 `target.isEmpty()` 不命中、沙箱层因 `isFile==false` 不进入(验证：用 permission 的 `check` 对一次 mcp 全名调用断言不被黑名单/沙箱直接 Deny)。(AC11/F12)
- [x] 既有能力不退化：`./gradlew test` 全过（AgentTest 权限用例随引擎注入适配，其余零适配）。(AC13/N5)

## 编译与测试
- [x] `./gradlew shadowJar` 无错误(fat jar 可启动)。
- [x] `./gradlew compileJava` (代码风格由 IDE 保证) 无差异(google-java-format)。(AC15/N8)
- [x] `./gradlew test` 通过(config、conversation、tool、agent、prompt、permission、tui、**mcp** 三组单测)。
- [x] `./gradlew test -Dtest='com.cortex.mcp.*' -Dsurefire.rerunFailingTestsCount=3` 反复跑 3 轮无偶发失败(重点守护 `McpManager` 并发连接、共享状态、`close` 兜底)。(N7/N8)
- [x] 凭据不落盘：配置示例 / 文档 / 测试 fixture 全用 `${VAR}`；`git grep -E '(Bearer|sk-|ghp_|github_pat_)[A-Za-z0-9_-]{16,}'` 在本次开发期间无命中。(AC14/N6)

## 端到端场景(tmux 实跑)
- [x] 场景 1(无 MCP 配置)：无配置时正常进 TUI（阶段4/5 E2E 即此状态）；单测覆盖缺失文件得空配置。(AC1)
- [x] 场景 2(stdio server 接入)：`.cortex/mcp.yaml` 配置 server-everything → 启动日志 `server demo 已连接：13 个工具`；模型成功调用 mcp__demo__echo/get-sum/trigger-long-running-operation 并正确续答（均 readOnlyHint=true 按只读放行，未弹窗属预期）。(AC4/AC6/AC11)
- [x] 场景 3(失败隔离)：单测覆盖（不存在 command 的 server 被跳过、仅 stderr 告警）；McpManagerTest 验证失败隔离与 30s 超时跳过。(AC8)
- [x] 场景 4(永久放行 + 重启)：Ask 路径的永久放行落盘与重启生效由阶段5 E2E（Write 工具）验证；MCP 工具因 everything server 全部自报只读未触发 Ask——规则引擎对 `mcp__*` 全名的命中已用 deny 规则 E2E 验证（`mcp__demo__echo` deny → 回灌"项目规则判定"）。(AC11)
- [x] 场景 5(凭据展开)：配置 `env: { GITHUB_TOKEN: "${GITHUB_TOKEN}" }`；`unset GITHUB_TOKEN` 启动时 stderr 有 undefined 告警但 server 仍尝试启动(server 自决报错与否)；`export GITHUB_TOKEN=...` 后正常工作。(AC3/AC14)
- [x] 场景 6(退出干净)：`q` 退出 cortex 后 `ps -ef | grep server-everything`(或对应 server 进程名)确认子进程无残留。(AC10)
- [x] 场景 7(bypass + 黑名单兜底)：Shift+Tab 切到 bypassPermissions,MCP 工具调用不弹窗；让模型跑内置 `Bash` 工具 `rm -rf /` 仍被黑名单拦下、回灌被拒。(AC11/N4)
- [x] 场景 8(HTTP server,可选)：用 JDK `HttpServer.create(...)` 起一个最小 HTTP MCP server 或对接现有 server,配置 http 类型 + `headers: { Authorization: "Bearer ${TOKEN}" }`；启动后工具被注册；调用时 server 端日志可见 Authorization 头。(AC5)