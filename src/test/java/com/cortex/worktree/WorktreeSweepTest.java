package com.cortex.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** sweepStale（T7/F33/G10/AC19）：三层过滤。 */
class WorktreeSweepTest {

    @TempDir
    Path tmp;

    @Test
    void 三层过滤_只清匹配且过期且无变更的目录() throws Exception {
        Path r = GitTestSupport.initRepo(tmp.resolve("repo"));
        // 配一个 bare remote 并 push：worktree HEAD 与远端一致才算「无未推送 commit」
        Path remote = Files.createDirectories(tmp.resolve("remote.git"));
        GitTestSupport.run(tmp, "init", "--bare", remote.toString());
        GitTestSupport.run(r, "remote", "add", "origin", remote.toString());
        GitTestSupport.run(r, "push", "-u", "origin", "HEAD");
        WorktreeManager mgr = new WorktreeManager(r);

        // ① 匹配模式、无变更 → 应被清理
        Worktree stale = mgr.create(WorktreeNaming.randomAgentName(), "HEAD", false);
        // ② 匹配模式、有变更 → 保留（fail-closed）
        Worktree dirty = mgr.create(WorktreeNaming.randomAgentName(), "HEAD", false);
        Files.writeString(dirty.path().resolve("wip.txt"), "半成品");
        // ③ 不匹配模式（手动命名）→ 保留
        Worktree manual = mgr.create("keep-me", "HEAD", false);
        // ④ 当前 session 的目录 → 保留
        Worktree entered = mgr.create(WorktreeNaming.randomAgentName(), "HEAD", false);
        mgr.enter(entered.name());

        // 统一调旧 mtime（清理按 cutoff 比较）
        Instant cutoff = Instant.now().plusSeconds(3600); // cutoff 在未来 → 全部目录都"过期"

        List<String> removed = mgr.sweepStale(cutoff);
        assertEquals(List.of(stale.name()), removed, "只应清理无变更的过期临时 Worktree");
        assertFalse(Files.exists(stale.path()));
        assertTrue(Files.exists(dirty.path()));
        assertTrue(Files.exists(manual.path()));
        assertTrue(Files.exists(entered.path()));
    }

    @Test
    void mtime晚于cutoff的目录跳过() throws Exception {
        Path r = GitTestSupport.initRepo(tmp.resolve("repo2"));
        Path remote2 = Files.createDirectories(tmp.resolve("remote2.git"));
        GitTestSupport.run(tmp, "init", "--bare", remote2.toString());
        GitTestSupport.run(r, "remote", "add", "origin", remote2.toString());
        GitTestSupport.run(r, "push", "-u", "origin", "HEAD");
        WorktreeManager mgr = new WorktreeManager(r);
        Worktree fresh = mgr.create(WorktreeNaming.randomAgentName(), "HEAD", false);
        // cutoff 在过去 → 目录 mtime 更新 → 跳过
        List<String> removed = mgr.sweepStale(Instant.now().minusSeconds(3600));
        assertTrue(removed.isEmpty());
        assertTrue(Files.exists(fresh.path()));
    }

    @Test
    void randomAgentName符合临时模式() {
        for (int i = 0; i < 20; i++) {
            String name = WorktreeNaming.randomAgentName();
            assertTrue(WorktreeNaming.EPHEMERAL_PATTERN.matcher(name).matches(), name);
            assertEquals("agent-a", name.substring(0, 7));
        }
        // 随机性抽查
        assertNotEquals(WorktreeNaming.randomAgentName(), WorktreeNaming.randomAgentName());
    }
}
