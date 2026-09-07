# AGENTS.md

## What this is
MewCode — a Claude Code-style terminal AI coding assistant, written in Java. Currently mid-implementation of `docs/spec/phase-01` (multi-protocol LLM terminal chat client). Core layers (config, prompt, llm, conversation) + unit tests exist; the entrypoint and TUI are **not built yet**.

## Commands
- Build fat jar: `./gradlew shadowJar` → `build/libs/mewcode.jar` (main class `com.mewcode.MewCode`)
- Run: `java -jar build/libs/mewcode.jar`
- Test everything: `./gradlew test`
- One test class: `./gradlew test --tests '*ConfigLoaderTest'`
- Compile only: `./gradlew compileJava`

## Gotchas
- **JDK**: `gradle.properties` hardcodes `org.gradle.java.home` to a specific Temurin 21 path. The JDK toolchain is 21, but the machine's default `java` is 17 — use the Gradle wrapper (`./gradlew`), not a bare JDK.
- **Config**: real keys live in `.mewcode/config.yaml` (gitignored). Copy `.mewcode/config.yaml.example` and fill in. Do not print `api_key` anywhere (spec N5).
- **Protocol → client mapping** (`LlmClient.create`): `anthropic` → `AnthropicClient`; **`openai` AND `openai-compat` both → `OpenAiClient`**. `openai-compat` merely sets custom `base_url`. The separate `OpenAiCompatClient` in `Plan.md` does not exist — trust the code.
- **Incomplete entrypoint**: `src/main/java/com/mewcode/MewCode.java` is still the T1 placeholder (prints version); it does not load config or start the TUI. The `com.mewcode.tui` / `tui.tea` packages referenced in `Plan.md`/`Tasks.md` do not exist yet. Next steps are Tasks T9–T11.

## Conventions
- **Comments and user-facing strings are in Chinese** (see `codex.md`).
- Java 21 style: records, `sealed interface`, switch pattern matching, virtual threads (`Thread.ofVirtual()`).
- Streaming: each request runs on a virtual thread pushing `StreamEvent` into a `BlockingQueue`; thinking deltas are received and discarded (never rendered into text).
- Multi-provider selection, scrollback, spinner timing: only defined in the spec (`docs/spec/phase-01/Spec.md` F1–F12, Checklist.md) — implement against those.

## Workflow (important)
- **Spec-driven hard gate**: before coding any feature, follow `docs/spec/00-meta/mew-spec.md` — produce spec → plan → task → checklist under `docs/spec/`, each approved by the user. Do not write implementation code before all four are approved.
- **E2E verification**: per `codex.md`, run real end-to-end checks in `tmux` (launch MewCode, send a real request, match against `Checklist.md`).
