package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolDef;
import com.cortex.llm.ToolResult;
import com.cortex.tool.Result;
import com.cortex.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单轮闭环编排（F5/F6）：请求#1（带工具）→ 收集工具调用 → 注册中心执行 →
 * 结果回灌 → 请求#2（续答）→ 最终文本答复 → 停。
 * 保证单轮上限（AC9）：续答请求即使再次请求工具，也不再执行。
 */
public final class Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient client;
    private final ToolRegistry registry;

    public Agent(LlmClient client, ToolRegistry registry) {
        this.client = client;
        this.registry = registry;
    }

    /**
     * 执行单轮闭环，返回事件队列。内部用虚拟线程驱动整条链路；
     * 订阅方（TUI）逐条 poll，直到 Done / Failed。
     */
    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        BlockingQueue<AgentEvent> out = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("agent-turn").start(() -> {
            try {
                turn(conv, out);
            } catch (Exception e) {
                put(out, new AgentEvent.Failed(e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
        return out;
    }

    private void turn(ConversationManager conv, BlockingQueue<AgentEvent> out) throws InterruptedException {
        List<ToolDef> defs = registry.definitions();

        // ── 请求#1：可能带工具调用 ──
        Once first = streamOnce(conv, defs, out);
        if (first.error() != null) {
            put(out, new AgentEvent.Failed(first.error()));
            return;
        }
        if (first.calls().isEmpty()) {
            conv.addAssistantMessage(first.text());
            put(out, new AgentEvent.Done());
            return;
        }

        // ── 有工具调用：记历史 → 顺序执行 → 结果回灌 ──
        conv.addAssistantWithToolCalls(first.text(), first.calls());
        List<ToolResult> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ToolCall call : first.calls()) {
                put(out, new AgentEvent.Tool(new ToolEvent(
                        call.name(), preview(call.args()), Phase.START, "", false)));
                Result result = executeWithTimeout(call);
                put(out, new AgentEvent.Tool(new ToolEvent(
                        call.name(), preview(call.args()), Phase.END, result.content(), result.isError())));
                results.add(new ToolResult(call.id(), result.content(), result.isError()));
            }
        }
        conv.addToolResults(results);

        // ── 请求#2：续答。忽略其再次请求的工具调用（单轮上限，AC9）──
        Once second = streamOnce(conv, defs, out);
        if (second.error() != null) {
            put(out, new AgentEvent.Failed(second.error()));
            return;
        }
        String finalText = second.text().isBlank()
                ? "（工具结果已回灌；本章为单轮工具模式，不再发起新一轮工具调用。）"
                : second.text();
        if (second.text().isBlank()) {
            // 续答为空时 UI 也展示占位提示，避免答复区空白
            put(out, new AgentEvent.Text(finalText));
        }
        conv.addAssistantMessage(finalText);
        put(out, new AgentEvent.Done());
    }

    /** 注册中心执行 + 外层超时兜底：超时/执行异常都包成 error 结果回灌，不中断会话。 */
    private Result executeWithTimeout(ToolCall call) throws InterruptedException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Result> future = executor.submit(() -> registry.execute(call.name(), call.args()));
            try {
                return future.get(ToolRegistry.DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return Result.error("工具执行超时");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                return Result.error("工具执行异常: " + (cause != null ? cause.getMessage() : e.getMessage()));
            }
        }
    }

    /** 一次流式请求：转发文本增量、收集完整工具调用，直到流结束。 */
    private Once streamOnce(ConversationManager conv, List<ToolDef> defs, BlockingQueue<AgentEvent> out)
            throws InterruptedException {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        BlockingQueue<StreamEvent> queue = client.stream(conv, defs);
        while (true) {
            StreamEvent ev = queue.take();
            if (ev instanceof StreamEvent.TextDelta d) {
                text.append(d.text());
                put(out, new AgentEvent.Text(d.text()));
            } else if (ev instanceof StreamEvent.ToolCallComplete c) {
                calls.add(new ToolCall(c.toolId(), c.toolName(), c.arguments()));
            } else if (ev instanceof StreamEvent.StreamEnd) {
                break;
            } else if (ev instanceof StreamEvent.Error e) {
                return new Once(text.toString(), calls, e.message());
            }
            // ThinkingDelta 接收即丢弃
        }
        return new Once(text.toString(), calls, null);
    }

    private record Once(String text, List<ToolCall> calls, String error) {}

    private static void put(BlockingQueue<AgentEvent> out, AgentEvent event) {
        try {
            out.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
