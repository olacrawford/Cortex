package com.cortex.team;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 团队对象（F1/F8-F10）：成员花名册 + config.json 持久化。
 * 所有 members 变更受 {@link #lock} 保护（N4）；变更前先从磁盘 reload（跨进程兜底，F19c）。
 */
public final class Team {

    private final String name;              // 原始名
    private final String sanitizedName;     // 路径用名
    private final String leadAgentId;       // 本期固定 "lead"
    private final BackendType backend;
    private final String description;
    private final Instant createdAt;
    private final Path configDir;           // <home>/.cortex/teams/<sanitized>/
    private final ReentrantLock lock = new ReentrantLock();
    private final List<TeammateInfo> members = new ArrayList<>();

    public Team(String name, String sanitizedName, String leadAgentId, BackendType backend,
                String description, Instant createdAt, Path configDir) {
        this.name = name;
        this.sanitizedName = sanitizedName;
        this.leadAgentId = leadAgentId;
        this.backend = backend;
        this.description = description == null ? "" : description;
        this.createdAt = createdAt;
        this.configDir = configDir;
    }

    // ─── 只读 ───

    public String name() { return name; }
    public String sanitizedName() { return sanitizedName; }
    public String leadAgentId() { return leadAgentId; }
    public BackendType backend() { return backend; }
    public String description() { return description; }
    public Instant createdAt() { return createdAt; }
    public Path configDir() { return configDir; }
    public Path configPath() { return configDir.resolve("config.json"); }
    public Path mailboxDir() { return configDir.resolve("mailbox"); }
    public Path tasksPath() { return configDir.resolve("tasks.json"); }
    public ReentrantLock lock() { return lock; }

    public List<TeammateInfo> members() {
        lock.lock();
        try {
            return List.copyOf(members);
        } finally {
            lock.unlock();
        }
    }

    public Optional<TeammateInfo> memberByName(String memberName) {
        lock.lock();
        try {
            return members.stream().filter(m -> m.name().equals(memberName)).findFirst();
        } finally {
            lock.unlock();
        }
    }

    public Optional<TeammateInfo> memberByAgentId(String agentId) {
        lock.lock();
        try {
            return members.stream().filter(m -> m.agentId().equals(agentId)).findFirst();
        } finally {
            lock.unlock();
        }
    }

    // ─── 变更（reload-before-modify，F19c）───

    /** 加成员（F8）；重名抛 MemberExistsException。 */
    public void addMember(TeammateInfo info) throws IOException {
        lock.lock();
        try {
            Persistence.reloadMembersFromDiskLocked(this);
            if (members.stream().anyMatch(m -> m.name().equals(info.name()))) {
                throw new MemberExistsException(sanitizedName, info.name());
            }
            members.add(info);
            saveLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 更新活跃状态（F9）；成员不存在抛 MemberNotFoundException。 */
    public void setMemberActive(String memberName, boolean active) throws IOException {
        lock.lock();
        try {
            Persistence.reloadMembersFromDiskLocked(this);
            boolean changed = false;
            List<TeammateInfo> updated = new ArrayList<>();
            for (TeammateInfo m : members) {
                if (m.name().equals(memberName)) {
                    updated.add(m.withActive(active));
                    changed = true;
                } else {
                    updated.add(m);
                }
            }
            if (!changed) {
                throw new MemberNotFoundException(sanitizedName, memberName);
            }
            members.clear();
            members.addAll(updated);
            saveLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 移除成员（F10）。 */
    public void removeMember(String memberName) throws IOException {
        lock.lock();
        try {
            Persistence.reloadMembersFromDiskLocked(this);
            if (members.removeIf(m -> m.name().equals(memberName))) {
                saveLocked();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 仅供 Persistence.reloadMembersFromDiskLocked 在持锁状态下覆盖 members。 */
    void replaceMembersLocked(List<TeammateInfo> reloaded) {
        members.clear();
        members.addAll(reloaded);
    }

    /** 持锁状态下从 TeamSnapshot 装载（启动扫描用）。 */
    public void loadFromSnapshotLocked(Persistence.TeamSnapshot snap) {
        members.clear();
        members.addAll(snap.members());
    }

    private void saveLocked() throws IOException {
        Persistence.atomicWriteJson(configPath(), new Persistence.TeamSnapshot(
                name, sanitizedName, leadAgentId, backend, description,
                createdAt.getEpochSecond(), List.copyOf(members)));
    }
}
