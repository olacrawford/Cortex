package com.cortex.agent;

import com.cortex.compact.Recovery;
import com.cortex.compact.state.AutoCompactTrackingState;
import com.cortex.compact.state.ContentReplacementState;
import com.cortex.compact.state.SessionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 跨 run 持有的长生命周期状态容器。
 * 现状的 TUI 在 beginTurn 里每轮重新构造 Agent，会把 compact 的长生命周期状态（替换决策账本、
 * 文件追踪、自动摘要熔断计数、usageAnchor、会话目录）重置掉——决策冻结与熔断器立刻失效。
 * 本章把这类状态收敛到 SessionRuntime，由 TUI Model 跨轮持有，每轮与 Conversation 一并交给 Agent。
 */
public final class SessionRuntime {

    public final ContentReplacementState replacement;
    public final Recovery.RecoveryState recovery;
    public final AutoCompactTrackingState autoTracking;
    public volatile SessionContext session;   // /resume 恢复时替换为新会话
    public volatile int contextWindow;
    /** Hook 引擎（阶段11，可空 = 未装配 hook）；由 Main 装配后设置。 */
    public volatile com.cortex.hook.HookEngine hookEngine;
    /** hook prompt 动作注入的 reminder 队列：仅本轮有效，streamOnce 取走后清空（F20/F21/N4）。 */
    private final java.util.List<String> pendingReminders =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private final ReentrantLock anchorLock = new ReentrantLock();
    private long usageAnchor;     // 上一次主对话路径 Stream 真实 usage 之和；摘要请求不更新此字段
    private int anchorMsgLen;     // anchor 当时 conversation.size()，下次估算只算这之后的字符增量
    private long turnCount;       // 已完成的自然回合数（ch09 记忆更新触发用）

    public SessionRuntime(ContentReplacementState replacement, Recovery.RecoveryState recovery,
                          AutoCompactTrackingState autoTracking, SessionContext session, int contextWindow) {
        this.replacement = replacement;
        this.recovery = recovery;
        this.autoTracking = autoTracking;
        this.session = session;
        this.contextWindow = contextWindow;
    }

    /** 测试 / 未注入场景的空 runtime：所有压缩状态为空、会话目录落在系统临时目录。 */
    public static SessionRuntime empty(int contextWindow) {
        try {
            Path tmp = Files.createTempDirectory("cortex-session");
            return new SessionRuntime(new ContentReplacementState(), new Recovery.RecoveryState(),
                    new AutoCompactTrackingState(), SessionContext.create(tmp), contextWindow);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public long getUsageAnchor() {
        anchorLock.lock();
        try {
            return usageAnchor;
        } finally {
            anchorLock.unlock();
        }
    }

    public int getAnchorMsgLen() {
        anchorLock.lock();
        try {
            return anchorMsgLen;
        } finally {
            anchorLock.unlock();
        }
    }

    public void updateAnchor(long anchor, int msgLen) {
        anchorLock.lock();
        try {
            this.usageAnchor = anchor;
            this.anchorMsgLen = msgLen;
        } finally {
            anchorLock.unlock();
        }
    }

    /** 已完成的自然回合数（ch09 记忆更新每 5 轮触发用）。 */
    public long getTurnCount() {
        anchorLock.lock();
        try {
            return turnCount;
        } finally {
            anchorLock.unlock();
        }
    }

    public void bumpTurnCount() {
        anchorLock.lock();
        try {
            turnCount++;
        } finally {
            anchorLock.unlock();
        }
    }

    /**
     * /clear 开新会话时调用：重置压缩三态（决策账本/恢复快照/熔断计数）、
     * usageAnchor 与回合数，并把会话上下文指向新 session。contextWindow 保留。
     */
    public void resetForNewSession(SessionContext newSession) {
        replacement.reset();
        recovery.reset();
        autoTracking.reset();
        anchorLock.lock();
        try {
            usageAnchor = 0;
            anchorMsgLen = 0;
            turnCount = 0;
        } finally {
            anchorLock.unlock();
        }
        pendingReminders.clear();
        if (hookEngine != null) {
            hookEngine.resetForNewSession();
        }
        this.session = newSession;
    }

    /** 追加 hook 注入的 reminder（F20：按 hook 声明顺序拼接）。 */
    public void appendReminders(List<String> prompts) {
        if (prompts != null && !prompts.isEmpty()) {
            pendingReminders.addAll(prompts);
        }
    }

    /** 取走全部 pending reminder 并清空（F21：仅本轮有效）；在组装 LLM 请求 reminder 时调用。 */
    public List<String> takeReminders() {
        synchronized (pendingReminders) {
            if (pendingReminders.isEmpty()) {
                return List.of();
            }
            List<String> taken = List.copyOf(pendingReminders);
            pendingReminders.clear();
            return taken;
        }
    }
}
