# AGENTS.md

## What this is
Cortex — a Claude Code-style terminal AI coding assistant, written in Java. Phase-01 (multi-protocol LLM terminal chat client), phase-02 (tool system) and phase-03 (Agent Loop: multi-round ReAct, batched concurrent execution, Plan Mode) are implemented on `feature/phase03`. Next spec phases go under `docs/spec/`.

## Commands
- Build fat jar: `./gradlew shadowJar` → `build/libs/cortex.jar` (main class `com.cortex.Cortex`)
- Run: `java -jar build/libs/cortex.jar` (needs a JDK 21 `java`; see Gotchas)
- Test everything: `./gradlew test`
- One test class: `./gradlew test --tests '*RegistryTest'`
- Compile only: `./gradlew compileJava`

## Gotchas
- **JDK**: `gradle.properties` hardcodes `org.gradle.java.home` to a specific Temurin 21 path. The JDK toolchain is 21, but the machine's default `java` is 17 — use the Gradle wrapper (`./gradlew`), not a bare JDK; to run the jar use `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java`.
- **Config**: real keys live in `.cortex/config.yaml` (gitignored). Copy `.cortex/config.yaml.example` and fill in. Do not print `api_key` anywhere (spec N6/N7).
- **Protocol → client mapping** (`LlmClient.create`): `anthropic` → `AnthropicClient`; **`openai` AND `openai-compat` both → `OpenAiClient`**. `openai-compat` merely sets custom `base_url`. The separate `OpenAiCompatClient` in old plans does not exist — trust the code.
- **JLine keymap**: for printable input to reach the app, the keymap needs BOTH `setUnicode` (codepoints ≥ 256) AND `setNomatch` (Latin-1 chars) — an unbound ASCII char otherwise gets silently discarded by `BindingReader` (fixed in `Program.buildKeyMap`).
- **TUI rendering invariants** (`tui/tea/Program`): `clearView` must cursor-up `linesRendered - 1` (cursor sits at the end of the last view row); `CortexModel.onStreamTick` must return mid-turn collected `Command.println`s together with the next tick, otherwise committed tool lines get dropped.
- **Agent termination invariant**: `Agent.run`'s virtual thread ALWAYS enqueues `AgentEvent.Done` in a `finally` block (bypassing the cancel-checked `emit`) — the TUI relies on it to leave the streaming state on every path, including user cancel.
- **Cancelled rounds still feed history**: `Agent.executeBatched` fills unexecuted calls with a `（已取消。）` placeholder result and `conv.addToolResults` runs unconditionally, then `ensureAssistantTail` guarantees the history ends with an assistant text turn — otherwise the next request 400s on dangling tool_use / role alternation.

## Architecture (phase-03)
- `com.cortex.agent` — ReAct loop (`Agent.run(conv, mode, cancel)`): stream-collect per round (text streamed out AND complete tool calls accumulated), stop on natural completion / `MAX_ITERATIONS=25` / user cancel (`CancelToken`, Esc / Ctrl+C while streaming) / 3 consecutive all-unknown-tool rounds / stream error. `executeBatched` runs consecutive read-only calls concurrently (virtual threads, per-index results) and side-effect calls serially, Start/End events in call order.
- `com.cortex.tool` — `Tool`/`Result`/`ToolRegistry` (`createDefault()`: read_file, write_file, edit_file, bash, glob, grep) + `Truncate`. `readOnly()` classifies tools (read/glob/grep true) for concurrency batching and Plan Mode (`readOnlyDefinitions()`). Failures are `Result.error`, never exceptions.
- `com.cortex.llm` — `ToolDef`/`ToolCall`/`ToolResult`/`Usage` records; `stream(conv, tools, systemSuffix)`; both adapters inject tool definitions, assemble streamed tool calls, map tool turns back per protocol, and emit one `UsageEvent` per stream (OpenAI needs `streamOptions.includeUsage(true)`).
- `com.cortex.prompt` — `PLAN_MODE_REMINDER` (plan-mode system suffix) + `EXECUTE_DIRECTIVE` (`/do` user message). Plan Mode physically restricts the tool set to read-only.
- Thinking is disabled for requests whose history contains tool turns (Anthropic would 400 without the original signed thinking blocks).

## Conventions
- **Comments and user-facing strings are in Chinese** (docs/spec 各文档亦为中文).
- Java 21 style: records, `sealed interface`, switch pattern matching, virtual threads (`Thread.ofVirtual()`).
- Streaming: each request runs on a virtual thread pushing `StreamEvent` into a `BlockingQueue`; thinking deltas are received and discarded (never rendered into text).

## Workflow (important)
- **Spec-driven hard gate**: before coding any feature, follow `docs/spec/00-meta/mew-spec.md` — produce spec → plan → task → checklist under `docs/spec/`, each approved by the user. Do not write implementation code before all four are approved.
- **E2E verification**: run real end-to-end checks in `tmux` (launch Cortex, send a real request, match against the phase's `Checklist.md`).
