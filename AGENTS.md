# AGENTS.md

## What this is
Cortex — a Claude Code-style terminal AI coding assistant, written in Java. Phase-01 (multi-protocol LLM terminal chat client) and phase-02 (tool system: 6 core tools + registry + single-round agent loop + TUI tool lines) are implemented on `feature/phase02`. Next spec phases go under `docs/spec/`.

## Commands
- Build fat jar: `./gradlew shadowJar` → `build/libs/cortex.jar` (main class `com.cortex.Cortex`)
- Run: `java -jar build/libs/cortex.jar` (needs a JDK 21 `java`; see Gotchas)
- Test everything: `./gradlew test`
- One test class: `./gradlew test --tests '*RegistryTest'`
- Compile only: `./gradlew compileJava`

## Gotchas
- **JDK**: `gradle.properties` hardcodes `org.gradle.java.home` to a specific Temurin 21 path. The JDK toolchain is 21, but the machine's default `java` is 17 — use the Gradle wrapper (`./gradlew`), not a bare JDK; to run the jar use `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java`.
- **Config**: real keys live in `.cortex/config.yaml` (gitignored). Copy `.cortex/config.yaml.example` and fill in. Do not print `api_key` anywhere (spec N6).
- **Protocol → client mapping** (`LlmClient.create`): `anthropic` → `AnthropicClient`; **`openai` AND `openai-compat` both → `OpenAiClient`**. `openai-compat` merely sets custom `base_url`. The separate `OpenAiCompatClient` in `Plan.md` does not exist — trust the code.
- **JLine keymap**: for printable input to reach the app, the keymap needs BOTH `setUnicode` (codepoints ≥ 256) AND `setNomatch` (Latin-1 chars) — an unbound ASCII char otherwise gets silently discarded by `BindingReader` (phase-01 latent bug, fixed in `Program.buildKeyMap`).
- **TUI rendering invariants** (`tui/tea/Program`): `clearView` must cursor-up `linesRendered - 1` (cursor sits at the end of the last view row); `CortexModel.onStreamTick` must return mid-turn collected `Command.println`s together with the next tick, otherwise committed tool lines get dropped.

## Architecture (phase-02)
- `com.cortex.tool` — `Tool`/`Result`/`ToolRegistry` (`createDefault()`: read_file, write_file, edit_file, bash, glob, grep) + `Truncate`. Failures are `Result.error`, never exceptions.
- `com.cortex.agent` — `Agent.run(conv)` single-round loop (request#1 → execute tools → feed results back → request#2 → final answer → `Done`; a second tool request in round #2 is ignored) emitting `AgentEvent` (Text/Tool/Done/Failed) into a `BlockingQueue` polled by the TUI.
- `com.cortex.llm` — `ToolDef`/`ToolCall`/`ToolResult` records; both adapters inject tool definitions, assemble streamed tool calls, and map tool turns back per protocol (Anthropic: tool_use / tool_result-in-user-message; OpenAI: assistant.tool_calls / role=tool).
- Thinking is disabled for continuation requests whose history contains tool turns (Anthropic would 400 without the original signed thinking blocks).

## Conventions
- **Comments and user-facing strings are in Chinese** (see `codex.md`).
- Java 21 style: records, `sealed interface`, switch pattern matching, virtual threads (`Thread.ofVirtual()`).
- Streaming: each request runs on a virtual thread pushing `StreamEvent` into a `BlockingQueue`; thinking deltas are received and discarded (never rendered into text).

## Workflow (important)
- **Spec-driven hard gate**: before coding any feature, follow `docs/spec/00-meta/mew-spec.md` — produce spec → plan → task → checklist under `docs/spec/`, each approved by the user. Do not write implementation code before all four are approved.
- **E2E verification**: per `codex.md`, run real end-to-end checks in `tmux` (launch Cortex, send a real request, match against `Checklist.md`).
