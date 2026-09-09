package com.cortex.compact.state;

import com.cortex.compact.CompactConstants;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 自动摘要连续失败计数，用于熔断。
 * 手动 {@code /compact} 与紧急压缩路径不读这个字段；只有自动路径读写它。
 * 并发安全：所有读写都在锁内完成（AC23c）。
 */
public final class AutoCompactTrackingState {

    private final ReentrantLock lock = new ReentrantLock();
    private int consecutiveFailures = 0;

    /** 一次自动摘要成功：失败计数立即清零。 */
    public void recordSuccess() {
        lock.lock();
        try {
            consecutiveFailures = 0;
        } finally {
            lock.unlock();
        }
    }

    /** 一次自动摘要失败（含 PTL 重试用光）：计数 +1。 */
    public void recordFailure() {
        lock.lock();
        try {
            consecutiveFailures++;
        } finally {
            lock.unlock();
        }
    }

    /** 是否已触发熔断（连续失败 ≥ 阈值）。 */
    public boolean tripped() {
        lock.lock();
        try {
            return consecutiveFailures >= CompactConstants.MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES;
        } finally {
            lock.unlock();
        }
    }
}
