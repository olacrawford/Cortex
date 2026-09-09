package com.cortex.hook;

import com.cortex.agent.CancelToken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hook 引擎（F31/G1）：按声明顺序把事件分派给匹配的规则，返回拦截判定与待注入 prompt。
 * <ul>
 *   <li>拦截类事件下首个命中拦截信号的同步 hook 中断后续分派（N3：同步串行）</li>
 *   <li>async 规则起 virtual thread 后台执行，不参与拦截判定与 prompt 注入（F28）</li>
 *   <li>only_once 命中执行后记入内存集合，{@link #resetForNewSession} 清空（F27/N5）</li>
 *   <li>hook 失败只 stderr 一行日志，不中断分派（G9/F29）</li>
 * </ul>
 */
public final class HookEngine {

    private final List<HookRule> rules;
    private final List<String> sources;
    private final HookExecutor executor;
    private final ReentrantLock onceLock = new ReentrantLock();
    private final Set<String> onceFired = new HashSet<>();

    public HookEngine(List<HookRule> rules, List<String> sources, HookExecutor executor) {
        this.rules = List.copyOf(rules);
        this.sources = List.copyOf(sources);
        this.executor = executor;
    }

    public List<HookRule> rules() {
        return rules;
    }

    public List<String> sources() {
        return sources;
    }

    /**
     * 事件分派主流程（F31）：过滤事件 → only_once 跳过 → 条件求值 → 执行 → 结果整合。
     * cancel 支持（N2）：规则间与同步执行内均检查，取消时立即返回已累计的结果。
     */
    public DispatchResult dispatch(Event event, Payload payload, CancelToken cancel) {
        boolean blocked = false;
        String reason = null;
        String blockingHook = null;
        List<String> prompts = new ArrayList<>();
        for (HookRule rule : rules) {
            if (rule.event() != event) {
                continue;
            }
            if (cancel != null && cancel.isCancelled()) {
                break;
            }
            if (rule.onlyOnce() && alreadyFired(rule.name())) {
                continue;
            }
            if (!ConditionEvaluator.evaluate(rule.condition(), payload)) {
                continue;
            }
            if (rule.async()) {
                // async：后台 virtual thread 执行，不等结果、不参与拦截与注入（F28）
                Thread.ofVirtual().name("hook-" + rule.name()).start(() ->
                        executor.run(rule, payload, false, null));
                markFiredIfOnce(rule);
                continue;
            }
            ExecutionResult result = executor.run(rule, payload, event.isBlocking(), cancel);
            if (result.error() != null) {
                System.err.printf("[hook %s] %s failed: %s%n",
                        rule.name(), event.wireName(), result.error().getMessage());
                markFiredIfOnce(rule);
                continue;
            }
            if (result.prompt() != null) {
                prompts.add(result.prompt());
            }
            if (result.blocked() && event.isBlocking() && !blocked) {
                blocked = true;
                reason = result.reason();
                blockingHook = rule.name();
                markFiredIfOnce(rule);
                break; // 拦截后中断后续（F32）
            }
            markFiredIfOnce(rule);
        }
        return new DispatchResult(blocked, reason, blockingHook, List.copyOf(prompts));
    }

    /** 便捷重载：无取消信号。 */
    public DispatchResult dispatch(Event event, Payload payload) {
        return dispatch(event, payload, null);
    }

    /** only_once 重置（/clear、/resume 切换会话时调用，F27/N5）。 */
    public void resetForNewSession() {
        onceLock.lock();
        try {
            onceFired.clear();
        } finally {
            onceLock.unlock();
        }
    }

    private boolean alreadyFired(String name) {
        onceLock.lock();
        try {
            return onceFired.contains(name);
        } finally {
            onceLock.unlock();
        }
    }

    private void markFiredIfOnce(HookRule rule) {
        if (!rule.onlyOnce()) {
            return;
        }
        onceLock.lock();
        try {
            onceFired.add(rule.name());
        } finally {
            onceLock.unlock();
        }
    }
}
