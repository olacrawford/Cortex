package com.cortex.team;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Team 共享任务列表（T8/F26-F30）：tasks.json read-modify-write，文件锁串行。
 * isReady 在读取视图时计算（blockedBy 全 completed），不落盘。
 */
public final class TaskStore {

    private final Path path; // <teamConfigDir>/tasks.json
    private final ReentrantLock lock = new ReentrantLock();

    public TaskStore(Path path) {
        this.path = path;
    }

    /** 新建任务，返回生成的 taskId（task_ + 6 位 hex）。 */
    public String create(String title, String description, String assignee,
                         List<String> blockedBy) throws IOException {
        String id = String.format("task_%06x", ThreadLocalRandom.current().nextInt(0x1000000));
        long now = System.currentTimeMillis() / 1000;
        TeamTask task = new TeamTask(id, title, description == null ? "" : description,
                "pending", assignee == null ? "" : assignee, blockedBy, List.of(), now, now);
        lock.lock();
        try (var unused = FileLock.acquire(lockPath())) {
            List<TeamTask> tasks = new ArrayList<>(readLocked());
            tasks.add(task);
            saveLocked(tasks);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("任务创建失败", e);
        } finally {
            lock.unlock();
        }
        return id;
    }

    public Optional<TeamTask> get(String taskId) throws IOException {
        lock.lock();
        try {
            return readLocked().stream().filter(t -> t.id().equals(taskId)).findFirst();
        } finally {
            lock.unlock();
        }
    }

    /** 列表（可按 status 过滤），附 isReady 视图字段（AC11）。 */
    public List<TaskView> list(String statusFilter) throws IOException {
        lock.lock();
        try {
            List<TeamTask> all = readLocked();
            List<TaskView> out = new ArrayList<>();
            for (TeamTask t : all) {
                if (statusFilter != null && !statusFilter.isBlank() && !statusFilter.equals(t.status())) {
                    continue;
                }
                boolean ready = t.blockedBy().stream().allMatch(bid ->
                        all.stream().filter(b -> b.id().equals(bid)).findFirst()
                                .map(b -> "completed".equals(b.status())).orElse(true));
                out.add(new TaskView(t, ready));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** 更新（F29）：addBlockedBy/addBlockedBy 双向维护（AC10）。 */
    public void update(String taskId, TaskPatch patch) throws IOException {
        if (patch == null || patch.isEmpty()) {
            return;
        }
        lock.lock();
        try (var unused = FileLock.acquire(lockPath())) {
            List<TeamTask> tasks = new ArrayList<>(readLocked());
            TeamTask target = tasks.stream().filter(t -> t.id().equals(taskId)).findFirst()
                    .orElseThrow(() -> new TeamException("未找到任务: " + taskId));
            long now = System.currentTimeMillis() / 1000;
            List<TeamTask> updated = new ArrayList<>();
            for (TeamTask t : tasks) {
                if (t.id().equals(taskId)) {
                    updated.add(apply(t, patch, now));
                } else {
                    updated.add(applyDependencyMirror(t, taskId, patch));
                }
            }
            saveLocked(updated);
        } catch (TeamException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("任务更新失败", e);
        } finally {
            lock.unlock();
        }
    }

    private TeamTask apply(TeamTask t, TaskPatch p, long now) {
        List<String> blockedBy = new ArrayList<>(t.blockedBy());
        List<String> blocks = new ArrayList<>(t.blocks());
        if (p.addBlockedBy() != null) {
            for (String b : p.addBlockedBy()) {
                if (!blockedBy.contains(b)) {
                    blockedBy.add(b);
                }
            }
        }
        if (p.addBlocks() != null) {
            for (String b : p.addBlocks()) {
                if (!blocks.contains(b)) {
                    blocks.add(b);
                }
            }
        }
        if (p.removeBlockedBy() != null) {
            blockedBy.removeAll(p.removeBlockedBy());
        }
        if (p.removeBlocks() != null) {
            blocks.removeAll(p.removeBlocks());
        }
        String status = p.status() == null ? t.status() : p.status();
        return new TeamTask(t.id(),
                p.title() == null ? t.title() : p.title(),
                p.description() == null ? t.description() : p.description(),
                status,
                p.assignee() == null ? t.assignee() : p.assignee(),
                blockedBy, blocks, t.createdAt(), now);
    }

    /** 双向维护：X.addBlockedBy=[self] 时给 self 阻塞者 X 的 blocks 加 self；反之亦然。 */
    private TeamTask applyDependencyMirror(TeamTask other, String selfId, TaskPatch p) {
        List<String> blocks = new ArrayList<>(other.blocks());
        List<String> blockedBy = new ArrayList<>(other.blockedBy());
        boolean changed = false;
        if (p.addBlockedBy() != null) {
            for (String b : p.addBlockedBy()) {
                if (b.equals(other.id()) && !blocks.contains(selfId)) {
                    blocks.add(selfId);
                    changed = true;
                }
            }
        }
        if (p.addBlocks() != null) {
            for (String b : p.addBlocks()) {
                if (b.equals(other.id()) && !blockedBy.contains(selfId)) {
                    blockedBy.add(selfId);
                    changed = true;
                }
            }
        }
        if (p.removeBlockedBy() != null && p.removeBlockedBy().contains(other.id())) {
            changed |= blocks.removeIf(selfId::equals);
        }
        if (p.removeBlocks() != null && p.removeBlocks().contains(other.id())) {
            changed |= blockedBy.removeIf(selfId::equals);
        }
        return changed ? new TeamTask(other.id(), other.title(), other.description(), other.status(),
                other.assignee(), blockedBy, blocks, other.createdAt(), other.updatedAt()) : other;
    }

    private Path lockPath() {
        return path.resolveSibling(path.getFileName() + ".lock");
    }

    private List<TeamTask> readLocked() throws IOException {
        if (!java.nio.file.Files.exists(path)) {
            return List.of();
        }
        return Persistence.readJson(path, Box.class).map(Box::tasks).orElse(List.of());
    }

    private void saveLocked(List<TeamTask> tasks) throws IOException {
        Persistence.atomicWriteJson(path, new Box(tasks));
    }

    /** tasks.json 结构。 */
    public record Box(List<TeamTask> tasks) {
        public Box {
            if (tasks == null) {
                tasks = new ArrayList<>();
            }
        }
    }

    /** 列表视图：task + isReady（blockedBy 是否全部 completed，AC11）。 */
    public record TaskView(TeamTask task, boolean isReady) {}
}
