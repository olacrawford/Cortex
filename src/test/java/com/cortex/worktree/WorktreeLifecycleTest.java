package com.cortex.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 生命周期（T6/F11-F14）：enter 不动 JVM cwd、exit 变更保护、remove、autoCleanup 三分支。 */
class WorktreeLifecycleTest {

    @TempDir
    Path tmp;

    private Path repo() throws Exception {
        return GitTestSupport.initRepo(tmp.resolve("repo-" + System.nanoTime()));
    }

    @Test
    void enter不改JVM当前目录_返回完整session并持久化() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        mgr.create("alice", "HEAD", true);

        String cwdBefore = Path.of("").toAbsolutePath().toString();
        WorktreeSession session = mgr.enter("alice");
        assertEquals(cwdBefore, Path.of("").toAbsolutePath().toString(), "AC9：进程当前目录不得改变");
        assertEquals(cwdBefore, session.originalCwd());
        assertEquals("alice", session.worktreeName());
        assertTrue(session.worktreePath().endsWith("worktrees" + System.getProperty("file.separator") + "alice")
                || session.worktreePath().endsWith("worktrees/alice"));
        assertFalse(session.sessionId().isEmpty());
        // 持久化到 session 文件
        assertTrue(Files.exists(r.resolve(".cortex/worktree_session.json")));
        WorktreeSession loaded = SessionStore.load(r.resolve(".cortex/worktree_session.json")).orElseThrow();
        assertEquals(session.sessionId(), loaded.sessionId());
    }

    @Test
    void exitKeep清空session_目录保留() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        mgr.create("alice", "HEAD", true);
        mgr.enter("alice");
        ExitReport report = mgr.exit("alice", ExitAction.KEEP, ExitOptions.normal());
        assertFalse(report.removed());
        assertTrue(Files.isDirectory(r.resolve(".cortex/worktrees/alice")), "KEEP 应保留目录");
        assertTrue(SessionStore.load(r.resolve(".cortex/worktree_session.json")).isEmpty());
        assertNull(mgr.currentSession());
    }

    @Test
    void exitRemove有变更抛异常_目录仍在() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        mgr.create("alice", "HEAD", true);
        mgr.enter("alice");
        Files.writeString(r.resolve(".cortex/worktrees/alice/changed.txt"), "x");
        WorktreeHasChangesException e = assertThrows(WorktreeHasChangesException.class,
                () -> mgr.exit("alice", ExitAction.REMOVE, ExitOptions.normal()), "AC10");
        assertTrue(Files.isDirectory(r.resolve(".cortex/worktrees/alice")), "拒绝删除后目录仍在");
    }

    @Test
    void exitRemove显式discard删除目录与分支() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        mgr.create("alice", "HEAD", true);
        mgr.enter("alice");
        Files.writeString(r.resolve(".cortex/worktrees/alice/changed.txt"), "x");
        ExitReport report = mgr.exit("alice", ExitAction.REMOVE, ExitOptions.discard());
        assertTrue(report.removed());
        assertFalse(Files.exists(r.resolve(".cortex/worktrees/alice")), "AC11");
        assertTrue(SessionStore.load(r.resolve(".cortex/worktree_session.json")).isEmpty());
        assertTrue(mgr.get("alice").isEmpty());
    }

    @Test
    void exit非当前会话的worktree报错() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        mgr.create("alice", "HEAD", true);
        assertThrows(IOException.class, () -> mgr.exit("alice", ExitAction.KEEP, ExitOptions.normal()),
                "未 enter 过就 exit 应抛");
    }

    @Test
    void remove允许非当前会话且有变更保护() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        mgr.create("alice", "HEAD", true);
        // 未 enter——直接 remove
        mgr.remove("alice", ExitOptions.normal());
        assertFalse(Files.exists(r.resolve(".cortex/worktrees/alice")));
        assertTrue(mgr.get("alice").isEmpty());

        // 有变更未 discard → 拒绝
        Worktree wt = mgr.create("bob", "HEAD", true);
        Files.writeString(wt.path().resolve("x.txt"), "x");
        assertThrows(WorktreeHasChangesException.class, () -> mgr.remove("bob", ExitOptions.normal()));
        assertTrue(Files.exists(wt.path()));
        mgr.remove("bob", ExitOptions.discard());
        assertFalse(Files.exists(wt.path()));
    }

    @Test
    void autoCleanup三分支() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        // ① manual=true：直接保留
        mgr.create("manual", "HEAD", true);
        AutoCleanupReport r1 = mgr.autoCleanup("manual");
        assertTrue(r1.kept());
        assertTrue(Files.exists(r.resolve(".cortex/worktrees/manual")));

        // ② 临时 + 无变更：直接删除
        mgr.create(WorktreeNaming.randomAgentName(), "HEAD", false);
        String ephemeral = mgr.list().stream().map(Worktree::name)
                .filter(n -> n.startsWith("agent-a")).findFirst().orElseThrow();
        AutoCleanupReport r2 = mgr.autoCleanup(ephemeral);
        assertFalse(r2.kept());

        // ③ 临时 + 有变更：保留并回报 path/branch
        Worktree dirty = mgr.create(WorktreeNaming.randomAgentName(), "HEAD", false);
        Files.writeString(dirty.path().resolve("new.txt"), "改动");
        AutoCleanupReport r3 = mgr.autoCleanup(dirty.name());
        assertTrue(r3.kept());
        assertEquals(dirty.branch(), r3.branch());
        assertTrue(Files.exists(dirty.path()));
    }
}
