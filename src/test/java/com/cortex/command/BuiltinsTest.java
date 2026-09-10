package com.cortex.command;

import com.cortex.permission.Mode;
import com.cortex.prompt.Reminder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinsTest {

    /** 可观测桩：委托 NopUi 并记录调用。 */
    private static class RecordingUi implements Ui {
        @Override public com.cortex.command.WorktreeAccessor worktreeAccessor() { return null; }
        final List<String> prints = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        Mode mode = Mode.PLAN;
        final List<String> injected = new ArrayList<>();
        boolean cleared;
        boolean quit;
        List<String> hookLineList = List.of();

        @Override
        public void println(String msg) {
            prints.add(msg);
        }

        @Override
        public void error(String msg) {
            errors.add(msg);
        }

        @Override
        public Mode mode() {
            return mode;
        }

        @Override
        public void setMode(Mode m) {
            this.mode = m;
        }

        @Override
        public void injectAndSend(String displayLabel, String presetPrompt) {
            injected.add(displayLabel + "\u0000" + presetPrompt);
        }

        @Override
        public long usageIn() {
            return 12;
        }

        @Override
        public long usageOut() {
            return 34;
        }

        @Override
        public String modelName() {
            return "test-model";
        }

        @Override
        public String cwd() {
            return "/tmp/ws";
        }

        @Override
        public int toolCount() {
            return 9;
        }

        @Override
        public List<String> memoryFiles() {
            return List.of("MEMORY.md", "project_knowledge_a.md");
        }

        @Override
        public String sessionPath() {
            return "/tmp/ws/.cortex/sessions/x/conversation.jsonl";
        }

        @Override
        public String sessionId() {
            return "20260909-120000-abcd";
        }

        @Override
        public void quit() {
            quit = true;
        }

        @Override
        public void forceCompact() {
            prints.add("COMPACT");
        }

        @Override
        public void openResumeMenu() {
            prints.add("RESUME");
        }

        @Override
        public void clearAndNewSession() {
            cleared = true;
        }

        @Override
        public List<String> skillNames() {
            return List.of();
        }

        @Override
        public List<String> hookLines() {
            return hookLineList;
        }

        @Override
        public List<String> hookSources() {
            return List.of();
        }

        @Override
        public boolean idle() {
            return true;
        }
    }

    private static CommandRegistry newRegistry() {
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg);
        return reg;
    }

    private static final List<String> ALL_15 = List.of(
            "clear", "compact", "do", "exit", "help", "hooks", "memory",
            "permission", "plan", "resume", "review", "session", "skills", "status", "worktree");

    @Test
    void registerAll_allRegistered_恰好15条全小写() {
        CommandRegistry reg = newRegistry();
        assertEquals(15, reg.visible().size());
        List<String> names = reg.visible().stream().map(Command::name).toList();
        assertEquals(ALL_15, names);
        for (String n : names) {
            assertEquals(n, n.toLowerCase(), "命令名必须全小写");
        }
    }

    @Test
    void registerAll_noCollision_重复注册立即抛冲突() {
        CommandRegistry reg = newRegistry();
        // 已注册注册中心再注册任意同名命令 → 启动期立即失败（F2/N4）
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reg.register(new Command("help", List.of(), "重复", Kind.LOCAL, false, (ui, args) -> {})));
        assertTrue(ex.getMessage().contains("help"));
    }

    @Test
    void registerAll_handlersRunOnNopUi_全部不抛异常() throws Exception {
        CommandRegistry reg = newRegistry();
        for (Command c : reg.visible()) {
            assertDoesNotThrow(() -> c.handler().handle(Ui.NopUi.INSTANCE, ""),
                    "handler 在 NopUi 上应可安全执行: " + c.name());
        }
    }

    @Test
    void handleHooks_空时NoHooksLoaded_有规则列出() throws Exception {
        CommandRegistry reg = newRegistry();
        RecordingUi empty = new RecordingUi();
        reg.lookup("hooks").orElseThrow().handler().handle(empty, "");
        assertEquals("No hooks loaded.", empty.prints.get(0));

        RecordingUi ui = new RecordingUi();
        ui.hookLineList = List.of("PreToolUse:", "  block-write  shell [once]");
        reg.lookup("hooks").orElseThrow().handler().handle(ui, "");
        String out = ui.prints.get(ui.prints.size() - 1);
        assertTrue(out.contains("PreToolUse:"));
        assertTrue(out.contains("block-write  shell [once]"));
        assertTrue(out.contains("Loaded from:"));
    }

    @Test
    void handleSkills_空清单给引导_有清单逐行列出() throws Exception {
        CommandRegistry reg = newRegistry();
        RecordingUi empty = new RecordingUi();
        reg.lookup("skills").orElseThrow().handler().handle(empty, "");
        assertTrue(empty.prints.get(0).contains("无已安装技能"));

        RecordingUi ui = new RecordingUi() {
            @Override
            public List<String> skillNames() {
                return List.of("demo", "commit-helper");
            }
        };
        reg.lookup("skills").orElseThrow().handler().handle(ui, "");
        String out = ui.prints.get(ui.prints.size() - 1);
        assertTrue(out.contains("demo"));
        assertTrue(out.contains("commit-helper"));
    }

    @Test
    void handleStatus_printsAllKeys_固定顺序六行() throws Exception {
        CommandRegistry reg = newRegistry();
        RecordingUi ui = new RecordingUi();
        reg.lookup("status").orElseThrow().handler().handle(ui, "");
        assertEquals(1, ui.prints.size());
        String[] lines = ui.prints.get(0).split("\n");
        assertEquals(6, lines.length);
        assertTrue(lines[0].startsWith("Mode:"));
        assertTrue(lines[1].startsWith("Tokens:"));
        assertTrue(lines[2].startsWith("Tools:"));
        assertTrue(lines[3].startsWith("Memories:"));
        assertTrue(lines[4].startsWith("Model:"));
        assertTrue(lines[5].startsWith("Directory:"));
        assertTrue(ui.prints.get(0).contains("12 in / 34 out"));
        assertTrue(ui.prints.get(0).contains("2 files"));
    }

    @Test
    void handleHelp_printsAllFourteen_两列对齐() throws Exception {
        CommandRegistry reg = newRegistry();
        RecordingUi ui = new RecordingUi();
        reg.lookup("help").orElseThrow().handler().handle(ui, "");
        assertEquals(1, ui.prints.size());
        for (String n : ALL_15) {
            assertTrue(ui.prints.get(0).contains("/" + n), "缺命令: " + n);
        }
    }

    @Test
    void handleDo_setsModeAndInjects() throws Exception {
        CommandRegistry reg = newRegistry();
        RecordingUi ui = new RecordingUi();
        reg.lookup("do").orElseThrow().handler().handle(ui, "");
        assertEquals(Mode.DEFAULT, ui.mode);
        assertEquals(1, ui.injected.size());
        String[] parts = ui.injected.get(0).split("\u0000");
        assertEquals(Reminder.EXECUTE_DIRECTIVE, parts[1]);
    }

    @Test
    void handleReview_injects审查请求() throws Exception {
        CommandRegistry reg = newRegistry();
        RecordingUi ui = new RecordingUi();
        reg.lookup("review").orElseThrow().handler().handle(ui, "");
        assertEquals(1, ui.injected.size());
        assertTrue(ui.injected.get(0).contains("审查"));
    }

    @Test
    void handleUiCommands_委托正确动作() throws Exception {
        CommandRegistry reg = newRegistry();
        RecordingUi ui = new RecordingUi();
        reg.lookup("exit").orElseThrow().handler().handle(ui, "");
        assertTrue(ui.quit);
        reg.lookup("plan").orElseThrow().handler().handle(ui, "");
        assertEquals(Mode.PLAN, ui.mode);
        reg.lookup("compact").orElseThrow().handler().handle(ui, "");
        assertTrue(ui.prints.contains("COMPACT"));
        reg.lookup("resume").orElseThrow().handler().handle(ui, "");
        assertTrue(ui.prints.contains("RESUME"));
        reg.lookup("clear").orElseThrow().handler().handle(ui, "");
        assertTrue(ui.cleared);
    }

    @Test
    void handleMemoryAndSession_输出文件名与会话信息() throws Exception {
        CommandRegistry reg = newRegistry();
        RecordingUi ui = new RecordingUi();
        reg.lookup("memory").orElseThrow().handler().handle(ui, "");
        assertTrue(ui.prints.get(ui.prints.size() - 1).contains("MEMORY.md"));
        reg.lookup("session").orElseThrow().handler().handle(ui, "");
        String out = ui.prints.get(ui.prints.size() - 1);
        assertTrue(out.contains("Session: 20260909-120000-abcd"));
        assertTrue(out.contains("Path: /tmp/ws/.cortex/sessions/x/conversation.jsonl"));
        reg.lookup("permission").orElseThrow().handler().handle(ui, "");
        assertEquals("plan", ui.prints.get(ui.prints.size() - 1));
    }
}
