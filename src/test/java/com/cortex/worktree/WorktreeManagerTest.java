package com.cortex.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** WorktreeManager 构造与恢复（T4/F5/F31/AC20）。 */
class WorktreeManagerTest {

    @TempDir
    Path tmp;

    @Test
    void 非git目录构造抛IOException() throws Exception {
        Path notRepo = Files.createTempDirectory(tmp, "plain");
        IOException e = assertThrows(IOException.class, () -> new WorktreeManager(notRepo));
        assertTrue(e.getMessage().contains("git"));
    }

    @Test
    void 构造创建worktrees目录_空session返回null() throws Exception {
        Path r = GitTestSupport.initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = new WorktreeManager(r);
        assertTrue(Files.isDirectory(r.resolve(".cortex/worktrees")));
        assertNull(mgr.currentSession());
        assertTrue(mgr.list().isEmpty());
    }

    @Test
    void 启动加载已有session文件() throws Exception {
        Path r = GitTestSupport.initRepo(tmp.resolve("repo"));
        WorktreeManager first = new WorktreeManager(r);
        first.create("alice", "HEAD", true);
        first.enter("alice");

        // 模拟进程重启
        WorktreeManager second = new WorktreeManager(r);
        assertNotNull(second.currentSession(), "重启后应恢复 session");
        assertEquals("alice", second.currentSession().worktreeName());
        assertEquals("alice", second.currentSession().worktreeName());
    }

    @Test
    void session指向目录消失时启动清空并警告() throws Exception {
        Path r = GitTestSupport.initRepo(tmp.resolve("repo"));
        // 伪造 session 指向不存在的目录
        WorktreeSession ghost = WorktreeSession.create("/nowhere", "/nowhere/ghost-wt", "ghost", "main", "x");
        SessionStore.save(r.resolve(".cortex/worktree_session.json"), ghost);

        WorktreeManager mgr = new WorktreeManager(r);
        assertNull(mgr.currentSession(), "AC20：目录消失应清空 session");
        assertTrue(SessionStore.load(r.resolve(".cortex/worktree_session.json")).isEmpty(),
                "session 文件应被覆写为 null");
    }

    @Test
    void session文件损坏时启动降级不清空目录() throws Exception {
        Path r = GitTestSupport.initRepo(tmp.resolve("repo"));
        Files.createDirectories(r.resolve(".cortex"));
        Files.writeString(r.resolve(".cortex/worktree_session.json"), "{broken");
        WorktreeManager mgr = new WorktreeManager(r);
        assertNull(mgr.currentSession(), "N5：坏 JSON 只警告并按空处理");
    }

    @Test
    void 启动扫描还原active映射_快速恢复路径() throws Exception {
        Path r = GitTestSupport.initRepo(tmp.resolve("repo"));
        WorktreeManager first = new WorktreeManager(r);
        first.create("alice", "HEAD", true);
        first.create("bob", "HEAD", false);
        assertTrue(first.get("alice").isPresent());

        // 模拟重启：新 Manager 纯文件系统扫描应发现两个 worktree
        WorktreeManager second = new WorktreeManager(r);
        assertEquals(2, second.list().size());
        assertTrue(second.get("alice").isPresent());
        assertTrue(second.get("bob").isPresent());
    }
}
