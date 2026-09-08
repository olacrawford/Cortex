package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.conversation.Message;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.SystemPrompt;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolDef;
import com.cortex.llm.ToolResult;
import com.cortex.permission.Decision;
import com.cortex.permission.Mode;
import com.cortex.permission.Outcome;
import com.cortex.permission.PermissionEngine;
import com.cortex.prompt.Environment;
import com.cortex.prompt.Prompt;
import com.cortex.prompt.Reminder;
import com.cortex.tool.Result;
import com.cortex.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ReAct 循环编排（F1/F2）：带工具发起请求 → 流式收集 → 有工具调用则执行并回灌，
 * 进入下一轮；纯文本即最终答复，循环结束。停止条件：自然完成、迭代上限（兜底）、
 * 用户取消、连续未知工具、流出错。任何终止路径都保证对话历史配对合法（F6）。
 */
public final class Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 内置迭代上限兜底（F2，不可配置）。 */
    static final int MAX_ITERATIONS = 25;
    /** 连续「整轮只产生未知工具调用」的迭代数上限（F2）。 */
    static final int MAX_UNKNOWN_RUN = 3;
    /** 规划模式完整提醒的注入间隔：首轮与每隔此轮数注入完整版，其余轮精简（F7）。 */
    static final int PLAN_REMINDER_INTERVAL = 4;

    // 停止/收尾提示文案——既推给 UI（Event.Notice / 兜底文本），也写入历史收尾。
    static final String NOTICE_MAX_ITER = "（已达最大迭代轮数 25，自动停止；可继续发消息推进。）";
    static final String NOTICE_UNKNOWN_TOOLS = "（连续多轮只请求到未注册的工具，自动停止。）";
    static final String NOTICE_STREAM_ERR = "（请求出错，本轮已中断。）";
    static final String NOTICE_CANCELLED = "（已取消。）";

    private final LlmClient client;
    private final ToolRegistry registry;
    private final String version;
    private final PermissionEngine engine;

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine engine) {
        this.client = client;
        this.registry = registry;
        this.version = version == null ? "" : version;
        this.engine = engine;
    }

    /**
     * 执行 Agent Loop，返回事件队列。内部用虚拟线程驱动整条循环；
     * 订阅方（TUI）逐条 poll，直到 Done（任何结束路径的终止哨兵）。
     */
    public BlockingQueue<AgentEvent> run(ConversationManager conv, Mode mode, CancelToken cancel) {
        BlockingQueue<AgentEvent> out = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("agent-loop").start(() -> {
            try {
                loop(conv, mode, cancel, out);
            } catch (Exception e) {
                // 未预期异常：发 Failed（正常路径已发过的会由 TUI 幂等处理）
                putDirect(out, new AgentEvent.Failed(e.getMessage() != null ? e.getMessage() : e.toString()));
            } finally {
                // 终止哨兵：绕过 emit 的取消检查，保证 TUI 总能收到结束信号回空闲态
                putDirect(out, new AgentEvent.Done());
            }
        });
        return out;
    }

    // ─── ReAct 主循环 ───

    private void loop(ConversationManager conv, Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out)
            throws InterruptedException {
        // 环境信息（不缓存）与稳定系统提示（可缓存）在 run 起始构造一次，跨轮复用（F2/F3/N1）
        String envText = Environment.gather(this.version, "").render();
        String sys = Prompt.buildSystemPrompt();
        List<ToolDef> defs = mode == Mode.PLAN ? registry.readOnlyDefinitions() : registry.definitions();

        int unknownRun = 0;
        for (int iter = 1; iter <= MAX_ITERATIONS; iter++) {
            if (!emit(out, cancel, new AgentEvent.Iter(iter))) {
                ensureAssistantTail(conv, NOTICE_CANCELLED);
                return;
            }

            // 规划模式提醒按轮次注入：首轮与间隔轮完整，其余精简（F7）；普通模式不注入
            String reminder = "";
            if (mode == Mode.PLAN) {
                boolean full = iter == 1 || (iter - 1) % PLAN_REMINDER_INTERVAL == 0;
                reminder = Reminder.plan(full);
            }

            // 请求：流式收集本轮响应（双路——文本实时转发 + 完整调用收集）
            StreamOutcome once = streamOnce(conv, sys, envText, defs, reminder, cancel, out);
            if (once.failed()) {
                // 取消优先于流错误
                ensureAssistantTail(conv, cancel.isCancelled() ? NOTICE_CANCELLED : NOTICE_STREAM_ERR);
                return;
            }
            if (once.usage() != null) {
                emit(out, cancel, new AgentEvent.UsageReport(new Usage(
                        once.usage().inputTokens(), once.usage().outputTokens(),
                        once.usage().cacheWrite(), once.usage().cacheRead())));
            }

            // 自然完成：无工具调用的纯文本即最终答复（F2-1）
            if (once.calls().isEmpty()) {
                conv.addAssistantMessage(ensureFinal(out, cancel, once.text()));
                return;
            }

            // 有工具调用：记历史 → 分批执行 → 结果回灌（含已取消占位，F6）
            conv.addAssistantWithToolCalls(once.text(), once.calls());
            unknownRun = allUnknown(once.calls()) ? unknownRun + 1 : 0;
            BatchOutcome batch = executeBatched(once.calls(), mode, cancel, out);
            conv.addToolResults(batch.results());

            // 执行中取消是最高优先级终止——跳过未知工具与上限检查
            if (!batch.completed()) {
                ensureAssistantTail(conv, NOTICE_CANCELLED);
                return;
            }
            if (unknownRun >= MAX_UNKNOWN_RUN) {
                emit(out, cancel, new AgentEvent.Notice(NOTICE_UNKNOWN_TOOLS));
                ensureAssistantTail(conv, NOTICE_UNKNOWN_TOOLS);
                return;
            }
        }
        // 循环走完 = 触达迭代上限（F2-2）
        emit(out, cancel, new AgentEvent.Notice(NOTICE_MAX_ITER));
        ensureAssistantTail(conv, NOTICE_MAX_ITER);
    }

    // ─── 流式收集（双路）───

    private record StreamOutcome(String text, List<ToolCall> calls, com.cortex.llm.Usage usage, boolean failed) {}

    /** 一次流式请求：组装 Request（系统两段 + 本轮 reminder）、转发文本、收集完整调用与用量，直到流结束。 */
    private StreamOutcome streamOnce(ConversationManager conv, String sys, String envText,
                                     List<ToolDef> defs, String reminder,
                                     CancelToken cancel, BlockingQueue<AgentEvent> out)
            throws InterruptedException {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        com.cortex.llm.Usage usage = null;
        Request req = new Request(conv.getMessages(), defs, new SystemPrompt(sys, envText), reminder);
        BlockingQueue<StreamEvent> queue = client.stream(req);
        while (true) {
            if (cancel.isCancelled()) {
                // 用户取消：立即停止消费当前流（底层请求尽力而为），由 loop 按取消路径收尾
                return new StreamOutcome(text.toString(), calls, usage, true);
            }
            StreamEvent ev = queue.poll(200, TimeUnit.MILLISECONDS);
            if (ev == null) {
                continue;
            }
            switch (ev) {
                case StreamEvent.TextDelta d -> {
                    text.append(d.text());
                    emit(out, cancel, new AgentEvent.Text(d.text()));
                }
                case StreamEvent.ToolCallComplete c ->
                        calls.add(new ToolCall(c.toolId(), c.toolName(), c.arguments()));
                case StreamEvent.UsageEvent u -> usage = u.usage();
                case StreamEvent.StreamEnd s -> {
                    // 取消视为失败，由 loop 按取消路径收尾
                    return new StreamOutcome(text.toString(), calls, usage, cancel.isCancelled());
                }
                case StreamEvent.Error e -> {
                    emit(out, cancel, new AgentEvent.Failed(e.message()));
                    return new StreamOutcome(text.toString(), calls, usage, true);
                }
                case StreamEvent.ThinkingDelta ignored -> {
                    // thinking 增量接收即丢弃
                }
            }
        }
    }

    // ─── 保序分批并发执行（F5）───

    private record BatchOutcome(List<ToolResult> results, boolean completed) {}

    /**
     * 按模型调用顺序扫描：连续只读调用合并为并发批，有副作用调用单独串行。
     * 事件时序：Start 按调用序先发，End 也按调用序后发——并发只发生在执行环节，
     * UI 看到的顺序始终是调用序（N3）。每个工具受 per-tool 超时约束（N1）。
     */
    private BatchOutcome executeBatched(List<ToolCall> calls, Mode mode, CancelToken cancel,
                                        BlockingQueue<AgentEvent> out)
            throws InterruptedException {
        ToolResult[] results = new ToolResult[calls.size()];
        int i = 0;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (i < calls.size()) {
                if (cancel.isCancelled()) {
                    for (int k = i; k < calls.size(); k++) {
                        results[k] = new ToolResult(calls.get(k).id(), NOTICE_CANCELLED, true);
                    }
                    return new BatchOutcome(List.of(results), false);
                }
                ToolCall call = calls.get(i);
                if (registry.isReadOnly(call.name())) {
                    i = executeReadOnlyBatch(calls, i, executor, mode, cancel, out, results);
                } else {
                    int next = executeSingle(calls, i, executor, mode, cancel, out, results);
                    if (next < 0) {
                        // 人在回路等待中被取消：当前项已置为已取消，收尾剩余项
                        for (int k = i + 1; k < calls.size(); k++) {
                            results[k] = new ToolResult(calls.get(k).id(), NOTICE_CANCELLED, true);
                        }
                        return new BatchOutcome(List.of(results), false);
                    }
                    i = next;
                }
            }
        }
        return new BatchOutcome(List.of(results), true);
    }

    /**
     * 并发执行 [from, to) 内的连续只读调用；返回段尾下标。
     * 权限检查逐个进行（N3）：只读永不 Ask；被拒项不进入并发执行，
     * 但 Start/End 事件仍按调用序发出（isError 区分），与其他项互不串位。
     */
    private int executeReadOnlyBatch(List<ToolCall> calls, int from, ExecutorService executor, Mode mode,
                                     CancelToken cancel, BlockingQueue<AgentEvent> out, ToolResult[] results)
            throws InterruptedException {
        int to = from;
        while (to < calls.size() && registry.isReadOnly(calls.get(to).name())) {
            to++;
        }
        // 前四层判定（黑名单/沙箱/规则/模式兜底；只读兜底恒 Allow）
        PermissionEngine.CheckResult[] checks = new PermissionEngine.CheckResult[to - from];
        for (int k = from; k < to; k++) {
            checks[k - from] = engine.check(mode, calls.get(k), true);
        }
        // Start 事件按调用序先发（动态区同时列出多个在执行的工具行）
        for (int k = from; k < to; k++) {
            emit(out, cancel, toolEvent(calls.get(k), Phase.START, "", false));
        }
        List<Future<Result>> futures = new ArrayList<>();
        for (int k = from; k < to; k++) {
            if (checks[k - from].decision() == Decision.DENY) {
                futures.add(null); // 被拒项不执行
                continue;
            }
            ToolCall c = calls.get(k);
            futures.add(executor.submit(() -> registry.execute(c.name(), c.args())));
        }
        // End 事件按调用序逐个落 scrollback；只写各自下标，无竞争（N6）
        for (int k = from; k < to; k++) {
            PermissionEngine.CheckResult cr = checks[k - from];
            Result r;
            if (cr.decision() == Decision.DENY) {
                r = Result.error(cr.reason());
            } else if (cancel.isCancelled()) {
                futures.get(k - from).cancel(true);
                r = Result.error(NOTICE_CANCELLED);
            } else {
                r = await(futures.get(k - from), cancel);
            }
            results[k] = new ToolResult(calls.get(k).id(), r.content(), r.isError());
            emit(out, cancel, toolEvent(calls.get(k), Phase.END, r.content(), r.isError()));
        }
        return to;
    }

    /** 串行执行单个有副作用调用（含权限判定与人在回路）；返回下一个下标，-1 表示已取消。 */
    private int executeSingle(List<ToolCall> calls, int i, ExecutorService executor, Mode mode,
                              CancelToken cancel, BlockingQueue<AgentEvent> out, ToolResult[] results)
            throws InterruptedException {
        ToolCall call = calls.get(i);
        PermissionEngine.CheckResult cr = engine.check(mode, call, false);
        Result r = switch (cr.decision()) {
            case DENY -> {
                emit(out, cancel, toolEvent(call, Phase.START, "", false));
                yield Result.error(cr.reason());
            }
            case ASK -> {
                Outcome o = requestApproval(call, cr.reason(), cancel, out);
                if (o == null) {
                    results[i] = new ToolResult(call.id(), NOTICE_CANCELLED, true);
                    yield null; // 取消：由调用方收尾剩余调用
                }
                if (o == Outcome.ALLOW_FOREVER) {
                    try {
                        engine.persistLocalAllow(call);
                    } catch (IOException e) {
                        emit(out, cancel, new AgentEvent.Notice("永久放行规则写入失败：" + e.getMessage()));
                    }
                }
                if (o == Outcome.DENY_ONCE) {
                    emit(out, cancel, toolEvent(call, Phase.START, "", false));
                    yield Result.error("用户拒绝本次调用");
                } else {
                    emit(out, cancel, toolEvent(call, Phase.START, "", false));
                    Future<Result> future = executor.submit(() -> registry.execute(call.name(), call.args()));
                    yield await(future, cancel);
                }
            }
            case ALLOW -> {
                emit(out, cancel, toolEvent(call, Phase.START, "", false));
                Future<Result> future = executor.submit(() -> registry.execute(call.name(), call.args()));
                yield await(future, cancel);
            }
        };
        if (r == null) {
            return -1; // 人在回路等待中被取消
        }
        results[i] = new ToolResult(call.id(), r.content(), r.isError());
        emit(out, cancel, toolEvent(call, Phase.END, r.content(), r.isError()));
        return i + 1;
    }

    /**
     * 第五层人在回路（F8）：发 Approval 事件并阻塞等 TUI 回传用户三选一。
     * 返回 null 表示已取消（中断或 per-turn cancel 已触发）。
     */
    private Outcome requestApproval(ToolCall call, String reason, CancelToken cancel,
                                    BlockingQueue<AgentEvent> out) throws InterruptedException {
        if (cancel.isCancelled()) {
            return null;
        }
        BlockingQueue<Outcome> respond = new ArrayBlockingQueue<>(1);
        if (!emit(out, cancel, new AgentEvent.Approval(
                new ApprovalRequest(call.name(), preview(call.args()), reason, respond)))) {
            return null;
        }
        try {
            return respond.take();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** per-tool 超时 + 取消感知的结果等待。 */
    private Result await(Future<Result> future, CancelToken cancel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ToolRegistry.DEFAULT_TIMEOUT.toMillis();
        while (true) {
            try {
                return future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                if (cancel.isCancelled()) {
                    future.cancel(true);
                    return Result.error(NOTICE_CANCELLED);
                }
                if (System.currentTimeMillis() > deadline) {
                    future.cancel(true);
                    return Result.error("工具执行超时");
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                return Result.error("工具执行异常: " + (cause != null ? cause.getMessage() : e.getMessage()));
            }
        }
    }

    private AgentEvent.Tool toolEvent(ToolCall call, Phase phase, String result, boolean isError) {
        return new AgentEvent.Tool(new ToolEvent(call.name(), preview(call.args()), phase, result, isError));
    }

    // ─── 辅助 ───

    /** 发事件；per-turn 取消已触发时返回 false（调用方据此提前收尾）。 */
    private static boolean emit(BlockingQueue<AgentEvent> bus, CancelToken cancel, AgentEvent event) {
        if (cancel.isCancelled()) {
            return false;
        }
        try {
            bus.put(event);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 不经取消检查直接投递（终止哨兵专用）。 */
    private static void putDirect(BlockingQueue<AgentEvent> bus, AgentEvent event) {
        try {
            bus.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 连续未知工具判定：整轮全部调用都是未注册工具才计一次空转（混入已知工具即重置）。 */
    private boolean allUnknown(List<ToolCall> calls) {
        return calls.stream().noneMatch(c -> registry.get(c.name()).isPresent());
    }

    /** 最终答复非空原样返回；为空则发占位提示并返回占位文本（空 assistant 回合会破坏下一轮请求）。 */
    private String ensureFinal(BlockingQueue<AgentEvent> out, CancelToken cancel, String text) {
        if (text != null && !text.isBlank()) {
            return text;
        }
        String placeholder = "（任务已完成。）";
        emit(out, cancel, new AgentEvent.Text(placeholder));
        return placeholder;
    }

    /** 保证历史以 assistant 文本回合收尾（取消/出错/上限后角色仍交替，下一轮请求不报 400，F6）。 */
    private void ensureAssistantTail(ConversationManager conv, String fallback) {
        if (conv.lastRole().orElse(null) != Message.Role.ASSISTANT) {
            conv.addAssistantMessage(fallback);
        }
    }

    /** 参数预览：优先取 path/command/pattern 等关键字段，超长截断到 80 字符。 */
    static String preview(String argsJson) {
        try {
            var node = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            for (String key : List.of("path", "command", "pattern", "old_string")) {
                if (node.has(key) && node.get(key).isTextual()) {
                    return abbr(node.get(key).asText());
                }
            }
        } catch (Exception ignored) {
            // 参数不是合法 JSON 时退回原文截断
        }
        return abbr(argsJson == null ? "" : argsJson);
    }

    private static String abbr(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
