package com.cortex.compact.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 会话级的「工具结果替换决策账本」。
 * <p>
 * seenIds 记录已经决策过的 toolUseId（无论替换还是保留原文）；
 * replacements 只保存「决定替换」那一支的预览字符串，键是 toolUseId。
 * 同一个 toolUseId 一旦进入 seenIds 就再也不会被重新评估，保证 prompt cache 前缀逐字节稳定。
 * <p>
 * 并发安全：账本的「读账本 → 决策 → 写账本」必须在同一把锁的同一临界区内原子完成，
 * 避免出现「已 Seen 但 replacement 未写」的中间态。对外只暴露 {@link #decideOnce} 这一
 * 个高层方法（持锁 + 回调内决策 + 同临界区写入），以及两个只读查询方法。
 */
public final class ContentReplacementState {

    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> seenIds = new HashSet<>();
    private final Map<String, String> replacements = new HashMap<>();

    public enum Decision { KEPT, REPLACED, SKIP }

    public record DecisionResult(Decision decision, String preview) {}

    /** 某个 id 是否已经决策过。 */
    public boolean seen(String id) {
        lock.lock();
        try {
            return seenIds.contains(id);
        } finally {
            lock.unlock();
        }
    }

    /** 某个 id 的预览字符串；未决策或决定保留时返回 null。 */
    public String replacement(String id) {
        lock.lock();
        try {
            return replacements.get(id);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在持锁状态下完成「查账本 → 决策 → 写账本」原子操作。
     * <ul>
     *   <li>id 已 Seen：直接返回账本存量结果（KEPT 返回原 content，REPLACED 返回 replacements[id]）。</li>
     *   <li>id 未 Seen：调 decide.get()（仍在持锁状态）：
     *     <ul>
     *       <li>KEPT → 写 seenIds，不写 replacements；返回原 content。</li>
     *       <li>REPLACED → 写 seenIds + replacements；返回 preview。</li>
     *       <li>SKIP（落盘失败，本轮不写账本下轮重试）→ 既不写 seenIds 也不写 replacements；返回原 content。</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    public String decideOnce(String id, String original, Supplier<DecisionResult> decide) {
        lock.lock();
        try {
            if (seenIds.contains(id)) {
                return replacements.getOrDefault(id, original);
            }
            DecisionResult r = decide.get();
            return switch (r.decision()) {
                case KEPT -> {
                    seenIds.add(id);
                    yield original;
                }
                case REPLACED -> {
                    seenIds.add(id);
                    replacements.put(id, r.preview());
                    yield r.preview();
                }
                case SKIP -> original;
            };
        } finally {
            lock.unlock();
        }
    }

    /** 清空决策账本（/clear 开新会话时调用）。 */
    public void reset() {
        lock.lock();
        try {
            seenIds.clear();
            replacements.clear();
        } finally {
            lock.unlock();
        }
    }
}
