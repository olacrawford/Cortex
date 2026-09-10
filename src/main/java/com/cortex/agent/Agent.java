package com.cortex.agent;

import com.cortex.compact.CompactConstants;
import com.cortex.compact.ContextCompactor;
import com.cortex.compact.Token;
import com.cortex.conversation.ConversationManager;
import com.cortex.conversation.Message;
import com.cortex.hook.DispatchResult;
import com.cortex.hook.Event;
import com.cortex.hook.Payload;
import com.cortex.llm.LlmClient;
import com.cortex.llm.PromptTooLongException;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.SystemPrompt;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolDef;
import com.cortex.llm.ToolResult;
import com.cortex.memory.Manager;
import com.cortex.permission.Decision;
import com.cortex.permission.Mode;
import com.cortex.permission.Outcome;
import com.cortex.permission.PermissionEngine;
import com.cortex.prompt.Environment;
import com.cortex.prompt.Prompt;
import com.cortex.prompt.Reminder;
import com.cortex.tool.Result;
import com.cortex.tool.ToolContext;
import com.cortex.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ReAct 循环编排（F1/F2）：带工具发起请求 → 流式收集 → 有工具调用则执行并回灌，
 * 进入下一轮；纯文本即最终答复，循环结束。停止条件：自然完成、迭代上限（兜底）、
 * 用户取消、连续未知工具、流出错。任何终止路径都保证对话历史配对合法（F6）。
 * <p>
 * ch08：主循环集成上下文管理（每轮请求前 manageContext）、ReadFile 文件追踪、
 * PTL 紧急压缩 + 一次性重试、手动 /compact 的 runForceCompact 互斥。
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
    private final SessionRuntime runtime;
    private final ReentrantLock runLock = new ReentrantLock();

    private Manager memMgr;                 // ch09 记忆管理器（可空：未注入则不触发）
    private String instructionText = "";    // 项目指令（custom-instructions 模块）
    private String memoryText = "";         // 长期记忆索引（long-term-memory 模块）

    // ─── 阶段12 SubAgent 扩展（子 Agent 专用；主 Agent 均取默认值）───
    private final String systemPromptOverride;   // 非空时覆盖默认系统提示（角色正文）
    private final int maxTurns;                  // 最大迭代轮数；0 = MAX_ITERATIONS
    private final Mode subAgentMode;             // 子 Agent 权限模式
    private final boolean subAgentModeSet;       // 区分「未设置」与 DEFAULT
    private final boolean dontAsk;               // 规则未命中的 Ask 决策自动放行
    private final ApprovalUpgrader approvalUpgrader; // 审批升级到父 TUI 的回调（可空）
    private volatile java.util.Set<String> allowedTools; // 工具白名单；空 = 不收窄（F30）
    private final boolean forkContext;           // Fork/skill-fork 子 Agent 标记（嵌套阻断 QuerySource）
    // ─── 阶段14 Team 扩展 ───
    private volatile TeammateContext teammateContext; // 队员上下文（Team spawn 注入；可空）
    private volatile Mode overrideMode;          // plan 审批通过后的模式切换（TeamMailboxIngestor）
    /** 当前 run/runToCompletion 的对话；Agent 工具 fork 时取父消息用（F22）。 */
    private volatile ConversationManager activeConv;
    /** 工具执行现场的调用方 Agent（QuerySource 嵌套检测，F24）；executeTool 包装器设置/清理。 */
    private static final ThreadLocal<Agent> CURRENT_CALLER = new ThreadLocal<>();

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine engine) {
        this(client, registry, version, engine, SessionRuntime.empty(200000));
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine engine,
                 SessionRuntime runtime) {
        this.client = client;
        this.registry = registry;
        this.version = version == null ? "" : version;
        this.engine = engine;
        this.runtime = runtime;
        this.systemPromptOverride = null;
        this.maxTurns = 0;
        this.subAgentMode = Mode.DEFAULT;
        this.subAgentModeSet = false;
        this.dontAsk = false;
        this.approvalUpgrader = null;
        this.allowedTools = java.util.Set.of();
        this.forkContext = false;
    }

    private Agent(Builder b) {
        this.client = b.client;
        this.registry = b.registry;
        this.version = b.version == null ? "" : b.version;
        this.engine = b.engine;
        this.runtime = b.runtime;
        this.systemPromptOverride = b.systemPrompt;
        this.maxTurns = Math.max(0, b.maxTurns);
        this.subAgentMode = b.permissionMode;
        this.subAgentModeSet = b.permissionModeSet;
        this.dontAsk = b.dontAsk;
        this.approvalUpgrader = b.approvalUpgrader;
        this.allowedTools = java.util.Set.copyOf(b.allowedTools);
        this.forkContext = b.forkContext;
    }

    /** 子 Agent 构造器（阶段12）：主 Agent 沿用既有构造函数，不受影响。 */
    public static Builder builder(LlmClient client, ToolRegistry registry, String version,
                                  PermissionEngine engine, SessionRuntime runtime) {
        return new Builder(client, registry, version, engine, runtime);
    }

    /** Builder 选项：既有五参 + 阶段12 新增 systemPrompt/maxTurns/permissionMode/dontAsk/approvalUpgrader/allowedTools/forkContext。 */
    public static final class Builder {
        private final LlmClient client;
        private final ToolRegistry registry;
        private final String version;
        private final PermissionEngine engine;
        private final SessionRuntime runtime;
        private String systemPrompt;
        private int maxTurns;
        private Mode permissionMode = Mode.DEFAULT;
        private boolean permissionModeSet;
        private boolean dontAsk;
        private ApprovalUpgrader approvalUpgrader;
        private java.util.Set<String> allowedTools = java.util.Set.of();
        private boolean forkContext;

        private Builder(LlmClient client, ToolRegistry registry, String version,
                        PermissionEngine engine, SessionRuntime runtime) {
            this.client = client;
            this.registry = registry;
            this.version = version;
            this.engine = engine;
            this.runtime = runtime;
        }

        /** 子 Agent 角色系统提示；非空时覆盖默认主 Agent 系统提示（F10）。 */
        public Builder systemPrompt(String text) {
            this.systemPrompt = text;
            return this;
        }

        /** 最大迭代轮数；&lt;=0 表示用全局 MAX_ITERATIONS。 */
        public Builder maxTurns(int n) {
            this.maxTurns = n;
            return this;
        }

        /** 子 Agent 启动权限模式（F10）；未设置时 runToCompletion 用 DEFAULT。 */
        public Builder permissionMode(Mode m) {
            this.permissionMode = m;
            this.permissionModeSet = true;
            return this;
        }

        /** dontAsk 兜底：规则未命中的 Ask 决策自动放行（F4/F12）。 */
        public Builder dontAsk(boolean enabled) {
            this.dontAsk = enabled;
            return this;
        }

        /** 审批升级回调：返回非 empty Outcome 即接管，empty 则走默认 emit Approval 路径（F13）。 */
        public Builder approvalUpgrader(ApprovalUpgrader fn) {
            this.approvalUpgrader = fn;
            return this;
        }

        /** 工具白名单（F30 过滤产物）；空集合 = 不收窄。 */
        public Builder allowedTools(java.util.Set<String> allowed) {
            this.allowedTools = allowed == null ? java.util.Set.of() : allowed;
            return this;
        }

        /** Fork/skill-fork 子 Agent 标记：嵌套阻断 QuerySource 闸用（F24）。 */
        public Builder forkContext(boolean fork) {
            this.forkContext = fork;
            return this;
        }

        public Agent build() {
            return new Agent(this);
        }
    }

    // ─── 阶段12：子 Agent 装配辅助（AgentTool / LaunchFork 同包使用）───

    /** 共享的 LLM 客户端（基础设施共享，F11）。 */
    LlmClient client() {
        return client;
    }

    ToolRegistry registry() {
        return registry;
    }

    String version() {
        return version;
    }

    PermissionEngine engine() {
        return engine;
    }

    SessionRuntime runtime() {
        return runtime;
    }

    String instructionText() {
        return instructionText;
    }

    String memoryText() {
        return memoryText;
    }

    /** 是否 Fork/skill-fork 子 Agent（嵌套阻断 QuerySource 闸）。 */
    boolean isForkContext() {
        return forkContext;
    }

    /** 当前 run 的对话（Agent 工具构造 Fork 子对话时取父消息；无 run 时 null）。 */
    ConversationManager currentConversation() {
        return activeConv;
    }

    // ─── 阶段13：explicit cwd（ToolContext）───

    private volatile ToolContext toolContext = ToolContext.EMPTY;

    /** 设置本 Agent 工具调用的 explicit cwd（isolation:worktree 子 Agent 与 /worktree enter 用，F18）。 */
    public void setToolContext(ToolContext ctx) {
        this.toolContext = ctx == null ? ToolContext.EMPTY : ctx;
    }

    // ─── 阶段14 Team ───

    /** 注入队员上下文（TeamManager.spawnTeammate 用）；null 清除。 */
    public void setTeammateContext(TeammateContext ctx) {
        this.teammateContext = ctx;
    }

    public TeammateContext teammateContext() {
        return teammateContext;
    }

    /** 运行中收窄 allowedTools（Coordinator Mode 的 Lead 收窄用，N8 单向）。 */
    public void setAllowedTools(java.util.Set<String> allowed) {
        this.allowedTools = allowed == null ? java.util.Set.of() : java.util.Set.copyOf(allowed);
    }

    /** 追加系统提示段（Coordinator Mode 提示词，F54）；多次调用按序拼接。 */
    public void appendSystemPrompt(String extra) {
        if (extra != null && !extra.isBlank()) {
            this.appendedSystemPrompt.append(extra.strip()).append("\n\n");
        }
    }

    private final StringBuilder appendedSystemPrompt = new StringBuilder();

    /** plan 审批通过后的权限模式切换（TeamMailboxIngestor 调用，F44/T32）。 */
    public void setPermissionMode(Mode m) {
        this.overrideMode = m;
    }

    ToolContext toolContext() {
        return toolContext;
    }

    /** 工具执行现场的调用方 Agent；非 Agent 循环线程返回 null。 */
    static Agent currentCaller() {
        return CURRENT_CALLER.get();
    }

    /** 以本 Agent 为调用方执行工具（QuerySource 闸 + explicit cwd 的数据来源）；包装 registry.execute。 */
    private Result executeAsCaller(com.cortex.llm.ToolCall call) {
        CURRENT_CALLER.set(this);
        try {
            return registry.execute(call.name(), toolContext, call.args());
        } finally {
            CURRENT_CALLER.remove();
        }
    }

    /** 注入记忆管理器与系统提示的指令 / 记忆文本（ch09；由 Cortex 装配时调用）。 */
    public void setMemory(Manager memMgr, String instructionText, String memoryText) {
        this.memMgr = memMgr;
        this.instructionText = instructionText == null ? "" : instructionText;
        this.memoryText = memoryText == null ? "" : memoryText;
    }

    /**
     * 执行 Agent Loop，返回事件队列。内部用虚拟线程驱动整条循环；
     * 订阅方（TUI）逐条 poll，直到 Done（任何结束路径的终止哨兵）。
     * 入口先持有 runLock，保证 run 与 runForceCompact 不并发触发 manageContext。
     */
    public BlockingQueue<AgentEvent> run(ConversationManager conv, Mode mode, CancelToken cancel) {
        BlockingQueue<AgentEvent> out = new LinkedBlockingQueue<>();
        activeConv = conv; // 阶段12：Agent 工具 fork 时读取父消息（F22）
        // 阶段14：Coordinator 提示词等追加段（activate 时注入，跨轮生效）
        Thread.ofVirtual().name("agent-loop").start(() -> {
            runLock.lock();
            try {
                loop(conv, mode, cancel, out);
            } catch (Exception e) {
                // 未预期异常：发 Failed（正常路径已发过的会由 TUI 幂等处理）
                emitFailed(mode, cancel, out, e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                runLock.unlock();
                // 终止哨兵：绕过 emit 的取消检查，保证 TUI 总能收到结束信号回空闲态
                putDirect(out, new AgentEvent.Done());
            }
        });
        return out;
    }

    // ─── Hook 分派（阶段11 F31）───

    /**
     * 事件分派 + 注入 prompt 入队：11 个 emit 点的统一入口。
     * hookEngine 未装配时零开销返回 empty；注入的 prompt 进 runtime reminder 队列，
     * 下一次 streamOnce 拼进 reminder 串（F33/N4）。
     */
    private DispatchResult dispatchHook(Event event, Mode mode, CancelToken cancel,
                                        Map<String, Object> extras) {
        com.cortex.hook.HookEngine engine = runtime.hookEngine;
        if (engine == null) {
            return DispatchResult.empty();
        }
        Map<String, Object> data = new java.util.TreeMap<>();
        data.put("event", event.wireName());
        data.put("session_id", runtime.session != null ? runtime.session.sessionId() : "");
        data.put("cwd", System.getProperty("user.dir"));
        data.put("mode", mode == null ? "default" : mode.displayName());
        if (extras != null) {
            data.putAll(extras);
        }
        DispatchResult result = engine.dispatch(event, new Payload(data), cancel);
        runtime.appendReminders(result.injectedPrompts());
        return result;
    }

    /** 工具参数 JSON → Map（PreToolUse/PostToolUse payload 的 tool_input 字段）；解析失败为空 Map。 */
    private Map<String, Object> toolInputMap(ToolCall call) {
        try {
            return MAPPER.readValue(call.args() == null || call.args().isBlank() ? "{}" : call.args(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** PreToolUse 拦截时的回灌结果：content=[hook <name>] <reason>、isError=true（F32）。 */
    private String hookBlockedText(String hookName, String reason) {
        return "[hook " + hookName + "] " + reason;
    }

    /** Failed 事件统一出口：先发 NOTIFICATION（kind=stream_error）再发 Failed（F9）。 */
    private void emitFailed(Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out, String message) {
        dispatchHook(Event.NOTIFICATION, mode, cancel,
                Map.of("kind", "stream_error", "detail", message == null ? "" : message));
        emit(out, cancel, new AgentEvent.Failed(message));
    }

    /** 手动 /compact 专用：等主循环空闲后执行一次 forceCompact，返回前后 token。 */
    public ForceCompactResult runForceCompact(ConversationManager conv, List<ToolDef> defs) {
        runLock.lock();
        try {
            long anchor = runtime.getUsageAnchor();
            int anchorLen = runtime.getAnchorMsgLen();
            int cw = runtime.contextWindow;
            long est = Token.estimateTokens(anchor, conv.getMessages(), anchorLen);
            ContextCompactor.Input in = new ContextCompactor.Input(conv, client, cw, defs,
                    runtime.replacement, runtime.recovery, runtime.autoTracking, runtime.session,
                    anchor, anchorLen, est, ContextCompactor.TriggerKind.MANUAL);
            // PreCompact：trigger=manual（F9，手动路径合并接入）
            dispatchHook(Event.PRE_COMPACT, null, null, Map.of("trigger", "manual"));
            try {
                ContextCompactor.CompactMsg msg = ContextCompactor.manage(in);
                dispatchHook(Event.POST_COMPACT, null, null, Map.of(
                        "trigger", "manual",
                        "before_tokens", msg.beforeTokens(),
                        "after_tokens", msg.afterTokens()));
                return new ForceCompactResult(msg.beforeTokens(), msg.afterTokens(), null);
            } catch (com.cortex.compact.CompactException e) {
                return new ForceCompactResult(0, 0, e);
            }
        } finally {
            runLock.unlock();
        }
    }

    public record ForceCompactResult(long before, long after, Throwable error) {}

    // ─── ReAct 主循环 ───

    private void loop(ConversationManager conv, Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out)
            throws InterruptedException {
        // 环境信息（不缓存）与稳定系统提示（可缓存）在 run 起始构造一次，跨轮复用（F2/F3/N1）
        String envText = Environment.gather(this.version, "").render();
        String sys = appendedSystemPrompt.isEmpty()
                ? Prompt.buildSystemPrompt(instructionText, memoryText)
                : Prompt.buildSystemPrompt(instructionText, memoryText) + "\n\n" + appendedSystemPrompt.toString().strip();
        List<ToolDef> defs = definitionsFor(mode);

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

            StreamOutcome once;
            boolean emergencyRetried = false;
            try {
                once = roundRequest(conv, sys, envText, defs, reminder, mode, cancel, out);
            } catch (com.cortex.compact.CompactException ce) {
                emitFailed(mode, cancel, out, ce.getMessage());
                ensureAssistantTail(conv, NOTICE_STREAM_ERR);
                return;
            } catch (StreamException se) {
                if (se.getCause() instanceof PromptTooLongException && !emergencyRetried) {
                    once = emergencyCompact(conv, sys, envText, defs, reminder, mode, cancel, out, se);
                    if (once == null) {
                        return;
                    }
                    emergencyRetried = true;
                } else {
                    emitFailed(mode, cancel, out, se.getMessage());
                    ensureAssistantTail(conv, NOTICE_STREAM_ERR);
                    return;
                }
            }

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
                maybeUpdateMemory(conv);
                // Stop 事件：自然停止后、Done emit 之前；取消与出错路径不触发（F9）
                dispatchHook(Event.STOP, mode, cancel, Map.of("iter", iter));
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

    // ─── 阶段12：子 Agent「跑到底」循环（F9/F10/G5）───

    /** 触达 maxTurns 时抛出；lastAssistantText 供调用方（Manager）回灌部分结果。 */
    public static final class MaxTurnsReachedException extends RuntimeException {
        private final String lastAssistantText;

        public MaxTurnsReachedException(String message, String lastAssistantText) {
            super(message);
            this.lastAssistantText = lastAssistantText;
        }

        public String lastAssistantText() {
            return lastAssistantText;
        }
    }

    /**
     * 子 Agent 的非交互「跑到底」执行（G5）：task 作为 user 消息追加（空串跳过——Fork 路径已预装填），
     * 复用主循环的 roundRequest / streamOnce / executeBatched / 紧急压缩 / hook 分派（F9），
     * 差异：事件写入调用方提供的 events 队列（可 null）；不注入 plan reminder；不触发记忆更新；
     * 结束即返回末尾 assistant 文本；触达 maxTurns 抛 {@link MaxTurnsReachedException}；
     * 取消抛 {@link java.util.concurrent.CancellationException}；流出错/压缩失败抛 RuntimeException。
     * 权限模式取 Builder.permissionMode（未设置则 DEFAULT），并叠加 dontAsk / approvalUpgrader（F12）。
     */
    public String runToCompletion(CancelToken cancel, ConversationManager conv, String task,
                                  BlockingQueue<AgentEvent> events) throws InterruptedException {
        if (task != null && !task.isBlank()) {
            conv.addUserMessage(task);
        }
        activeConv = conv;
        Mode mode = overrideMode != null ? overrideMode
                : (subAgentModeSet ? subAgentMode : Mode.DEFAULT);
        String envText = Environment.gather(this.version, "").render();
        // systemPrompt 非空 = 角色 prompt 覆盖（定义式）；空 = 继承主 Agent 系统提示（Fork，N2 缓存一致）
        String sys = systemPromptOverride == null || systemPromptOverride.isBlank()
                ? Prompt.buildSystemPrompt(instructionText, memoryText)
                : systemPromptOverride;
        List<ToolDef> defs = definitionsFor(mode);
        int turns = maxTurns > 0 ? maxTurns : MAX_ITERATIONS;

        int unknownRun = 0;
        for (int iter = 1; iter <= turns; iter++) {
            if (cancel.isCancelled()) {
                ensureAssistantTail(conv, NOTICE_CANCELLED);
                throw new java.util.concurrent.CancellationException();
            }
            // 阶段14：队员每轮请求 LLM 前读邮箱，未读消息以 <incoming-messages> reminder 注入（F41）
            if (teammateContext != null) {
                TeamMailboxIngestor.ingest(this, teammateContext);
            }
            emit(events, cancel, new AgentEvent.Iter(iter));

            StreamOutcome once;
            boolean emergencyRetried = false;
            try {
                once = roundRequest(conv, sys, envText, defs, "", mode, cancel, events);
            } catch (com.cortex.compact.CompactException ce) {
                ensureAssistantTail(conv, NOTICE_STREAM_ERR);
                throw new RuntimeException("子 Agent 压缩失败: " + ce.getMessage(), ce);
            } catch (StreamException se) {
                if (se.getCause() instanceof PromptTooLongException && !emergencyRetried) {
                    once = emergencyCompact(conv, sys, envText, defs, "", mode, cancel, events, se);
                    if (once == null) {
                        throw new RuntimeException("子 Agent 紧急压缩后仍无法继续");
                    }
                    emergencyRetried = true;
                } else {
                    ensureAssistantTail(conv, NOTICE_STREAM_ERR);
                    throw new RuntimeException("子 Agent 流中断: " + se.getMessage(), se);
                }
            }

            if (once.failed()) {
                ensureAssistantTail(conv, cancel.isCancelled() ? NOTICE_CANCELLED : NOTICE_STREAM_ERR);
                if (cancel.isCancelled()) {
                    throw new java.util.concurrent.CancellationException();
                }
                throw new RuntimeException("子 Agent 请求失败");
            }

            // 自然完成：无工具调用的纯文本即最终答复（G5）
            if (once.calls().isEmpty()) {
                String finalText = ensureFinal(events, cancel, once.text());
                conv.addAssistantMessage(finalText);
                // Stop 事件：与主循环同点位（hook 调度延续，F9；子 Agent 不触发记忆更新）
                dispatchHook(Event.STOP, mode, cancel, Map.of("iter", iter));
                return finalText;
            }

            conv.addAssistantWithToolCalls(once.text(), once.calls());
            unknownRun = allUnknown(once.calls()) ? unknownRun + 1 : 0;
            BatchOutcome batch = executeBatched(once.calls(), mode, cancel, events);
            conv.addToolResults(batch.results());
            if (!batch.completed()) {
                ensureAssistantTail(conv, NOTICE_CANCELLED);
                throw new java.util.concurrent.CancellationException();
            }
            if (unknownRun >= MAX_UNKNOWN_RUN) {
                emit(events, cancel, new AgentEvent.Notice(NOTICE_UNKNOWN_TOOLS));
                ensureAssistantTail(conv, NOTICE_UNKNOWN_TOOLS);
                throw new RuntimeException("子 Agent 连续请求未注册工具，已停止");
            }
        }
        // 触达 maxTurns（F9）：历史收尾后抛异常，末尾 assistant 文本随异常带回
        ensureAssistantTail(conv, NOTICE_MAX_ITER);
        String last = lastAssistantText(conv);
        throw new MaxTurnsReachedException("子 Agent 达到最大轮数 " + turns, last);
    }

    /** 末尾 assistant 消息文本（无则空串）。 */
    private static String lastAssistantText(ConversationManager conv) {
        List<Message> msgs = conv.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == Message.Role.ASSISTANT) {
                String c = msgs.get(i).getContent();
                return c == null ? "" : c;
            }
        }
        return "";
    }

    /**
     * 紧急压缩路径：先发 BEFORE_EMERGENCY，manage(EMERGENCY) 后发 AFTER_EMERGENCY；
     * 重置锚点并重估，能塞下则重试一次 streamOnce，否则上抛。
     */
    private StreamOutcome emergencyCompact(ConversationManager conv, String sys, String envText,
                                           List<ToolDef> defs, String reminder, Mode mode,
                                           CancelToken cancel, BlockingQueue<AgentEvent> out, StreamException firstErr)
            throws InterruptedException {
        emit(out, cancel, new CompactEvent(CompactPhase.BEFORE_EMERGENCY, 0, 0, null));
        // PreCompact：trigger=emergency（F9）
        dispatchHook(Event.PRE_COMPACT, mode, cancel, Map.of("trigger", "emergency"));
        ContextCompactor.CompactMsg msg;
        try {
            long anchor = runtime.getUsageAnchor();
            int anchorLen = runtime.getAnchorMsgLen();
            int cw = runtime.contextWindow;
            long est = Token.estimateTokens(anchor, conv.getMessages(), anchorLen);
            ContextCompactor.Input in = new ContextCompactor.Input(conv, client, cw, defs,
                    runtime.replacement, runtime.recovery, runtime.autoTracking, runtime.session,
                    anchor, anchorLen, est, ContextCompactor.TriggerKind.EMERGENCY);
            msg = ContextCompactor.manage(in);
        } catch (com.cortex.compact.CompactException ce) {
            emit(out, cancel, new CompactEvent(CompactPhase.AFTER_EMERGENCY, 0, 0, ce));
            emitFailed(mode, cancel, out, ce.getMessage());
            ensureAssistantTail(conv, NOTICE_STREAM_ERR);
            return null;
        }
        runtime.updateAnchor(0L, 0);
        long est2 = Token.estimateTokens(0L, conv.getMessages(), 0);
        emit(out, cancel, new CompactEvent(CompactPhase.AFTER_EMERGENCY,
                msg.beforeTokens(), msg.afterTokens(), null));
        dispatchHook(Event.POST_COMPACT, mode, cancel, Map.of(
                "trigger", "emergency",
                "before_tokens", msg.beforeTokens(),
                "after_tokens", msg.afterTokens()));
        if (est2 >= runtime.contextWindow - CompactConstants.MANUAL_SAFETY_MARGIN) {
            emitFailed(mode, cancel, out, firstErr.getMessage());
            ensureAssistantTail(conv, NOTICE_STREAM_ERR);
            return null;
        }
        try {
            return roundRequest(conv, sys, envText, defs, reminder, mode, cancel, out);
        } catch (com.cortex.compact.CompactException ce) {
            emitFailed(null, cancel, out, ce.getMessage());
            ensureAssistantTail(conv, NOTICE_STREAM_ERR);
            return null;
        } catch (StreamException se) {
            emitFailed(mode, cancel, out, se.getMessage());
            ensureAssistantTail(conv, NOTICE_STREAM_ERR);
            return null;
        }
    }

    /** 每轮请求前：manageContext(AUTO) + streamOnce + 更新 usage 锚点。 */
    private StreamOutcome roundRequest(ConversationManager conv, String sys, String envText,
                                       List<ToolDef> defs, String reminder, Mode mode,
                                       CancelToken cancel, BlockingQueue<AgentEvent> out)
            throws InterruptedException, com.cortex.compact.CompactException, StreamException {
        long anchor = runtime.getUsageAnchor();
        int anchorLen = runtime.getAnchorMsgLen();
        int cw = runtime.contextWindow;
        long est = Token.estimateTokens(anchor, conv.getMessages(), anchorLen);
        boolean willSummarize = cw > CompactConstants.SUMMARY_RESERVE + CompactConstants.AUTO_SAFETY_MARGIN
                && est >= cw - CompactConstants.SUMMARY_RESERVE - CompactConstants.AUTO_SAFETY_MARGIN;
        if (willSummarize) {
            emit(out, cancel, new CompactEvent(CompactPhase.BEFORE_AUTO, 0, 0, null));
            // PreCompact：manage 调用之前，trigger=auto（F9）
            dispatchHook(Event.PRE_COMPACT, mode, cancel, Map.of("trigger", "auto"));
        }
        ContextCompactor.Input in = new ContextCompactor.Input(conv, client, cw, defs,
                runtime.replacement, runtime.recovery, runtime.autoTracking, runtime.session,
                anchor, anchorLen, est, ContextCompactor.TriggerKind.AUTO);
        ContextCompactor.CompactMsg msg;
        try {
            msg = ContextCompactor.manage(in);
        } catch (com.cortex.compact.CompactException e) {
            if (willSummarize) {
                emit(out, cancel, new CompactEvent(CompactPhase.AFTER_AUTO, est, est, e));
            }
            throw e;
        }
        if (willSummarize) {
            emit(out, cancel, new CompactEvent(CompactPhase.AFTER_AUTO,
                    msg.beforeTokens(), msg.afterTokens(), null));
            // PostCompact：manage 返回后，带前后 token（F9）
            dispatchHook(Event.POST_COMPACT, mode, cancel, Map.of(
                    "trigger", "auto",
                    "before_tokens", msg.beforeTokens(),
                    "after_tokens", msg.afterTokens()));
        }
        StreamOutcome once = streamOnce(conv, sys, envText, defs, reminder, mode, cancel, out);
        if (once.usage() != null) {
            runtime.updateAnchor(Token.usageAnchor(once.usage()), conv.size());
        }
        return once;
    }

    /** conversation 末尾的 user 消息内容（PreUserMessage payload 用）；无则空串。 */
    private static String lastUserPrompt(ConversationManager conv) {
        List<Message> msgs = conv.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == Message.Role.USER) {
                return msgs.get(i).getContent();
            }
        }
        return "";
    }

    // ─── 工具定义收窄（阶段12 F30/F31）───

    /** 按模式 + allowedTools 白名单导出工具定义；主 Agent（白名单空）与原行为完全一致（N1）。 */
    private List<ToolDef> definitionsFor(Mode mode) {
        List<ToolDef> base = mode == Mode.PLAN ? registry.readOnlyDefinitions() : registry.definitions();
        if (allowedTools.isEmpty()) {
            return base;
        }
        return base.stream().filter(d -> allowedTools.contains(d.name())).toList();
    }

    /** 工具调用闸：不在白名单内的调用不执行、直接回灌错误（模型越权幻觉兜底）。 */
    private boolean toolAllowed(String name) {
        return allowedTools.isEmpty() || allowedTools.contains(name);
    }

    // ─── 流式收集（双路）───

    private record StreamOutcome(String text, List<ToolCall> calls, com.cortex.llm.Usage usage, boolean failed) {}

    /** 一次流式请求：组装 Request（系统两段 + 本轮 reminder）、转发文本、收集完整调用与用量，直到流结束。 */
    private StreamOutcome streamOnce(ConversationManager conv, String sys, String envText,
                                     List<ToolDef> defs, String reminder, Mode mode,
                                     CancelToken cancel, BlockingQueue<AgentEvent> out)
            throws InterruptedException, StreamException {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        com.cortex.llm.Usage usage = null;
        // PreUserMessage：provider.stream 之前，payload 带本轮末尾 user 消息（F9）
        dispatchHook(Event.PRE_USER_MESSAGE, null, cancel,
                Map.of("prompt", lastUserPrompt(conv)));
        // hook prompt 注入与 plan reminder 同轮拼接：hook 注入置于 plan reminder 之后（F20/F33）
        List<String> hookReminders = runtime.takeReminders();
        String fullReminder = hookReminders.isEmpty() ? reminder
                : (reminder.isEmpty() ? String.join("\n\n", hookReminders)
                        : reminder + "\n\n" + String.join("\n\n", hookReminders));
        Request req = new Request(conv.getMessages(), defs, new SystemPrompt(sys, envText), fullReminder);
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
                    if (e.cause() instanceof PromptTooLongException p) {
                        // 上下文过长：抛给主循环走紧急压缩（已累加文本不写回 Conversation）
                        throw new StreamException(p);
                    }
                    emitFailed(mode, cancel, out, e.message());
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
     * ch08：结果回填前对成功的 ReadFile 调用重读纯净字节并写入文件追踪（F19/F19a）。
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
                    recordReadFiles(calls, results);
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
                        recordReadFiles(calls, results);
                        return new BatchOutcome(List.of(results), false);
                    }
                    i = next;
                }
            }
        }
        recordReadFiles(calls, results);
        return new BatchOutcome(List.of(results), true);
    }

    /**
     * 记录成功的 ReadFile 调用：用纯净字节（不带行号前缀）重读一次磁盘写入文件追踪，
     * 作为恢复段的数据源。读盘失败 / 参数解析失败一律吞掉（缺一条无所谓）。
     */
    private void recordReadFiles(List<ToolCall> calls, ToolResult[] results) {
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (!"read_file".equals(call.name())) {
                continue;
            }
            if (results[i] == null || results[i].isError()) {
                continue;
            }
            try {
                Map<String, Object> args = MAPPER.readValue(call.args(), new TypeReference<>() {});
                Object pathObj = args.get("path");
                if (!(pathObj instanceof String path) || path.isBlank()) {
                    continue;
                }
                Path absPath = Path.of(path).toAbsolutePath().normalize();
                String content = Files.readString(absPath, StandardCharsets.UTF_8);
                runtime.recovery.recordFile(absPath.toString(), content);
            } catch (IOException ignored) {
                // 读盘失败吞掉
            } catch (Exception ignored) {
                // 参数解析失败跳过
            }
        }
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
        // PreToolUse：每条只读调用准备执行之前，可拦截（F9/F32）
        boolean[] hookBlocked = new boolean[to - from];
        String[] hookText = new String[to - from];
        for (int k = from; k < to; k++) {
            DispatchResult pre = dispatchHook(Event.PRE_TOOL_USE, mode, cancel,
                    Map.of("tool_name", calls.get(k).name(), "tool_input", toolInputMap(calls.get(k))));
            if (pre.blocked()) {
                hookBlocked[k - from] = true;
                hookText[k - from] = hookBlockedText(pre.blockingHookName(), pre.reason());
            }
        }
        // 前四层判定（黑名单/沙箱/规则/模式兜底；只读兜底恒 Allow）
        PermissionEngine.CheckResult[] checks = new PermissionEngine.CheckResult[to - from];
        for (int k = from; k < to; k++) {
            checks[k - from] = hookBlocked[k - from]
                    ? null // 被 hook 拦截：跳过权限引擎
                    : engine.check(mode, calls.get(k), true);
        }
        // Start 事件按调用序先发（动态区同时列出多个在执行的工具行）
        for (int k = from; k < to; k++) {
            emit(out, cancel, toolEvent(calls.get(k), Phase.START, "", false));
        }
        List<Future<Result>> futures = new ArrayList<>();
        for (int k = from; k < to; k++) {
            if (hookBlocked[k - from] || checks[k - from].decision() == Decision.DENY
                    || !toolAllowed(calls.get(k).name())) {
                futures.add(null); // 被拒项不执行
                continue;
            }
            ToolCall c = calls.get(k);
            futures.add(executor.submit(() -> executeAsCaller(c)));
        }
        // End 事件按调用序逐个落 scrollback；只写各自下标，无竞争（N6）
        for (int k = from; k < to; k++) {
            PermissionEngine.CheckResult cr = checks[k - from];
            Result r;
            if (hookBlocked[k - from]) {
                r = Result.error(hookText[k - from]);
            } else if (cr.decision() == Decision.DENY) {
                r = Result.error(cr.reason());
            } else if (!toolAllowed(calls.get(k).name())) {
                r = Result.error("工具未授权（子 Agent 工具白名单不含 " + calls.get(k).name() + "）");
            } else if (cancel.isCancelled()) {
                futures.get(k - from).cancel(true);
                r = Result.error(NOTICE_CANCELLED);
            } else {
                r = await(futures.get(k - from), cancel, calls.get(k).name());
            }
            results[k] = new ToolResult(calls.get(k).id(), r.content(), r.isError());
            // PostToolUse：拿到 result 之后、PhaseEnd emit 之前（F9；被 Deny/拦截的也触发）
            dispatchHook(Event.POST_TOOL_USE, mode, cancel, Map.of(
                    "tool_name", calls.get(k).name(), "tool_input", toolInputMap(calls.get(k)),
                    "tool_result", abbreviateHookResult(r.content()), "is_error", r.isError()));
            emit(out, cancel, toolEvent(calls.get(k), Phase.END, r.content(), r.isError()));
        }
        return to;
    }

    /** 串行执行单个有副作用调用（含权限判定与人在回路）；返回下一个下标，-1 表示已取消。 */
    private int executeSingle(List<ToolCall> calls, int i, ExecutorService executor, Mode mode,
                              CancelToken cancel, BlockingQueue<AgentEvent> out, ToolResult[] results)
            throws InterruptedException {
        ToolCall call = calls.get(i);
        // PreToolUse：权限引擎 check 之前，可拦截（F9/F32）——被拦截则跳过权限与真实执行
        DispatchResult pre = dispatchHook(Event.PRE_TOOL_USE, mode, cancel,
                Map.of("tool_name", call.name(), "tool_input", toolInputMap(call)));
        if (pre.blocked()) {
            String text = hookBlockedText(pre.blockingHookName(), pre.reason());
            emit(out, cancel, toolEvent(call, Phase.START, "", false));
            results[i] = new ToolResult(call.id(), text, true);
            // PostToolUse：被拦截同样触发，is_error=true（F9）
            dispatchHook(Event.POST_TOOL_USE, mode, cancel, Map.of(
                    "tool_name", call.name(), "tool_input", toolInputMap(call),
                    "tool_result", text, "is_error", true));
            emit(out, cancel, toolEvent(call, Phase.END, text, true));
            return i + 1;
        }
        // 阶段14：工具闸提前到权限判定之前——白名单外的调用直接回灌错误（Coordinator 收窄后
        // 模型幻觉调用 write_file 时不再触发审批弹窗，而是立即可见的越权错误）
        if (!toolAllowed(call.name())) {
            emit(out, cancel, toolEvent(call, Phase.START, "", false));
            String text = "工具未授权（子 Agent 工具白名单不含 " + call.name() + "）";
            results[i] = new ToolResult(call.id(), text, true);
            dispatchHook(Event.POST_TOOL_USE, mode, cancel, Map.of(
                    "tool_name", call.name(), "tool_input", toolInputMap(call),
                    "tool_result", text, "is_error", true));
            emit(out, cancel, toolEvent(call, Phase.END, text, true));
            return i + 1;
        }
        PermissionEngine.CheckResult cr = engine.check(mode, call, false);
        Result r = switch (cr.decision()) {
            case DENY -> {
                emit(out, cancel, toolEvent(call, Phase.START, "", false));
                yield Result.error(cr.reason());
            }
            case ASK -> {
                // 子 Agent dontAsk 兜底（F12-②）：黑名单/沙箱/规则已过，Ask 类直接放行
                if (dontAsk) {
                    emit(out, cancel, toolEvent(call, Phase.START, "", false));
                    Future<Result> autoAllow = executor.submit(() -> executeAsCaller(call));
                    yield await(autoAllow, cancel, call.name());
                }
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
                    Future<Result> future = executor.submit(() -> executeAsCaller(call));
                    yield await(future, cancel, call.name());
                }
            }
            case ALLOW -> {
                emit(out, cancel, toolEvent(call, Phase.START, "", false));
                Future<Result> future = executor.submit(() -> executeAsCaller(call));
                yield await(future, cancel, call.name());
            }
        };
        if (r == null) {
            return -1; // 人在回路等待中被取消
        }
        results[i] = new ToolResult(call.id(), r.content(), r.isError());
        // PostToolUse：拿到 result 之后、PhaseEnd emit 之前（F9）
        dispatchHook(Event.POST_TOOL_USE, mode, cancel, Map.of(
                "tool_name", call.name(), "tool_input", toolInputMap(call),
                "tool_result", abbreviateHookResult(r.content()), "is_error", r.isError()));
        emit(out, cancel, toolEvent(call, Phase.END, r.content(), r.isError()));
        return i + 1;
    }

    /** PostToolUse payload 的结果摘要上限，避免 payload JSON 过大。 */
    private static String abbreviateHookResult(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 2000 ? content : content.substring(0, 2000) + "…";
    }

    /**
     * 第五层人在回路（F8）：发 Approval 事件并阻塞等 TUI 回传用户三选一。
     * 阶段12（F13）：子 Agent 可经 {@code approvalUpgrader} 把请求升级到父 TUI——返回非 empty
     * Outcome 即接管本次判定；empty 则回落默认 emit 路径。
     * 取消兜底：cancel 触发时自动 offer DENY_ONCE 解阻塞（TaskStop 停后台子 Agent 依赖此闸）。
     * 返回 null 表示已取消（中断或 per-turn cancel 已触发）。
     */
    private Outcome requestApproval(ToolCall call, String reason, CancelToken cancel,
                                    BlockingQueue<AgentEvent> out) throws InterruptedException {
        if (cancel.isCancelled()) {
            return null;
        }
        BlockingQueue<Outcome> respond = new ArrayBlockingQueue<>(1);
        ApprovalRequest request = new ApprovalRequest(call.name(), preview(call.args()), reason, respond);
        cancel.onCancel(() -> respond.offer(Outcome.DENY_ONCE));
        // Notification：权限 Ask 弹出审批时（F9）
        dispatchHook(Event.NOTIFICATION, null, cancel,
                Map.of("kind", "approval", "detail", call.name()));
        if (approvalUpgrader != null) {
            java.util.Optional<Outcome> upgraded = approvalUpgrader.upgrade(request);
            if (upgraded.isPresent()) {
                return upgraded.get();
            }
        }
        if (!emit(out, cancel, new AgentEvent.Approval(request))) {
            return null;
        }
        try {
            return respond.take();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** per-tool 超时 + 取消感知的结果等待；工具自身的 timeout() 覆盖全局默认。 */
    private Result await(Future<Result> future, CancelToken cancel, String toolName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + registry.timeoutOf(toolName).toMillis();
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

    /** 发事件；per-turn 取消已触发时返回 false（调用方据此提前收尾）。事件总线为 null（子 Agent 无订阅方）时只反映取消态。 */
    private static boolean emit(BlockingQueue<AgentEvent> bus, CancelToken cancel, AgentEvent event) {
        if (cancel.isCancelled()) {
            return false;
        }
        if (bus == null) {
            return true;
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

    // ─── ch09 记忆更新触发（F35~F42）───

    /**
     * 自然回合结束后触发：每 5 轮或命中记忆信号关键词时，异步发起一次记忆更新（不阻塞主会话）。
     * 仅当注入了 memMgr 且能取到「最后一条 user 到末尾」的最近消息时才触发。
     */
    private void maybeUpdateMemory(ConversationManager conv) {
        if (memMgr == null) {
            return;
        }
        runtime.bumpTurnCount();
        List<Message> recent = lastUserTurn(conv.getMessages());
        if (recent.isEmpty()) {
            return;
        }
        if (runtime.getTurnCount() % 5 == 0 || hasMemorySignal(recent)) {
            memMgr.updateAsync(recent);
        }
    }

    /** 从末尾往回找最后一条 user 消息，返回其到末尾的子列表。 */
    private static List<Message> lastUserTurn(List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == Message.Role.USER) {
                return new ArrayList<>(msgs.subList(i, msgs.size()));
            }
        }
        return List.of();
    }

    /** 记忆信号关键词（大小写不敏感）。 */
    private static boolean hasMemorySignal(List<Message> msgs) {
        for (Message m : msgs) {
            String c = m.getContent();
            if (c == null || c.isEmpty()) {
                continue;
            }
            String low = c.toLowerCase();
            for (String kw : List.of("记住", "记忆", "别忘", "remember", "memo")) {
                if (low.contains(kw.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }
}
