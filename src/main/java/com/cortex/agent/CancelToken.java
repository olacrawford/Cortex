package com.cortex.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * per-turn 轻量取消句柄：volatile 标志 + 取消回调。
 * 用户 Esc / Ctrl+C（流式态）触发 cancel()，Agent 循环与工具执行在检查点尽快退出。
 */
public final class CancelToken {

    private volatile boolean cancelled;
    private final List<Runnable> callbacks = new ArrayList<>();

    public boolean isCancelled() {
        return cancelled;
    }

    public synchronized void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        for (Runnable r : callbacks) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
        callbacks.clear();
    }

    /** 注册取消回调；已取消则立即执行。 */
    public synchronized void onCancel(Runnable callback) {
        if (cancelled) {
            callback.run();
        } else {
            callbacks.add(callback);
        }
    }
}
