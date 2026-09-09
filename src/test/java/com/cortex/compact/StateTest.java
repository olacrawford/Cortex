package com.cortex.compact;

import com.cortex.compact.state.AutoCompactTrackingState;
import com.cortex.compact.state.ContentReplacementState;
import com.cortex.compact.state.ContentReplacementState.Decision;
import com.cortex.compact.state.ContentReplacementState.DecisionResult;
import com.cortex.compact.state.SessionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class StateTest {

    @TempDir
    Path tempDir;

    @Test
    void createSessionContext生成目录() throws IOException {
        SessionContext ctx = SessionContext.create(tempDir);
        assertTrue(ctx.sessionId().matches("\\d+-[0-9a-f]{8}"));
        assertTrue(Files.isDirectory(ctx.spillDir()));
    }

    @Test
    void decideOnce冻结保留() {
        ContentReplacementState s = new ContentReplacementState();
        String r1 = s.decideOnce("id1", "orig", () -> new DecisionResult(Decision.KEPT, null));
        assertEquals("orig", r1);
        // 再次 decideOnce：账本已 Seen → 返回原 content，不重跑回调
        String r2 = s.decideOnce("id1", "orig", () -> {
            throw new AssertionError("不应重跑决策回调");
        });
        assertEquals("orig", r2);
    }

    @Test
    void decideOnce冻结替换() {
        ContentReplacementState s = new ContentReplacementState();
        String r1 = s.decideOnce("id1", "orig", () -> new DecisionResult(Decision.REPLACED, "preview-1"));
        assertEquals("preview-1", r1);
        String r2 = s.decideOnce("id1", "orig", () -> {
            throw new AssertionError("不应重跑决策回调");
        });
        assertEquals("preview-1", r2);
    }

    @Test
    void decideOnce跳过不写账本() {
        ContentReplacementState s = new ContentReplacementState();
        String r1 = s.decideOnce("id1", "orig", () -> new DecisionResult(Decision.SKIP, null));
        assertEquals("orig", r1);
        assertFalse(s.seen("id1"));
        // 下轮可重新决策
        String r2 = s.decideOnce("id1", "orig", () -> new DecisionResult(Decision.REPLACED, "p"));
        assertEquals("p", r2);
        assertTrue(s.seen("id1"));
    }

    @Test
    void autoTracking熔断预算() {
        AutoCompactTrackingState a = new AutoCompactTrackingState();
        a.recordFailure();
        a.recordFailure();
        assertFalse(a.tripped());
        a.recordSuccess();
        a.recordFailure();
        a.recordFailure();
        assertFalse(a.tripped());
        a.recordFailure();
        assertTrue(a.tripped());
    }

    @Test
    void autoTracking并发无竞态() throws Exception {
        AutoCompactTrackingState a = new AutoCompactTrackingState();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                fs.add(ex.submit(() -> {
                    a.recordFailure();
                    a.tripped();
                    a.recordSuccess();
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        }
        assertDoesNotThrow(() -> a.tripped());
    }

    @Test
    void recoverySnapshot按时间倒序() {
        Recovery.RecoveryState r = new Recovery.RecoveryState();
        r.recordFile("/a", "a");
        r.recordFile("/b", "b");
        r.recordFile("/c", "c");
        List<Recovery.FileReadRecord> snap = r.snapshot();
        assertEquals(3, snap.size());
        // 时间戳倒序：最近写入的排最前
        assertTrue(snap.get(0).timestamp().compareTo(snap.get(1).timestamp()) >= 0);
        assertTrue(snap.get(1).timestamp().compareTo(snap.get(2).timestamp()) >= 0);
    }

    @Test
    void recoverySnapshot并发() throws Exception {
        Recovery.RecoveryState r = new Recovery.RecoveryState();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int k = i;
                fs.add(ex.submit(() -> {
                    r.recordFile("/f" + k, "c" + k);
                    r.snapshot();
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        }
        assertDoesNotThrow(r::snapshot);
    }

    @Test
    void recoveryRecordFile归一化绝对路径() {
        Recovery.RecoveryState r = new Recovery.RecoveryState();
        r.recordFile("rel.txt", "content");
        List<Recovery.FileReadRecord> snap = r.snapshot();
        assertTrue(snap.get(0).path().startsWith("/"));
    }
}
