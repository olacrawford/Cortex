package com.cortex.hook;

import com.cortex.agent.CancelToken;
import com.cortex.permission.Matchers;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HookEngineTest {

    /** 可编程执行器：记录执行顺序并按 name 返回预置结果（HookExecutor.run 同包可覆写）。 */
    private static final class RecordingExecutor extends HookExecutor {
        final List<String> order = new java.util.ArrayList<>();
        final Map<String, ExecutionResult> canned = new HashMap<>();
        final CountDownLatch asyncRan = new CountDownLatch(1);

        @Override
        ExecutionResult run(HookRule rule, Payload payload, boolean blocking, CancelToken cancel) {
            if (rule.async()) {
                asyncRan.countDown();
            }
            order.add(rule.name());
            ExecutionResult r = canned.get(rule.name());
            return r != null ? r : ExecutionResult.empty();
        }
    }

    private static Payload payload(String k, String v) {
        return new Payload(Map.of(k, v));
    }

    private static HookRule rule(String name, Event event, Action action) {
        return new HookRule(name, event, null, action, false, false, Duration.ofSeconds(5), "t");
    }

    private static HookRule rule(String name, Event event, boolean onlyOnce, boolean async, Action action) {
        return new HookRule(name, event, null, action, onlyOnce, async, Duration.ofSeconds(5), "t");
    }

    @Test
    void dispatch_多规则同事件按声明序执行并过滤其它事件() {
        RecordingExecutor ex = new RecordingExecutor();
        HookEngine engine = new HookEngine(List.of(
                rule("a", Event.STOP, new Action.Shell("true")),
                rule("b", Event.STOP, new Action.Shell("true")),
                rule("c", Event.SESSION_START, new Action.Shell("true"))), List.of(), ex);
        DispatchResult r = engine.dispatch(Event.STOP, payload("x", "1"));
        assertFalse(r.blocked());
        assertEquals(List.of("a", "b"), ex.order, "按声明序执行且过滤非本事件规则");
    }

    @Test
    void dispatch_拦截类事件首个blocked中断后续() {
        RecordingExecutor ex = new RecordingExecutor();
        ex.canned.put("first", ExecutionResult.blocked("no"));
        HookEngine engine = new HookEngine(List.of(
                rule("first", Event.PRE_TOOL_USE, new Action.Shell("true")),
                rule("second", Event.PRE_TOOL_USE, new Action.Shell("true"))), List.of(), ex);
        DispatchResult r = engine.dispatch(Event.PRE_TOOL_USE, payload("tool_name", "write_file"));
        assertTrue(r.blocked());
        assertEquals("first", r.blockingHookName());
        assertEquals("no", r.reason());
        assertEquals(List.of("first"), ex.order, "拦截后中断后续（F32）");
    }

    @Test
    void dispatch_非拦截事件blocked不传递() {
        RecordingExecutor ex = new RecordingExecutor();
        ex.canned.put("x", ExecutionResult.blocked("ignored"));
        HookEngine engine = new HookEngine(List.of(
                rule("x", Event.STOP, new Action.Shell("true")),
                rule("y", Event.STOP, new Action.Shell("true"))), List.of(), ex);
        DispatchResult r = engine.dispatch(Event.STOP, payload("k", "v"));
        assertFalse(r.blocked(), "非拦截事件下 blocked 不传递（T13-3）");
        assertEquals(List.of("x", "y"), ex.order);
    }

    @Test
    void dispatch_prompt累加到injectedPrompts() {
        HookEngine engine = new HookEngine(List.of(
                rule("p1", Event.SESSION_START, new Action.Prompt("第一段")),
                rule("p2", Event.SESSION_START, new Action.Prompt("第二段"))), List.of(),
                new HookExecutor());
        DispatchResult r = engine.dispatch(Event.SESSION_START, payload("k", "v"));
        assertEquals(List.of("第一段", "第二段"), r.injectedPrompts(), "F33：按声明顺序拼接");
    }

    @Test
    void dispatch_条件不通过跳过() {
        RecordingExecutor ex = new RecordingExecutor();
        HookRule conditional = new HookRule("cond", Event.PRE_TOOL_USE,
                new Condition(CombineMode.ALL_OF, List.of(
                        new com.cortex.hook.AtomCondition("tool_name",
                                Matchers.compile("=write_file", false)))),
                new Action.Shell("true"), false, false, Duration.ofSeconds(5), "t");
        HookEngine engine = new HookEngine(List.of(conditional), List.of(), ex);
        engine.dispatch(Event.PRE_TOOL_USE, payload("tool_name", "bash"));
        assertTrue(ex.order.isEmpty(), "条件不匹配不执行");
        engine.dispatch(Event.PRE_TOOL_USE, payload("tool_name", "write_file"));
        assertEquals(List.of("cond"), ex.order);
    }

    @Test
    void onlyOnce_首次执行后跳过_reset清空后恢复() {
        RecordingExecutor ex = new RecordingExecutor();
        HookEngine engine = new HookEngine(List.of(
                rule("once-hook", Event.PRE_USER_MESSAGE, true, false, new Action.Shell("true"))),
                List.of(), ex);
        engine.dispatch(Event.PRE_USER_MESSAGE, payload("prompt", "一"));
        engine.dispatch(Event.PRE_USER_MESSAGE, payload("prompt", "二"));
        assertEquals(1, ex.order.size(), "only_once 第二次跳过（F27）");

        engine.resetForNewSession();
        engine.dispatch(Event.PRE_USER_MESSAGE, payload("prompt", "三"));
        assertEquals(2, ex.order.size(), "/clear 与 /resume 后重置（N5）");
    }

    @Test
    void dispatch_async后台执行且不参与拦截与注入() throws Exception {
        RecordingExecutor ex = new RecordingExecutor();
        ex.canned.put("async-hook", ExecutionResult.blocked("should-not-block"));
        ex.canned.put("async-hook", ExecutionResult.prompt("不应注入"));
        HookEngine engine = new HookEngine(List.of(
                rule("async-hook", Event.POST_TOOL_USE, false, true, new Action.Shell("true")),
                rule("after", Event.POST_TOOL_USE, new Action.Shell("true"))), List.of(), ex);
        DispatchResult r = engine.dispatch(Event.POST_TOOL_USE, payload("k", "v"));
        assertTrue(ex.asyncRan.await(2, TimeUnit.SECONDS), "async 规则在 virtual thread 中已执行");
        assertFalse(r.blocked(), "async 不参与拦截判定（F28）");
        assertTrue(r.injectedPrompts().isEmpty(), "async 不进入注入集合（F28）");
    }

    @Test
    void dispatch_hook失败只记日志不中断() {
        RecordingExecutor ex = new RecordingExecutor();
        ex.canned.put("bad", ExecutionResult.failed(new RuntimeException("boom")));
        HookEngine engine = new HookEngine(List.of(
                rule("bad", Event.STOP, new Action.Shell("true")),
                rule("good", Event.STOP, new Action.Shell("true"))), List.of(), ex);
        DispatchResult r = engine.dispatch(Event.STOP, payload("k", "v"));
        assertFalse(r.blocked());
        assertEquals(List.of("bad", "good"), ex.order, "hook 失败不中断分派（G9/F29）");
    }

    @Test
    void dispatch_取消信号立即返回() {
        RecordingExecutor ex = new RecordingExecutor();
        CancelToken cancel = new CancelToken();
        cancel.cancel();
        DispatchResult r = engineDispatchWithCancel(engine(List.of(
                rule("a", Event.STOP, new Action.Shell("true"))), ex), cancel);
        assertTrue(ex.order.isEmpty(), "取消后不执行任何规则（N2）");
        assertFalse(r.blocked());
    }

    private static HookEngine engine(List<HookRule> rules, HookExecutor ex) {
        return new HookEngine(rules, List.of(), ex);
    }

    private static DispatchResult engineDispatchWithCancel(HookEngine engine, CancelToken cancel) {
        return engine.dispatch(Event.STOP, payload("k", "v"), cancel);
    }

    @Test
    void rules与sources访问器() {
        HookEngine engine = new HookEngine(List.of(
                rule("a", Event.STOP, new Action.Shell("true"))), List.of("/x/hooks.yaml"), new HookExecutor());
        assertEquals(1, engine.rules().size());
        assertEquals(List.of("/x/hooks.yaml"), engine.sources());
    }
}
