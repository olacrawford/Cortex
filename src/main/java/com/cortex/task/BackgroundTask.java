package com.cortex.task;

import com.cortex.agent.Agent;
import com.cortex.agent.AgentEvent;
import com.cortex.agent.CancelToken;
import com.cortex.agent.SubAgentRun;
import com.cortex.agent.Usage;
import com.cortex.conversation.ConversationManager;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台子 Agent 的完整状态快照（F15）；实现 {@link SubAgentRun} 供前台同步等待。
 * 状态字段 volatile / 并发容器，runner 虚拟线程写、工具与 TUI 线程读。
 */
public final class BackgroundTask implements SubAgentRun {

    final String id;
    final String name;                       // Agent 工具 name 参数，可空
    final Agent subAgent;
    final ConversationManager conv;
    final String task;                       // 初始任务文本（SendMessage 续派不加此字段、直接写 conv）
    final CancelToken cancelToken = new CancelToken();
    final Instant startTime = Instant.now();
    final CountDownLatch done = new CountDownLatch(1);

    volatile Status status = Status.RUNNING;
    volatile String result;
    volatile Throwable err;
    volatile Instant endTime;
    volatile String lastActivity = "";

    final AtomicInteger toolCount = new AtomicInteger();
    // token 用量累计（多轮 UsageReport 求和）
    final AtomicInteger usageIn = new AtomicInteger();
    final AtomicInteger usageOut = new AtomicInteger();
    final AtomicInteger usageCacheW = new AtomicInteger();
    final AtomicInteger usageCacheR = new AtomicInteger();

    BackgroundTask(String id, String name, Agent subAgent, ConversationManager conv, String task) {
        this.id = id;
        this.name = name;
        this.subAgent = subAgent;
        this.conv = conv;
        this.task = task;
    }

    // ─── getters（TaskList/TaskGet 与通知渲染用）───

    public String id() { return id; }
    public String name() { return name; }
    public Status status() { return status; }
    public String result() { return result; }
    public Throwable error() { return err; }
    public Instant startTime() { return startTime; }
    public Instant endTime() { return endTime; }
    public int toolCount() { return toolCount.get(); }
    public String lastActivity() { return lastActivity; }
    public CancelToken cancelToken() { return cancelToken; }

    /** 子对话（SendMessage 续派与调试观察用；只读方请勿直接改写历史）。 */
    public ConversationManager conversation() { return conv; }

    /** 累计 token 用量快照。 */
    public Usage usage() {
        return new Usage(usageIn.get(), usageOut.get(), usageCacheW.get(), usageCacheR.get());
    }

    // ─── SubAgentRun（前台同步等待，F17-②）───

    @Override
    public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
        return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public String resultText() {
        return result;
    }

    @Override
    public boolean failed() {
        return status == Status.FAILED;
    }

    @Override
    public String errorMessage() {
        Throwable t = err;
        return t == null ? "" : (t.getMessage() != null ? t.getMessage() : t.toString());
    }

    // ─── 事件聚合（T20：toolCount / lastActivity / usage）───

    /** runner 线程把子 Agent 事件喂进来；仅 Tool START 与 UsageReport 参与统计。 */
    void onEvent(AgentEvent event) {
        if (event instanceof AgentEvent.Tool t && t.event().phase() == com.cortex.agent.Phase.START) {
            toolCount.incrementAndGet();
            lastActivity = t.event().name();
        } else if (event instanceof AgentEvent.UsageReport u) {
            usageIn.addAndGet((int) Math.min(Integer.MAX_VALUE, u.usage().inputTokens()));
            usageOut.addAndGet((int) Math.min(Integer.MAX_VALUE, u.usage().outputTokens()));
            usageCacheW.addAndGet((int) Math.min(Integer.MAX_VALUE, u.usage().cacheWrite()));
            usageCacheR.addAndGet((int) Math.min(Integer.MAX_VALUE, u.usage().cacheRead()));
        }
    }

    /** 事件列表批量喂入（测试辅助）。 */
    void onEvents(List<AgentEvent> events) {
        for (AgentEvent e : events) {
            onEvent(e);
        }
    }
}
