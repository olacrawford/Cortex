package com.cortex.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * git 子进程与文件系统级 git 状态读取辅助（T3/F15/G5）。
 * 所有 git 调用：{@code GIT_TERMINAL_PROMPT=0} + {@code GIT_ASKPASS=""} + stdin 关闭
 * （永不挂起等凭据输入）；Worktree 内部 git 操作不持有 Manager 锁（N3）。
 */
public final class GitHelper {

    private GitHelper() {}

    /** 构造防挂起的 git 进程 builder（cwd、环境、stdin 重定向）。 */
    public static ProcessBuilder gitProcess(Path workDir, String... args) {
        ProcessBuilder pb = new ProcessBuilder("git");
        pb.command().addAll(java.util.List.of(args));
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(
                System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null")));
        return pb;
    }

    /** 执行 git 命令返回 stdout（去尾换行）；非零退出抛 IOException（带 stderr）。 */
    public static String runGit(Path workDir, String... args) throws IOException {
        try {
            Process process = gitProcess(workDir, args).start();
            byte[] out;
            try (var in = process.getInputStream()) {
                out = in.readAllBytes();
            }
            String err;
            try (var errStream = process.getErrorStream()) {
                err = new String(errStream.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git 命令超时: git " + String.join(" ", args));
            }
            if (process.exitValue() != 0) {
                throw new IOException("git " + String.join(" ", args)
                        + " 失败(exit " + process.exitValue() + "): " + err);
            }
            String text = new String(out, StandardCharsets.UTF_8);
            return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git 命令被中断", e);
        }
    }

    /** 复制进 worktree 的运行时目录（阶段13 设置 A），变更检查时排除。 */
    private static final String RUNTIME_DIR = ".cortex";

    /**
     * 变更检查（F15）：① {@code status --porcelain} 非空 = 有未提交修改；
     * ② {@code rev-list --count <base>..HEAD} &gt; 0 = 有本地新增 commit。
     * status 排除运行时副本目录（设置 A 复制的 .cortex/ 配置不算变更，G9）。
     * 任一 git 命令本身出错 fail-closed 返回 true（宁可保留，G10）。
     */
    public static boolean hasWorktreeChanges(Path wtPath, String baseCommit) {
        try {
            if (!runGit(wtPath, "status", "--porcelain", "--", ".", ":(exclude)" + RUNTIME_DIR).isEmpty()) {
                return true;
            }
            String count = runGit(wtPath, "rev-list", "--count", baseCommit + "..HEAD");
            return Integer.parseInt(count.strip()) > 0;
        } catch (Exception e) {
            return true; // fail-closed
        }
    }

    /**
     * 快速恢复（G5/F6-4）：不调 git 子进程，纯文件系统读取 Worktree 的 HEAD SHA——
     * 读 {@code <wt>/.git} 指针 → {@code <gitdir>/HEAD} → 对应 ref 文件。
     * 失败返回 empty。
     */
    public static Optional<String> resolveHeadShaFromFS(Path wtPath) {
        try {
            Path gitFile = wtPath.resolve(".git");
            if (!Files.isRegularFile(gitFile)) {
                return Optional.empty();
            }
            String pointer = Files.readString(gitFile, StandardCharsets.UTF_8).strip();
            if (!pointer.startsWith("gitdir:")) {
                return Optional.empty();
            }
            Path gitdir = Path.of(pointer.substring("gitdir:".length()).strip());
            if (!gitdir.isAbsolute()) {
                gitdir = wtPath.resolve(gitdir);
            }
            Path head = gitdir.resolve("HEAD");
            if (!Files.isRegularFile(head)) {
                return Optional.empty();
            }
            String headText = Files.readString(head, StandardCharsets.UTF_8).strip();
            if (headText.startsWith("ref:")) {
                String ref = headText.substring("ref:".length()).strip();
                // worktree 分支的 SHA 通常在主仓公共 refs（gitdir 只存 HEAD 与私有状态）
                Path common = gitdir;
                Path commondirFile = gitdir.resolve("commondir");
                if (Files.isRegularFile(commondirFile)) {
                    String cd = Files.readString(commondirFile, StandardCharsets.UTF_8).strip();
                    Path commonPath = Path.of(cd);
                    common = commonPath.isAbsolute() ? commonPath : gitdir.resolve(commonPath).normalize();
                }
                for (Path base : new Path[]{gitdir, common}) {
                    Path refFile = base.resolve(ref);
                    if (Files.isRegularFile(refFile)) {
                        return Optional.of(Files.readString(refFile, StandardCharsets.UTF_8).strip());
                    }
                }
                // 兜底：packed-refs（公共目录）
                Path packed = common.resolve("packed-refs");
                if (Files.isRegularFile(packed)) {
                    for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
                        if (line.endsWith(" " + ref)) {
                            return Optional.of(line.substring(0, line.indexOf(' ')).strip());
                        }
                    }
                }
                return Optional.empty();
            }
            return Optional.of(headText); // detached HEAD：内容即 SHA
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
