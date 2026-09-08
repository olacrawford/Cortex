# AGENTS.md

## What this is
Cortex — a Claude Code-style terminal AI coding assistant, written in Java. Phase-01 (multi-protocol LLM terminal chat client), phase-02 (tool system), phase-03 (Agent Loop: multi-round ReAct, batched concurrent execution, Plan Mode), phase-04 (system prompt engineering: modular assembly, env info, cache channels, system-reminder) and phase-05 (permission system: 5-layer defense, human-in-the-loop approval, 3-tier YAML rules) are implemented on `feature/phase05`. Next spec phases go under `docs/spec/`.

## Commands
- Build fat jar: `./gradlew shadowJar` → `build/libs/cortex.jar` (main class `com.cortex.Cortex`)
- Run: `java -jar build/libs/cortex.jar` (needs a JDK 21 `java`; see Gotchas)
- Test everything: `./gradlew test`
- One test class: `./gradlew test --tests '*RegistryTest'`
- Compile only: `./gradlew compileJava`

## Gotchas
- **JDK**: `gradle.properties` hardcodes `org.gradle.java.home` to a specific Temurin 21 path. The JDK toolchain is 21, but the machine's default `java` is 17 — use the Gradle wrapper (`./gradlew`), not a bare JDK; to run the jar use `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java`.
- **Config**: real keys live in `.cortex/config.yaml` (gitignored). Copy `.cortex/config.yaml.example` and fill in. Permission rules live in 3-tier settings files (user `~/.cortex/settings.yaml`, project `.cortex/settings.yaml`, local `.cortex/settings.local.yaml` — local is gitignored). Do not print `api_key` anywhere (spec N6/N7).
- **Protocol → client mapping** (`LlmClient.create`): `anthropic` → `AnthropicClient`; **`openai` AND `openai-compat` both → `OpenAiClient`**. `openai-compat` merely sets custom `base_url`. The separate `OpenAiCompatClient` in old plans does not exist — trust the code.
- **JLine keymap**: for printable input to reach the app, the keymap needs BOTH `setUnicode` (codepoints ≥ 256) AND `setNomatch` (Latin-1 chars) — an unbound ASCII char otherwise gets silently discarded by `BindingReader` (fixed in `Program.buildKeyMap`).
- **TUI rendering invariants** (`tui/tea/Program`): `clearView` must cursor-up `linesRendered - 1` (cursor sits at the end of the last view row); `CortexModel.onStreamTick` must return mid-turn collected `Command.println`s together with the next tick, otherwise committed tool lines get dropped.
- **Agent termination invariant**: `Agent.run`'s virtual thread ALWAYS enqueues `AgentEvent.Done` in a `finally` block (bypassing the cancel-checked `emit`) — the TUI relies on it to leave the streaming state on every path, including user cancel.
- **Cancelled rounds still feed history**: `Agent.executeBatched` fills unexecuted calls with a `（已取消。）` placeholder result and `conv.addToolResults` runs unconditionally, then `ensureAssistantTail` guarantees the history ends with an assistant text turn — otherwise the next request 400s on dangling tool_use / role alternation.
- **Permission Ask blocks the agent thread**: when the engine returns ASK, `Agent.requestApproval` blocks on `respond.take()` until the TUI (or a test) offers an `Outcome`. In tests without a UI, `drain()` auto-offers DENY_ONCE — otherwise the test hangs forever.

## Architecture (phase-05)
- `com.cortex.permission` — 5-layer defense. `PermissionEngine.check(mode, call, readOnly)` runs layers ① blacklist (regex, unconfigurable, EXEC tools only) → ② sandbox (project-root prefix check AFTER symlink resolution; file tools only) → ③ rule engine (local > project > user tiers, deny-first within a tier, friendly names Bash/Read/Write/Edit/Glob/Grep) → ④ mode fallback matrix (READ always Allow; DEFAULT/PLAN: WRITE/EXEC Ask; ACCEPT_EDITS: WRITE Allow; BYPASS: all Allow). ASK = layer ⑤ human-in-the-loop, orchestrated by agent.
- `com.cortex.agent` — ReAct loop (`Agent.run(conv, mode, cancel)`); on ASK emits `AgentEvent.Approval(ApprovalRequest{respond})` and blocks on `respond.take()`; DENY (any layer) becomes a structured error ToolResult fed back to the model without interrupting the loop. `permission.Mode` {DEFAULT, ACCEPT_EDITS, PLAN, BYPASS} replaced agent.Mode (plan keeps read-only toolset + reminder).
- `com.cortex.tui` — approval menu state (`pending`/`approveCursor`): ↑↓+Enter, digits 1/2/3, y/n; Esc/Ctrl+C during approval offers DENY_ONCE then cancels the turn; Shift+Tab cycles mode (idle only); status bar left shows the current permission mode (provider name no longer shown).
- Local allow persistence: human-loop "永久" writes an exact-match rule to `.cortex/settings.local.yaml` (gitignored) and syncs the in-memory ruleset.
- Thinking is disabled for requests whose history contains tool turns (Anthropic would 400 without the original signed thinking blocks).

## Conventions
- **Comments and user-facing strings are in Chinese** (docs/spec 各文档亦为中文).
- Java 21 style: records, `sealed interface`, switch pattern matching, virtual threads (`Thread.ofVirtual()`).
- Streaming: each request runs on a virtual thread pushing `StreamEvent` into a `BlockingQueue`; thinking deltas are received and discarded (never rendered into text).

## Workflow (important)
- **Spec-driven hard gate**: before coding any feature, follow `docs/spec/00-meta/mew-spec.md` — produce spec → plan → task → checklist under `docs/spec/`, each approved by the user. Do not write implementation code before all four are approved.
- **E2E verification**: run real end-to-end checks in `tmux` (launch Cortex, send a real request, match against the phase's `Checklist.md`).
