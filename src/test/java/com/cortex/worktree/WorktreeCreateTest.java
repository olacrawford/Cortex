package com.cortex.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** WorktreeManager.create（T5/F6/G3/G4/AC2-AC8）：落地、嵌套 slug、快速恢复、创建后设置。 */
class WorktreeCreateTest {

    @TempDir
    Path tmp;

    private Path repo() throws Exception {
        return GitTestSupport.initRepo(tmp.resolve("repo-" + System.nanoTime()));
    }

    @Test
    void create落地目录与分支() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        Worktree wt = mgr.create("alice", "HEAD", true);
        assertTrue(Files.isDirectory(r.resolve(".cortex/worktrees/alice")));
        assertEquals("worktree-alice", wt.branch());
        assertEquals("worktree-alice", GitHelper.runGit(wt.path(), "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals(40, wt.headCommit().length());
        assertTrue(mgr.get("alice").isPresent());
    }

    @Test
    void 嵌套slug落地为flat目录与分支() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        Worktree wt = mgr.create("team/alice", "HEAD", true);
        assertTrue(Files.isDirectory(r.resolve(".cortex/worktrees/team+alice")));
        assertEquals("worktree-team+alice", wt.branch());
    }

    @Test
    void 重复create报错_非法slug报错() throws Exception {
        Path r = repo();
        WorktreeManager mgr = new WorktreeManager(r);
        mgr.create("alice", "HEAD", true);
        assertThrows(IOException.class, () -> mgr.create("alice", "HEAD", true), "同名已活跃应抛");
        assertThrows(IllegalArgumentException.class, () -> mgr.create("../etc", "HEAD", true));
    }

    @Test
    void 快速恢复_构造器扫描即还原_不调git() throws Exception {
        Path r = repo();
        Path preExisting = r.resolve(".cortex/worktrees/manual-wt");
        Files.createDirectories(preExisting);
        // 构造器扫描把已存在目录还原进 active（纯文件系统读，无 git worktree add）
        WorktreeManager mgr = new WorktreeManager(r);
        assertTrue(mgr.get("manual-wt").isPresent(), "AC4：构造后 active 立即就绪");
        assertEquals("worktree-manual-wt", mgr.get("manual-wt").orElseThrow().branch());
        // 分支并未真的创建（没有调过 git）—— rev-parse 应失败
        assertThrows(java.io.IOException.class,
                () -> GitHelper.runGit(r, "rev-parse", "--verify", "worktree-manual-wt"));
        // 对已活跃名字再 create 仍拒绝（F6-2）
        assertThrows(java.io.IOException.class, () -> mgr.create("manual-wt", "HEAD", true));
    }

    @Test
    void 创建后设置A_复制本地配置() throws Exception {
        Path r = repo();
        Files.createDirectories(r.resolve(".cortex"));
        Files.writeString(r.resolve(".cortex/settings.local.yaml"), "allow: []\n");
        WorktreeManager mgr = new WorktreeManager(r);
        Worktree wt = mgr.create("alice", "HEAD", true);
        assertTrue(Files.exists(wt.path().resolve(".cortex/settings.local.yaml")),
                "AC5：settings.local.yaml 应被复制");
    }

    @Test
    void 创建后设置C_大目录软链() throws Exception {
        Path r = repo();
        Files.createDirectories(r.resolve("node_modules").resolve("pkg"));
        WorktreeManager mgr = new WorktreeManager(r);
        Worktree wt = mgr.create("alice", "HEAD", true);
        Path link = wt.path().resolve("node_modules");
        assertTrue(Files.isSymbolicLink(link), "AC7：node_modules 应为软链");
    }

    @Test
    void 创建后设置D_worktreeinclude复制被忽略文件() throws Exception {
        Path r = repo();
        Files.writeString(r.resolve(".worktreeinclude"), "*.env\n");
        // .gitignore 是 ls-files --others --ignored 的前提：让 .env 进入 ignored 集合
        Files.writeString(r.resolve(".gitignore"), ".env\n");
        Files.writeString(r.resolve(".env"), "SECRET=1\n");
        GitTestSupport.run(r, "add", ".worktreeinclude", ".gitignore");
        GitTestSupport.run(r, "commit", "-m", "include");
        // .env 保持被忽略（未 add）
        WorktreeManager mgr = new WorktreeManager(r);
        Worktree wt = mgr.create("alice", "HEAD", true);
        assertEquals("SECRET=1\n", Files.readString(wt.path().resolve(".env")),
                "AC8：被忽略但 include 的 .env 应被复制");
    }

    @Test
    void 创建后设置B_husky配置hooksPath() throws Exception {
        Path r = repo();
        Files.createDirectories(r.resolve(".husky"));
        WorktreeManager mgr = new WorktreeManager(r);
        Worktree wt = mgr.create("alice", "HEAD", true);
        String config = GitHelper.runGit(wt.path(), "config", "--get", "core.hooksPath");
        assertTrue(config.contains(".husky"), "AC6：core.hooksPath 应指向主仓 .husky");
    }
}
