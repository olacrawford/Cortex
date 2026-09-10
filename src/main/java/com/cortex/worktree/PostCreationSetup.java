package com.cortex.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * 创建后环境初始化（F7-F10/G4）：A 复制本地配置、B 配置 git hooks、C 软链大目录、
 * D 按 {@code .worktreeinclude} 复制被忽略文件。全部 best-effort——任何子步骤失败
 * 仅 stderr 警告，不中断创建（N2）。
 */
public final class PostCreationSetup {

    /** 默认软链的大目录（G4-C）。 */
    public static final List<String> DEFAULT_SYMLINK_DIRS = List.of("node_modules", ".venv", "vendor");

    /** 本地配置文件（G4-A）：Worktree 里也放一份，运行期可用。 */
    private static final List<String> LOCAL_CONFIG_FILES = List.of(
            ".cortex/config.yaml", ".cortex/settings.local.yaml");

    private PostCreationSetup() {}

    /** 依次执行四类设置；每个子步骤独立 try/catch（N2）。 */
    public static void run(Path repoRoot, Path wtPath, List<String> symlinkDirs) {
        tryStep("copy-configs", () -> {
            try {
                copyLocalConfigs(repoRoot, wtPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        tryStep("git-hooks", () -> setupGitHooks(repoRoot, wtPath));
        tryStep("symlink-dirs", () -> symlinkLargeDirs(repoRoot, wtPath,
                symlinkDirs == null ? DEFAULT_SYMLINK_DIRS : symlinkDirs));
        tryStep("worktree-include", () -> copyIncludedIgnored(repoRoot, wtPath));
    }

    private static void tryStep(String step, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            System.err.printf("worktree: setup %s: %s%n", step,
                    e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** A（F7）：复制本地配置；目标已存在跳过、源不存在跳过。变更检查侧由 GitHelper 排除 .cortex/。 */
    static void copyLocalConfigs(Path repoRoot, Path wtPath) throws IOException {
        for (String rel : LOCAL_CONFIG_FILES) {
            Path src = repoRoot.resolve(rel);
            Path dst = wtPath.resolve(rel);
            if (Files.isRegularFile(src) && !Files.exists(dst)) {
                if (dst.getParent() != null) {
                    Files.createDirectories(dst.getParent());
                }
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** B（F8）：主仓配置了 core.hooksPath（含 .husky/ 自动配置）则给 Worktree 配同路径。 */
    static void setupGitHooks(Path repoRoot, Path wtPath) {
        try {
            String hooksPath = null;
            if (Files.isDirectory(repoRoot.resolve(".husky"))) {
                hooksPath = repoRoot.resolve(".husky").toAbsolutePath().toString();
            } else {
                try {
                    String configured = GitHelper.runGit(repoRoot, "config", "--get", "core.hooksPath");
                    if (!configured.isBlank()) {
                        hooksPath = repoRoot.resolve(configured.strip()).toAbsolutePath().toString();
                    }
                } catch (IOException ignored) {
                    // 未配置：跳过
                }
            }
            if (hooksPath != null) {
                GitHelper.runGit(wtPath, "config", "core.hooksPath", hooksPath);
            }
        } catch (Exception e) {
            System.err.printf("worktree: setup git-hooks: %s%n", e.getMessage());
        }
    }

    /** C（F9）：软链主仓的大目录（node_modules 等）到 Worktree。 */
    static void symlinkLargeDirs(Path repoRoot, Path wtPath, List<String> dirs) {
        for (String dir : dirs) {
            Path src = repoRoot.resolve(dir);
            Path dst = wtPath.resolve(dir);
            try {
                if (Files.isDirectory(src) && !Files.exists(dst)) {
                    Files.createSymbolicLink(dst, src.toAbsolutePath());
                }
            } catch (Exception e) {
                System.err.printf("worktree: symlink %s 失败: %s%n", dir, e.getMessage());
            }
        }
    }

    /** D（F10）：按主仓 {@code .worktreeinclude} 的 glob 模式复制被忽略但运行需要的文件。 */
    static void copyIncludedIgnored(Path repoRoot, Path wtPath) {
        Path include = repoRoot.resolve(".worktreeinclude");
        if (!Files.isRegularFile(include)) {
            return;
        }
        java.util.List<String> patterns;
        java.util.List<String> ignoredFiles;
        try {
            patterns = Files.readAllLines(include).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
            if (patterns.isEmpty()) {
                return;
            }
            ignoredFiles = GitHelper.runGit(repoRoot,
                    "ls-files", "--others", "--ignored", "--exclude-standard", "--directory").lines().toList();
        } catch (Exception e) {
            System.err.printf("worktree: setup worktree-include: %s%n", e.getMessage());
            return;
        }
        for (String rel : ignoredFiles) {
            if (rel.isBlank() || rel.endsWith("/")) {
                continue;
            }
            Path src = repoRoot.resolve(rel);
            if (!Files.isRegularFile(src)) {
                continue;
            }
            if (!matchesAny(rel, patterns)) {
                continue;
            }
            try {
                Path dst = wtPath.resolve(rel);
                if (!Files.exists(dst)) {
                    if (dst.getParent() != null) {
                        Files.createDirectories(dst.getParent());
                    }
                    Files.copy(src, dst);
                }
            } catch (Exception e) {
                System.err.printf("worktree: include %s 复制失败: %s%n", rel, e.getMessage());
            }
        }
    }

    /** glob 匹配：对完整相对路径与文件名各试一次（`*.env` 只带文件名也能命中）。 */
    static boolean matchesAny(String relPath, List<String> patterns) {
        java.nio.file.FileSystem fs = java.nio.file.FileSystems.getDefault();
        Path p = Path.of(relPath);
        for (String pat : patterns) {
            try {
                if (fs.getPathMatcher("glob:" + pat).matches(p)
                        || fs.getPathMatcher("glob:" + pat).matches(p.getFileName())) {
                    return true;
                }
            } catch (Exception ignored) {
                // 非法模式：跳过
            }
        }
        return false;
    }
}
