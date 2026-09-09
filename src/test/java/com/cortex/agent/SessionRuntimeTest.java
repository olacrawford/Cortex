package com.cortex.agent;

import com.cortex.compact.Recovery;
import com.cortex.compact.state.AutoCompactTrackingState;
import com.cortex.compact.state.ContentReplacementState;
import com.cortex.compact.state.SessionContext;
import com.cortex.hook.HookEngine;
import com.cortex.hook.HookExecutor;
import com.cortex.hook.HookRule;
import com.cortex.hook.Payload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionRuntimeTest {

    @TempDir
    Path tmp;

    private static SessionRuntime newRuntime() {
        return new SessionRuntime(new ContentReplacementState(), new Recovery.RecoveryState(),
                new AutoCompactTrackingState(), SessionRuntime.empty(1).session, 200_000);
    }

    @Test
    void appendReminders与takeReminders_取走即清空() {
        SessionRuntime runtime = newRuntime();
        assertTrue(runtime.takeReminders().isEmpty());
        runtime.appendReminders(List.of("提示一", "提示二"));
        assertEquals(List.of("提示一", "提示二"), runtime.takeReminders());
        assertTrue(runtime.takeReminders().isEmpty(), "takeReminders 取走后清空（F21）");
        runtime.appendReminders(null);
        runtime.appendReminders(List.of());
        assertTrue(runtime.takeReminders().isEmpty());
    }

    @Test
    void resetForNewSession_清空pendingReminders与hookOnce状态() throws Exception {
        SessionRuntime runtime = newRuntime();
        runtime.appendReminders(List.of("未消费的提示"));

        // only_once hook：同一 session 内第二次 dispatch 跳过（观察点 = hook stderr 转发的标记次数）
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream oldErr = System.err;
        System.setErr(new java.io.PrintStream(buf, true));
        HookEngine engine;
        try {
            engine = new HookEngine(List.of(new HookRule("once", com.cortex.hook.Event.STOP,
                    null, new com.cortex.hook.Action.Shell("echo once-marker >&2"),
                    true, false, Duration.ofSeconds(5), "t")), List.of(), new HookExecutor());
            runtime.hookEngine = engine;

            com.cortex.hook.Event ev = com.cortex.hook.Event.STOP;
            engine.dispatch(ev, new Payload(java.util.Map.of()), null);
            engine.dispatch(ev, new Payload(java.util.Map.of()));
            assertEquals(1, countMarker(buf), "only_once 同会话只跑一次（F27）");

            runtime.resetForNewSession(SessionContext.create(tmp));
            assertTrue(runtime.takeReminders().isEmpty(), "reset 清空 pendingReminders（N5）");
            engine.dispatch(ev, new Payload(java.util.Map.of()));
            assertEquals(2, countMarker(buf), "resetForNewSession 后 only_once 重置（N5）");
        } finally {
            System.setErr(oldErr);
        }
    }

    private static int countMarker(java.io.ByteArrayOutputStream buf) {
        return (buf.toString().split("once-marker", -1).length - 1);
    }

    @Test
    void hookEngine可空时reset不抛() throws Exception {
        SessionRuntime runtime = newRuntime();
        runtime.appendReminders(List.of("x"));
        runtime.resetForNewSession(SessionContext.create(tmp));
        assertTrue(runtime.takeReminders().isEmpty());
    }

    @Test
    void resetForNewSession_原有语义保持() {
        SessionRuntime runtime = newRuntime();
        runtime.replacement.decideOnce("id", "orig",
                () -> new ContentReplacementState.DecisionResult(ContentReplacementState.Decision.KEPT, null));
        runtime.bumpTurnCount();
        runtime.resetForNewSession(runtime.session);
        assertEquals(0, runtime.getTurnCount(), "回合数清零");
    }
}
