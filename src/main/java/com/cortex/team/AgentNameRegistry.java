package com.cortex.team;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 队员命名注册表（F35-F38）：name → agentId 双向映射；同名后注册覆盖前者（弱引用）。
 * ch13 的 TaskManager.byName 统一迁移到本注册表。
 */
public final class AgentNameRegistry {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, String> byName = new HashMap<>();
    private final Map<String, String> byId = new HashMap<>();

    /** 注册（F37/F38）：同名覆盖；一个 agentId 只保留最新 name。 */
    public void register(String name, String agentId) {
        if (name == null || name.isBlank() || agentId == null || agentId.isBlank()) {
            return;
        }
        lock.lock();
        try {
            String oldId = byName.put(name, agentId);
            if (oldId != null && !oldId.equals(agentId)) {
                byId.remove(oldId, name);
            }
            String oldName = byId.put(agentId, name);
            if (oldName != null && !oldName.equals(name)) {
                byName.remove(oldName, agentId);
            }
        } finally {
            lock.unlock();
        }
    }

    public void unregister(String name) {
        lock.lock();
        try {
            String id = byName.remove(name);
            if (id != null) {
                byId.remove(id, name);
            }
        } finally {
            lock.unlock();
        }
    }

    public void unregisterByAgentId(String agentId) {
        lock.lock();
        try {
            String name = byId.remove(agentId);
            if (name != null) {
                byName.remove(name, agentId);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 解析：name 优先，agentId 反查兜底（F36）。 */
    public Optional<String> resolve(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return Optional.empty();
        }
        lock.lock();
        try {
            return Optional.ofNullable(byName.get(nameOrId)).or(() -> Optional.ofNullable(byId.get(nameOrId)));
        } finally {
            lock.unlock();
        }
    }

    public Optional<String> nameOf(String agentId) {
        lock.lock();
        try {
            return Optional.ofNullable(byId.get(agentId));
        } finally {
            lock.unlock();
        }
    }

    public Map<String, String> snapshot() {
        lock.lock();
        try {
            return Map.copyOf(byName);
        } finally {
            lock.unlock();
        }
    }
}
