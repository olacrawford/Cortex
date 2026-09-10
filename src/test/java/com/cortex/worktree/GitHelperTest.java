package com.cortex.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** GitHelper（T3/F15/G5）：git 进程环境、变更检查、纯 FS 的 HEAD 解析。 */
class GitHelperTest {

    @TempDir
    Path tmp;

    private Path repo() throws Exception {
        return GitTestSupport.initRepo(tmp.resolve("repo-" + System.nanoTime()));
    }

    @Test
    void gitProcess带防挂起环境与stdin重定向() throws Exception {
        ProcessBuilder pb = GitHelper.gitProcess(Path.of("."), "status");
        assertEquals("0", pb.environment().get("GIT_TERMINAL_PROMPT"));
        assertEquals("", pb.environment().get("GIT_ASKPASS"));
        // stdin 重定向自 /dev/null（进程可创建即视为配置成立）
        assertEquals(0, pb.start().waitFor());
    }

    @Test
    void runGit返回stdout_失败抛IOException() throws Exception {
        Path r = repo();
        assertEquals("init", GitHelper.runGit(r, "log", "-1", "--pretty=%s"), "stdout 原样返回（去尾换行）");
        IOException e = assertThrows(IOException.class, () -> GitHelper.runGit(r, "frobnicate"));
        assertTrue(e.getMessage().contains("frobnicate"));
    }

    @Test
    void hasWorktreeChanges_无修改false_有修改true_出错failClosed() throws Exception {
        Path r = repo();
        Path wtPath = r.resolve(".cortex/worktrees/w1");
        GitTestSupport.run(r, "worktree", "add", "-B", "wt1", wtPath.toString(), "HEAD");
        assertFalse(GitHelper.hasWorktreeChanges(wtPath, "HEAD"), "干净 worktree 应无变更");

        Files.writeString(wtPath.resolve("README.md"), "modified\n");
        assertTrue(GitHelper.hasWorktreeChanges(wtPath, "HEAD"), "未提交修改应判有变更");

        // git 命令出错（路径不存在）→ fail-closed true
        assertTrue(GitHelper.hasWorktreeChanges(tmp.resolve("nonexistent"), "HEAD"));
    }

    @Test
    void resolveHeadShaFromFS在真实worktree上返回SHA() throws Exception {
        Path r = repo();
        Path wtPath = r.resolve(".cortex/worktrees/w2");
        GitTestSupport.run(r, "worktree", "add", "-B", "wt2", wtPath.toString(), "HEAD");
        Optional<String> sha = GitHelper.resolveHeadShaFromFS(wtPath);
        assertTrue(sha.isPresent());
        assertEquals(40, sha.orElseThrow().length(), "应为完整 SHA");
        // 普通目录（无 .git 指针文件）→ empty
        assertTrue(GitHelper.resolveHeadShaFromFS(tmp).isEmpty());
    }
}
