package com.cortex.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Worktree 生命周期管理器（F2-F14/G1）：创建（含毫秒级快速恢复）、进入、退出、删除、
 * 自动清理与后台过期清理。工作目录经 explicit cwd（ToolContext）传递，绝不进程级 chdir（N4）。
 * <p>
 * 并发约定（N3）：{@code active} 映射与 session 状态变更受 {@link #lock} 保护；
 * git 子进程调用不持锁。git 操作失败原则上抛 {@link IOException}；变更检查 fail-closed（G10）。
 */
public final class WorktreeManager {

    private final Path repoRoot;      // 绝对路径的 git 仓库根
    private final Path worktreeDir;   // <repoRoot>/.cortex/worktrees
    private final Path sessionFile;   // <repoRoot>/.cortex/worktree_session.json
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Worktree> active = new HashMap<>();
    private WorktreeSession currentSession;

    /**
     * 构造（F5）：校验 repoRoot 是 git 仓库根（否则抛 IOException，调用方可降级为
     * 「Worktree 功能未启用」）；建 worktreeDir；从 sessionFile 恢复会话（指向目录已消失则清空）；
     * 扫描 worktreeDir 还原 active 映射（纯文件系统读，不调 git——G5）。
     */
    public WorktreeManager(Path repoRoot) throws IOException {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        String toplevel;
        try {
            toplevel = GitHelper.runGit(this.repoRoot, "rev-parse", "--show-toplevel");
        } catch (IOException e) {
            throw new IOException("目录不是 git 仓库: " + this.repoRoot, e);
        }
        // macOS 等环境 /tmp → /private/tmp 有符号链接，比较前先解析真实路径
        Path gitTop = Path.of(toplevel.strip()).toAbsolutePath().normalize();
        Path realRoot;
        try {
            realRoot = this.repoRoot.toRealPath();
        } catch (IOException e) {
            realRoot = this.repoRoot;
        }
        if (!realRoot.equals(gitTop)) {
            throw new IOException("repoRoot 与 git toplevel 不一致: " + toplevel);
        }
        this.worktreeDir = this.repoRoot.resolve(".cortex").resolve("worktrees");
        this.sessionFile = this.repoRoot.resolve(".cortex").resolve("worktree_session.json");
        Files.createDirectories(worktreeDir);
        warnIfGitignoreMissing();
        restoreSession();
        restoreActiveFromDisk();
    }

    /** F35：.gitignore 缺少 worktree 条目时只警告，不修改用户配置。 */
    private void warnIfGitignoreMissing() {
        Path gitignore = repoRoot.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) {
            return;
        }
        try {
            String text = Files.readString(gitignore);
            boolean hasDir = text.contains(".cortex/worktrees/");
            boolean hasSession = text.contains(".cortex/worktree_session.json");
            if (!hasDir || !hasSession) {
                System.err.println("[worktree] warn: 建议 .gitignore 加入 .cortex/worktrees/ 与"
                        + " .cortex/worktree_session.json（避免 Worktree 副本被 git 追踪）");
            }
        } catch (IOException ignored) {
        }
    }

    // ─── 查询 ───

    public Path repoRoot() {
        return repoRoot;
    }

    public Path worktreeDir() {
        return worktreeDir;
    }

    /** 全部活跃 Worktree，按 name 升序。 */
    public List<Worktree> list() {
        lock.lock();
        try {
            List<Worktree> all = new ArrayList<>(active.values());
            all.sort(Comparator.comparing(Worktree::name));
            return List.copyOf(all);
        } finally {
            lock.unlock();
        }
    }

    public Optional<Worktree> get(String name) {
        lock.lock();
        try {
            return Optional.ofNullable(active.get(name));
        } finally {
            lock.unlock();
        }
    }

    public WorktreeSession currentSession() {
        lock.lock();
        try {
            return currentSession;
        } finally {
            lock.unlock();
        }
    }

    // ─── 创建（F6）───

    /**
     * 创建 Worktree；同名已活跃抛异常；目录已存在走快速恢复（不调 git 子进程，G5/AC4）；
     * 否则 {@code git worktree add -B <branch> <path> <base>} + 创建后环境初始化（N2 best-effort）。
     */
    public Worktree create(String name, String baseRef, boolean manual) throws IOException {
        WorktreeSlug.validate(name);
        lock.lock();
        try {
            if (active.containsKey(name)) {
                throw new IOException("worktree 已存在: " + name + "（先 remove 或换一个名字）");
            }
        } finally {
            lock.unlock();
        }
        String flatSlug = WorktreeSlug.flatten(name);
        Path wtPath = worktreeDir.resolve(flatSlug);
        String branchName = "worktree-" + flatSlug;

        Worktree wt;
        if (Files.exists(wtPath)) {
            // 快速恢复：只读文件系统，不调任何 git 子进程（G5）
            String sha = GitHelper.resolveHeadShaFromFS(wtPath).orElse("");
            wt = new Worktree(name, wtPath, branchName, baseRef, sha, Instant.now(), manual);
        } else {
            try {
                GitHelper.runGit(repoRoot, "worktree", "add", "-B", branchName, wtPath.toString(), baseRef);
            } catch (IOException e) {
                deleteRecursivelyQuietly(wtPath); // 清理可能残留的目录
                throw e;
            }
            PostCreationSetup.run(repoRoot, wtPath, PostCreationSetup.DEFAULT_SYMLINK_DIRS);
            String sha = GitHelper.runGit(wtPath, "rev-parse", "HEAD");
            wt = new Worktree(name, wtPath, branchName, baseRef, sha, Instant.now(), manual);
        }
        lock.lock();
        try {
            active.put(name, wt);
        } finally {
            lock.unlock();
        }
        return wt;
    }

    // ─── 进入（F11）───

    /**
     * 进入 Worktree：记录会话（原 cwd/分支/HEAD）并持久化；**不改 JVM 进程当前目录**（AC9），
     * cwd 的生效由上层把 session.worktreePath 写进 ToolContext 完成。
     */
    public WorktreeSession enter(String name) throws IOException {
        Worktree wt;
        lock.lock();
        try {
            wt = active.get(name);
        } finally {
            lock.unlock();
        }
        if (wt == null) {
            throw new IOException("worktree 不存在: " + name);
        }
        String originalCwd = Path.of("").toAbsolutePath().toString();
        String branch = "";
        String head = "";
        try {
            branch = GitHelper.runGit(repoRoot, "rev-parse", "--abbrev-ref", "HEAD");
        } catch (IOException ignored) {
        }
        try {
            head = GitHelper.runGit(repoRoot, "rev-parse", "HEAD");
        } catch (IOException ignored) {
        }
        WorktreeSession session = WorktreeSession.create(
                originalCwd, wt.path().toString(), name, branch, head);
        lock.lock();
        try {
            currentSession = session;
        } finally {
            lock.unlock();
        }
        SessionStore.save(sessionFile, session);
        return session;
    }

    // ─── 退出（F12）───

    /**
     * 退出当前会话；{@code action=REMOVE} 时变更保护（未 discard 且有变更抛
     * {@link WorktreeHasChangesException}）；REMOVE 分支删除目录与分支。
     */
    public ExitReport exit(String name, ExitAction action, ExitOptions opts) throws IOException {
        Worktree wt;
        String originalCwd;
        lock.lock();
        try {
            if (currentSession == null || !name.equals(currentSession.worktreeName())) {
                throw new IOException("worktree 不是当前会话: " + name + "（只能退出已 enter 的目录）");
            }
            wt = active.get(name);
            originalCwd = currentSession.originalCwd(); // 供上层 UI 还原 cwd（F12-3）
        } finally {
            lock.unlock();
        }
        if (wt == null) {
            throw new IOException("worktree 不存在: " + name);
        }
        if (action == ExitAction.REMOVE && !opts.discardChanges()
                && GitHelper.hasWorktreeChanges(wt.path(), wt.headCommit())) {
            throw new WorktreeHasChangesException(wt.path().toString());
        }
        lock.lock();
        try {
            currentSession = null;
        } finally {
            lock.unlock();
        }
        SessionStore.save(sessionFile, null);
        boolean removed = false;
        if (action == ExitAction.REMOVE) {
            removeDirectoryAndBranch(wt);
            lock.lock();
            try {
                active.remove(name);
            } finally {
                lock.unlock();
            }
            removed = true;
        }
        return new ExitReport(removed, wt.path().toString(), wt.branch());
    }

    // ─── 删除（F13）───

    /** 独立删除入口：允许删除非当前会话的 Worktree；变更保护同 exit。 */
    public void remove(String name, ExitOptions opts) throws IOException {
        Worktree wt;
        lock.lock();
        try {
            wt = active.get(name);
            if (wt != null && currentSession != null && name.equals(currentSession.worktreeName())) {
                currentSession = null;
            }
        } finally {
            lock.unlock();
        }
        if (wt == null) {
            // 不在 active（可能是启动扫描没识别的目录）：直接按路径强删
            Path wtPath = worktreeDir.resolve(WorktreeSlug.flatten(name));
            if (Files.exists(wtPath)) {
                GitHelper.runGit(repoRoot, "worktree", "remove", "--force", wtPath.toString());
                deleteBranchQuietly("worktree-" + WorktreeSlug.flatten(name));
            } else {
                throw new IOException("worktree 不存在: " + name);
            }
            return;
        }
        if (!opts.discardChanges() && GitHelper.hasWorktreeChanges(wt.path(), wt.headCommit())) {
            throw new WorktreeHasChangesException(wt.path().toString());
        }
        removeDirectoryAndBranch(wt);
        lock.lock();
        try {
            active.remove(name);
        } finally {
            lock.unlock();
        }
    }

    // ─── 自动清理（F14/G9）───

    /**
     * SubAgent 跑完后的自动清理：manual 创建直接保留；无变更直接删；有变更保留并回报
     * 路径与分支（由主 Agent review，G9）。
     */
    public AutoCleanupReport autoCleanup(String name) throws IOException {
        Worktree wt;
        lock.lock();
        try {
            wt = active.get(name);
        } finally {
            lock.unlock();
        }
        if (wt == null) {
            return AutoCleanupReport.removed();
        }
        if (wt.manual()) {
            return new AutoCleanupReport(true, wt.path().toString(), wt.branch());
        }
        if (!GitHelper.hasWorktreeChanges(wt.path(), wt.headCommit())) {
            remove(name, ExitOptions.discard());
            return AutoCleanupReport.removed();
        }
        return new AutoCleanupReport(true, wt.path().toString(), wt.branch());
    }

    // ─── 过期清理（F33/G10）───

    /**
     * 三层过滤的过期清理：① 名字匹配临时模式 {@code agent-a[0-9a-f]{7}}；
     * ② 目录 mtime 早于 cutoff 且不是当前会话；③ 无未提交修改、无未推送 commit（fail-closed）。
     * 返回被清理的名字列表。
     */
    public List<String> sweepStale(Instant cutoff) {
        List<String> removed = new ArrayList<>();
        List<Path> candidates;
        try (Stream<Path> files = Files.list(worktreeDir)) {
            candidates = files.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            return removed;
        }
        WorktreeSession session = currentSession();
        for (Path sub : candidates) {
            String dirName = sub.getFileName() == null ? "" : sub.getFileName().toString();
            if (!WorktreeNaming.EPHEMERAL_PATTERN.matcher(dirName).matches()) {
                continue; // 第一层：只识别临时模式
            }
            try {
                if (Files.getLastModifiedTime(sub).toInstant().isAfter(cutoff)) {
                    continue; // 第二层：还不够旧
                }
            } catch (IOException e) {
                continue;
            }
            if (session != null && Path.of(session.worktreePath()).equals(sub.toAbsolutePath().normalize())) {
                continue; // 第二层：当前会话不清理
            }
            // 第三层：fail-closed 变更检查
            if (GitHelper.hasWorktreeChanges(sub, "HEAD")) {
                continue;
            }
            try {
                if (!GitHelper.runGit(sub, "rev-list", "--max-count=1", "HEAD", "--not", "--remotes").isEmpty()) {
                    continue; // 有未推送 commit：保留
                }
            } catch (IOException e) {
                continue; // git 出错：保留
            }
            try {
                remove(dirName, ExitOptions.discard());
                removed.add(dirName);
            } catch (IOException e) {
                System.err.printf("worktree: sweep %s 失败: %s%n", dirName, e.getMessage());
            }
        }
        return removed;
    }

    // ─── 内部 ───

    /** 启动恢复 session（F5/F31/N5）：文件缺失/为 null → 空；目录消失 → 清空；坏 JSON → 警告清空。 */
    private void restoreSession() {
        try {
            Optional<WorktreeSession> loaded = SessionStore.load(sessionFile);
            if (loaded.isPresent()) {
                WorktreeSession s = loaded.get();
                if (!Files.exists(Path.of(s.worktreePath()))) {
                    SessionStore.clear(sessionFile);
                    System.err.println("worktree: session worktree gone, cleared");
                    currentSession = null;
                } else {
                    currentSession = s;
                }
            }
        } catch (IOException e) {
            System.err.println("worktree: session 文件损坏,已忽略: " + e.getMessage());
            try {
                SessionStore.clear(sessionFile);
            } catch (IOException ignored) {
            }
            currentSession = null;
        }
    }

    /** 启动扫描 worktreeDir 还原 active（快速恢复路径，纯文件系统读，G5）。 */
    private void restoreActiveFromDisk() {
        try (Stream<Path> files = Files.list(worktreeDir)) {
            files.filter(Files::isDirectory).forEach(sub -> {
                String dirName = sub.getFileName() == null ? null : sub.getFileName().toString();
                if (dirName == null || dirName.isBlank()) {
                    return;
                }
                // 目录名即 flatSlug；还原为 name（+ → / 的还原是启发式：仅在对应目录也合理时）
                // 这里保守做法：name = dirName（手动创建的扁平名与含 + 的名字都能对上 list/remove）
                String sha = GitHelper.resolveHeadShaFromFS(sub).orElse("");
                active.put(dirName, new Worktree(dirName, sub, "worktree-" + dirName,
                        "", sha, Instant.now(), false));
            });
        } catch (IOException e) {
            System.err.println("worktree: 目录扫描失败: " + e.getMessage());
        }
    }

    /** 删目录 + 删分支：`worktree remove --force` → sleep(100)（lockfile 竞态）→ `branch -D`。 */
    private void removeDirectoryAndBranch(Worktree wt) throws IOException {
        GitHelper.runGit(repoRoot, "worktree", "remove", "--force", wt.path().toString());
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deleteBranchQuietly(wt.branch());
    }

    private void deleteBranchQuietly(String branch) {
        try {
            GitHelper.runGit(repoRoot, "branch", "-D", branch);
        } catch (IOException e) {
            System.err.printf("worktree: 分支删除失败(保留 %s): %s%n", branch, e.getMessage());
        }
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
