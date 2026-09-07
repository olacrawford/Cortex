# Cortex

Cortex 是一个用 Java 实现的、Claude Code 风格的终端 AI 编程助手：在终端里与大模型进行多轮对话，并让模型直接读写文件、执行命令来帮你完成实际任务。

当前实现覆盖：

- **多协议接入**：一份配置切换 Anthropic / OpenAI 协议，并支持自定义端点（`openai-compat`，可接 DeepSeek 等兼容服务）。
- **流式终端对话（TUI）**：回复逐字实时呈现，结束后以 Markdown 美化渲染；多轮上下文在单次会话内完整维护。
- **扩展思考**：开启后按协议请求思考，思考内容接收即丢弃，不回显到正文。
- **六个内置工具 + Agent Loop**：`read_file` / `write_file` / `edit_file` / `bash` / `glob` / `grep`；模型自主多轮循环调用工具直到任务完成（迭代上限、连续未知工具、用户取消、流出错等停止条件兜底），工具行以 Claude Code 风格展示，状态栏实时显示累计 token 用量与迭代轮次。
- **Plan Mode 两段式**：`/plan` 只放开只读工具让模型先出计划，`/do` 批准后切回全工具立即执行。
- **安全与健壮性**：API 密钥不回显；配置缺失、工具失败、命令超时等均以结构化结果/清晰提示呈现，不崩溃、不中断会话。

## 安装运行

### 环境要求

- **JDK 21**：代码基于 Java 21（toolchain 固定为 21）。本仓库机器的默认 `java` 是 17，因此**请统一使用 Gradle wrapper（`./gradlew`）**，不要用裸 `./gradle`；运行 jar 时也需要一个 JDK 21 的 `java`（见下文「常见问题」）。
- 首次构建时 Gradle wrapper 会自动下载对应版本，无需额外安装 Gradle。

### 构建

在仓库根目录执行：

```bash
./gradlew shadowJar
```

产物为 **`build/libs/cortex.jar`**（主类 `com.cortex.Cortex`）。

可选命令：

```bash
./gradlew test                 # 运行全部测试
./gradlew test --tests '*RegistryTest'   # 只跑某个测试类
./gradlew compileJava          # 仅编译
```

### 配置密钥

程序启动时从仓库根目录加载 `.cortex/config.yaml`。该文件已被 `.gitignore` 忽略、不会提交，请从示例复制并填入真实密钥：

```bash
cp .cortex/config.yaml.example .cortex/config.yaml
```

编辑 `config.yaml`，在 `providers` 下列出你要接入的服务（可多项），每项字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 供应商标识名（界面状态栏左侧显示） |
| `protocol` | 是 | `anthropic` / `openai` / `openai-compat` |
| `api_key` | 是 | 认证密钥（不回显，切勿打印或提交） |
| `model` | 是 | 模型名（状态栏右侧显示） |
| `base_url` | 否 | 自定义端点；`openai-compat` 常用（如 DeepSeek） |
| `thinking` | 否 | 仅 `anthropic` 生效，开启扩展思考 |

示例（已附在 `.cortex/config.yaml.example`）：

```yaml
providers:
  - name: claude
    protocol: anthropic
    api_key: sk-ant-xxxxxxxxxxxxxxxxxxxxxxxx
    model: claude-sonnet-4-20250514
    thinking: true
```

### 运行

在仓库根目录用 JDK 21 的 `java` 启动：

```bash
java -jar build/libs/cortex.jar
```

> 若你的默认 `java` 不是 21，可显式指定 JDK 21，例如：
> `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java -jar build/libs/cortex.jar`

启动后：

- 配置了多个 provider 时，先出现方向键列表供你选择，选定后进入对话。
- 在底部输入框键入消息，`Enter` 提交；`Alt+Enter` 插入换行进行多行编辑。
- 需要模型先出方案再动手时，用 `/plan` 进入计划模式（仅只读工具），审阅计划后用 `/do` 批准执行。
- 任务执行中按 `Esc`（或 `Ctrl+C`）可取消本轮，回空闲态、不退出程序。
- 输入 `/exit` 或空闲态按 `Ctrl+C` 安全退出（会正确恢复终端状态）。

### 常见问题

| 问题 | 处理 |
| --- | --- |
| `gradle.properties` 里硬编码的 JDK 路径在你机器上不存在，构建失败 | 将该文件中的 `org.gradle.java.home` 改成你机器上 JDK 21 的安装路径，或删除该行并确保 `JAVA_HOME` 指向 JDK 21 |
| 运行 jar 提示版本不支持（默认 `java` 是 17） | 按上文用 JDK 21 的 `java` 显式启动 |
| 启动报「配置错误」 | 检查 `.cortex/config.yaml` 是否存在、字段是否齐全（如 `api_key` 未填） |
| 网络/鉴权/限流等请求失败 | 错误会以可区分样式显示在对话区，程序不退出，可继续下一轮 |

## 项目结构

- `src/main/java/com/cortex/` — 源码入口与各模块
  - `agent/` — Agent Loop 编排（ReAct 多轮循环、分批并发、停止条件与取消）
  - `tool/` — 工具抽象、六个核心工具与注册中心
  - `llm/` — 多协议 LLM 适配与流式解析
  - `tui/` — 终端界面（基于 JLine）
  - `config/` — 配置加载与校验
- `docs/spec/` — 规格驱动的设计与验收文档（按 phase 组织）
- `.cortex/config.yaml.example` — 配置示例

## 相关文档

- `AGENTS.md` — 面向开发者的架构与约定说明
- `docs/spec/` — 各 phase 的 Spec / Plan / Tasks / Checklist
