package com.cortex.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** worktree 测试共用：在临时目录搭一个带初始提交的真实 git 仓库。 */
public final class GitTestSupport {

    private GitTestSupport() {}

    /** 建一个 git 仓库（含一次初始提交）；返回仓库根。 */
    public static Path initRepo(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        run(dir, "init");
        run(dir, "config", "user.email", "test@example.com");
        run(dir, "config", "user.name", "test");
        run(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("README.md"), "hello\n");
        run(dir, "add", ".");
        run(dir, "commit", "-m", "init");
        return dir;
    }

    public static void run(Path dir, String... args) throws IOException, InterruptedException {
        Process p = GitHelper.gitProcess(dir, args).start();
        String err = new String(p.getErrorStream().readAllBytes());
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git 超时");
        }
        if (p.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " 失败: " + err.strip());
        }
    }
}
