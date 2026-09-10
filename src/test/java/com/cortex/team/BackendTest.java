package com.cortex.team;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** BackendDetector + Pane 后端命令构造（T11-T13/AC6）。 */
class BackendTest {

    // ── detect（注入 Env 控制环境变量）──

    @Test
    void detect优先级_Env注入() {
        // $TMUX → TMUX（最高优先级）
        assertEquals(BackendType.TMUX, BackendDetector.detect(k -> "session"));
        // Env 全 null（含 PATH）→ 找不到任何二进制 → IN_PROCESS（确定性，与宿主机无关）
        assertEquals(BackendType.IN_PROCESS, BackendDetector.detect(k -> null));
        // iTerm 场景：TERM_PROGRAM=iTerm.app 但 PATH 为空 → 找不到 it2 → 回落（无 tmux → IN_PROCESS）
        assertEquals(BackendType.IN_PROCESS, BackendDetector.detect(
                k -> k.equals("TERM_PROGRAM") ? "iTerm.app" : null));
    }

    // ── tmux 命令构造 ──

    @Test
    void tmuxMemberCommand构造() {
        TmuxBackend backend = new TmuxBackend("cortex.jar");
        var cmd = backend.memberCommand(new Backend.SpawnRequest("demo", "alice", "agent-x",
                "/wt", "/session", "worker", "opus", "任务", true, null, null, null));
        // F15：--team-member 形态，--agent-id 必传，--plan-mode 条件携带
        assertEquals(List.of("java", "-jar", "cortex.jar", "--team-member",
                "--team", "demo", "--member", "alice", "--agent-id", "agent-x",
                "--session-dir", "/session", "--worktree", "/wt",
                "--agent-type", "worker", "--model", "opus", "--plan-mode"), cmd);
        // 缺省成员：agent-type/model/plan-mode 不携带
        var minimal = backend.memberCommand(new Backend.SpawnRequest("demo", "bob", "agent-y",
                "/wt", "/session", "", "", "", false, null, null, null));
        assertFalse(minimal.contains("--agent-type"));
        assertFalse(minimal.contains("--model"));
        assertFalse(minimal.contains("--plan-mode"));
    }

    @Test
    void tmuxWakeKill命令() throws Exception {
        List<List<String>> captured = new java.util.ArrayList<>();
        TmuxBackend backend = new TmuxBackend("cortex.jar", args -> {
            captured.add(args);
            return "";
        });
        backend.wake("%5", "agent-x");
        assertEquals(List.of("tmux", "send-keys", "-t", "%5", "", "Enter"), captured.get(0));
        backend.kill("%5", "agent-x");
        assertEquals(List.of("tmux", "kill-pane", "-t", "%5"), captured.get(1));
    }

    @Test
    void iterm2命令构造() throws Exception {
        List<List<String>> captured = new java.util.ArrayList<>();
        Iterm2Backend backend = new Iterm2Backend("cortex.jar", args -> {
            captured.add(args);
            return "pane-1";
        });
        Backend.SpawnResult r = backend.spawn(new Backend.SpawnRequest("demo", "alice", "agent-x",
                "/wt", "/session", "worker", "", "任务", false, null, null, null));
        assertEquals("pane-1", r.paneId());
        assertEquals("it2", captured.get(0).get(0));
        assertTrue(captured.get(0).contains("split-pane"));
        backend.wake("pane-1", "agent-x");
        assertTrue(captured.get(1).containsAll(List.of("send-text", "--pane", "pane-1")));
    }

    @Test
    void inProcess后端_类型与依赖() {
        var backend = new InProcessBackend(null);
        assertEquals(BackendType.IN_PROCESS, backend.type());
        assertDoesNotThrow(() -> backend.wake("", "agent-x")); // no-op
    }

    @Test
    void wireValue往返() {
        for (BackendType t : BackendType.values()) {
            assertEquals(t, BackendType.fromWire(t.wireValue()));
        }
    }
}
