package com.cortex.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** PostCreationSetup 子步骤（T5/F7-F10/AC6-AC8）：B hooks、C 软链、D include 复制。 */
class PostCreationSetupTest {

    @TempDir
    Path tmp;

    @Test
    void setupB_主仓配置hooksPath时worktree继承() throws Exception {
        Path repo = GitTestSupport.initRepo(tmp.resolve("repo"));
        Path hooks = repo.resolve(".githooks");
        Files.createDirectories(hooks);
        GitTestSupport.run(repo, "config", "core.hooksPath", hooks.toString());

        Path wt = repo.resolve("wt-b");
        GitTestSupport.run(repo, "worktree", "add", "-B", "wt-b", wt.toString(), "HEAD");
        PostCreationSetup.setupGitHooks(repo, wt);
        String configured = GitHelper.runGit(wt, "config", "--get", "core.hooksPath");
        assertTrue(configured.contains(".githooks"), "AC6：hooksPath 应继承，实际=" + configured);
    }

    @Test
    void setupC_主仓node_modules时创建软链() throws Exception {
        Path repo = GitTestSupport.initRepo(tmp.resolve("repo-c"));
        Files.createDirectories(repo.resolve("node_modules").resolve("left-pad"));
        Path wt = repo.resolve("wt-c");
        GitTestSupport.run(repo, "worktree", "add", "-B", "wt-c", wt.toString(), "HEAD");

        PostCreationSetup.symlinkLargeDirs(repo, wt, PostCreationSetup.DEFAULT_SYMLINK_DIRS);
        assertTrue(Files.isSymbolicLink(wt.resolve("node_modules")), "AC7");
        assertTrue(Files.isDirectory(wt.resolve("node_modules")), "软链目标可达");
    }

    @Test
    void setupD_worktreeinclude按模式复制被忽略文件() throws Exception {
        Path repo = GitTestSupport.initRepo(tmp.resolve("repo-d"));
        Files.writeString(repo.resolve(".worktreeinclude"), "*.env\nconfig/secrets.yaml\n");
        // .gitignore 让目标文件进入 ignored 集合（ls-files --others --ignored 的前提）
        Files.writeString(repo.resolve(".gitignore"), ".env\nother.env\nconfig/secrets.yaml\nnot-included.txt\n");
        Files.writeString(repo.resolve(".env"), "A=1\n");
        Files.writeString(repo.resolve("other.env"), "B=2\n");
        Files.createDirectories(repo.resolve("config"));
        Files.writeString(repo.resolve("config/secrets.yaml"), "s\n");
        Files.writeString(repo.resolve("not-included.txt"), "n\n");
        GitTestSupport.run(repo, "add", ".worktreeinclude", ".gitignore");
        GitTestSupport.run(repo, "commit", "-m", "include");

        Path wt = repo.resolve("wt-d");
        GitTestSupport.run(repo, "worktree", "add", "-B", "wt-d", wt.toString(), "HEAD");
        PostCreationSetup.copyIncludedIgnored(repo, wt);
        assertTrue(Files.exists(wt.resolve(".env")), "AC8：*.env 命中");
        assertTrue(Files.exists(wt.resolve("other.env")), "AC8：*.env 命中");
        assertTrue(Files.exists(wt.resolve("config/secrets.yaml")), "精确路径命中");
        assertFalse(Files.exists(wt.resolve("not-included.txt")), "未列出的不复制");
    }

    @Test
    void 子步骤失败不抛异常() {
        // 源目录缺各种文件——全部 best-effort，不应抛
        Path repo = Path.of(".").toAbsolutePath();
        Path wt = Path.of(System.getProperty("java.io.tmpdir"));
        assertDoesNotThrow(() -> PostCreationSetup.run(repo, wt, null));
    }
}
