package com.cortex.compact;

import com.cortex.compact.state.AutoCompactTrackingState;
import com.cortex.compact.state.ContentReplacementState;
import com.cortex.compact.state.ContentReplacementState.Decision;
import com.cortex.compact.state.ContentReplacementState.DecisionResult;
import com.cortex.compact.state.SessionContext;
import com.cortex.conversation.ConversationManager;
import com.cortex.conversation.Message;
import com.cortex.llm.LlmClient;
import com.cortex.llm.PromptTooLongException;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.SystemPrompt;
import com.cortex.llm.ToolDef;
import com.cortex.llm.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 上下文管理唯一权威入口（ch08）。承担两层压缩：
 * <ul>
 *   <li>第 1 层预防性压缩：每轮请求前对工具结果做幂等「超阈值落盘 + 字符串替换」，决策冻结在账本里。</li>
 *   <li>第 2 层 LLM 摘要 + 恢复：估算 token 触达阈值（或被手动 / 紧急触发）时调用 provider 跑结构化摘要。</li>
 * </ul>
 * 本类不直接持有 Agent / Provider，只通过窄接口与外部交互；自身无状态、可重入。
 */
public final class ContextCompactor {

    private ContextCompactor() {}

    /** 触发方式：自动 / 手动 / 紧急。 */
    public enum TriggerKind { AUTO, MANUAL, EMERGENCY }

    /** manage 的入参，全部字段由调用方（Agent 主循环 / TUI）填充。 */
    public record Input(
            ConversationManager conv,
            LlmClient client,
            int contextWindow,
            List<ToolDef> toolDefs,
            ContentReplacementState replacement,
            Recovery.RecoveryState recovery,
            AutoCompactTrackingState autoTracking,
            SessionContext session,
            long usageAnchor,
            int anchorMsgLen,
            long estimatedToken,
            TriggerKind trigger) {}

    /** manage 的返回值：压缩前后估算 token。 */
    public record CompactMsg(long beforeTokens, long afterTokens) {}

    /** autoCompact / forceCompact 的返回值：新消息列表 + 压缩前后 token。 */
    public record CompactResult(List<Message> newMsgs, long beforeTok, long afterTok) {}

    // ─── 编排入口 ───

    /**
     * 每轮请求前 / 手动 / 紧急的唯一入口。
     * <p>
     * MANUAL：跳过第 1 层、阈值、熔断，直接 forceCompact。
     * EMERGENCY：先强制跑一次第 1 层把大工具结果挪走，再无条件 forceCompact。
     * AUTO：先跑第 1 层，用 layer1 后的消息重估 token，未触阈值或已熔断则仅第 1 层生效，
     *       否则 autoCompact。
     */
    public static CompactMsg manage(Input in) throws CompactException {
        switch (in.trigger()) {
            case MANUAL -> {
                CompactResult result = forceCompact(in);
                in.conv().replaceMessages(result.newMsgs());
                return new CompactMsg(in.estimatedToken(), result.afterTok());
            }
            case EMERGENCY -> {
                List<Message> layer1Out = offloadAndSnip(in.conv().getMessages(), in.replacement(), in.session());
                in.conv().replaceMessages(layer1Out);
                CompactResult result = forceCompact(in);
                in.conv().replaceMessages(result.newMsgs());
                return new CompactMsg(in.estimatedToken(), result.afterTok());
            }
            case AUTO -> {
                List<Message> layer1Out = offloadAndSnip(in.conv().getMessages(), in.replacement(), in.session());
                in.conv().replaceMessages(layer1Out);
                long estTokens = Token.estimateTokens(in.usageAnchor(), layer1Out, in.anchorMsgLen());
                if (in.contextWindow() <= CompactConstants.SUMMARY_RESERVE + CompactConstants.AUTO_SAFETY_MARGIN) {
                    return new CompactMsg(in.estimatedToken(), estTokens);
                }
                long threshold = in.contextWindow()
                        - CompactConstants.SUMMARY_RESERVE - CompactConstants.AUTO_SAFETY_MARGIN;
                if (estTokens < threshold || in.autoTracking().tripped()) {
                    return new CompactMsg(in.estimatedToken(), estTokens);
                }
                CompactResult result = autoCompact(in);
                in.conv().replaceMessages(result.newMsgs());
                return new CompactMsg(in.estimatedToken(), result.afterTok());
            }
        }
        throw new IllegalStateException("未知 TriggerKind");
    }

    // ─── 第 1 层：单条 / 聚合落盘 + 决策冻结 ───

    /**
     * 遍历 msgs，针对每一条 TOOL 消息上的 toolResults 列表做幂等「超阈值落盘 + 字符串替换」。
     * 规则：
     * <ol>
     *   <li>已在账本中的项，通过只读查询拿到现存决策（KEPT → 原文；REPLACED → 复用预览，不重新构造）。</li>
     *   <li>未决策项进入候选列表，按字节倒序处理：单条 &gt; 单条阈值必须落盘；再按聚合预算继续落盘，
     *       直至剩余聚合 ≤ 聚合阈值。</li>
     *   <li>落盘失败降级为不替换、不写账本（SKIP），下次重试。</li>
     *   <li>落盘 → 改写 content → 写账本通过 decideOnce 在同一临界区顺序执行，保证 content 与账本一致。</li>
     * </ol>
     * 返回新的消息列表，纯函数风格，不修改入参。
     */
    public static List<Message> offloadAndSnip(List<Message> msgs, ContentReplacementState state,
                                               SessionContext session) {
        List<Message> out = new ArrayList<>(msgs.size());
        for (Message m : msgs) {
            if (m.getRole() != Message.Role.TOOL || m.getToolResults().isEmpty()) {
                out.add(m);
                continue;
            }
            out.add(snipToolMessage(m, state, session));
        }
        return out;
    }

    /** 处理一条 TOOL 消息：对其 toolResults 列表做落盘 / 替换决策。 */
    private static Message snipToolMessage(Message m, ContentReplacementState state, SessionContext session) {
        List<ToolResult> results = m.getToolResults();
        int n = results.size();
        String[] outContent = new String[n];
        List<Integer> candidates = new ArrayList<>();
        long aggregate = 0;

        // 冻结决策 + 初始聚合
        for (int i = 0; i < n; i++) {
            ToolResult r = results.get(i);
            String id = r.toolCallId();
            String content = r.content();
            if (state.seen(id)) {
                String repl = state.replacement(id);
                outContent[i] = repl != null ? repl : content;
                if (repl == null) {
                    aggregate += bytes(content);
                }
            } else {
                outContent[i] = content;
                candidates.add(i);
                aggregate += bytes(content);
            }
        }

        // 按字节倒序处理候选
        candidates.sort((a, b) -> Long.compare(bytes(results.get(b).content()), bytes(results.get(a).content())));

        for (int ci = 0; ci < candidates.size(); ci++) {
            int idx = candidates.get(ci);
            ToolResult r = results.get(idx);
            String id = r.toolCallId();
            String content = r.content();
            long b = bytes(content);
            boolean mustReplace = b > CompactConstants.SINGLE_RESULT_LIMIT;
            if (!mustReplace && aggregate <= CompactConstants.MESSAGE_AGGREGATE_LIMIT) {
                // 该候选及其后更小的候选均保留原文，冻结为 KEPT
                for (int k = ci; k < candidates.size(); k++) {
                    ToolResult kr = results.get(candidates.get(k));
                    state.decideOnce(kr.toolCallId(), kr.content(),
                            () -> new DecisionResult(Decision.KEPT, null));
                }
                break;
            }
            String resolved = state.decideOnce(id, content, () -> {
                try {
                    spillSingle(session, id, content);
                } catch (IOException e) {
                    return new DecisionResult(Decision.SKIP, null);
                }
                Path spillPath = session.spillDir().resolve(id);
                return new DecisionResult(Decision.REPLACED,
                        buildPreview((int) b, headPreview(content), spillPath));
            });
            outContent[idx] = resolved;
            if (state.seen(id) && state.replacement(id) != null) {
                aggregate -= b;
            }
        }

        List<ToolResult> newResults = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ToolResult r = results.get(i);
            newResults.add(new ToolResult(r.toolCallId(), outContent[i], r.isError()));
        }
        return new Message(m.getRole(), m.getContent(), m.getToolCalls(), newResults);
    }

    /** 把单条 toolResult 内容写入 spillDir/{@code toolUseId}；幂等：文件已存在则不重写、不报错。 */
    static void spillSingle(SessionContext session, String toolUseId, String content) throws IOException {
        Path path = session.spillDir().resolve(toolUseId);
        if (Files.exists(path)) {
            return;
        }
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    /** 头部预览：先按行截到 PREVIEW_HEAD_LINES，再按字节截到 PREVIEW_HEAD_BYTES，二者择短。 */
    static String headPreview(String content) {
        String head = content;
        String[] lines = content.split("\n", CompactConstants.PREVIEW_HEAD_LINES + 1);
        if (lines.length > CompactConstants.PREVIEW_HEAD_LINES) {
            head = String.join("\n", Arrays.copyOf(lines, CompactConstants.PREVIEW_HEAD_LINES));
        }
        byte[] bytes = head.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CompactConstants.PREVIEW_HEAD_BYTES) {
            head = new String(bytes, 0, CompactConstants.PREVIEW_HEAD_BYTES, StandardCharsets.UTF_8);
        }
        return head;
    }

    /**
     * 构造替换体字符串，含四项信息：原始字节数、头部预览、落盘路径、重读提示。
     * 只在首次决策为替换的瞬间调用一次；之后所有轮次复用账本里存好的字符串。
     */
    static String buildPreview(int originalBytes, String head, Path spillPath) {
        return "[content offloaded] original size: " + originalBytes + " bytes\n"
                + "[saved to] " + spillPath + "\n"
                + "[head preview]\n"
                + head + "\n"
                + "完整内容已保存到上述路径,如需查看请用文件读取工具读取该路径,不要凭头部预览猜测全文";
    }

    private static long bytes(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ─── 第 2 层：LLM 摘要 + 恢复 + PTL 重试 + 熔断 ───

    /** 自动摘要路径：整轮失败累加熔断计数，成功清零。 */
    static CompactResult autoCompact(Input in) throws CompactException {
        long beforeTok = in.estimatedToken();
        List<Message> newMsgs;
        try {
            newMsgs = runSummary(in);
        } catch (CompactException e) {
            in.autoTracking().recordFailure();
            throw e;
        }
        in.autoTracking().recordSuccess();
        long afterTok = Token.estimateTokens(0, newMsgs, 0);
        return new CompactResult(newMsgs, beforeTok, afterTok);
    }

    /** 手动 / 紧急路径：跳过熔断器，失败不计入熔断。 */
    static CompactResult forceCompact(Input in) throws CompactException {
        long beforeTok = in.estimatedToken();
        List<Message> newMsgs = runSummary(in);
        long afterTok = Token.estimateTokens(0, newMsgs, 0);
        return new CompactResult(newMsgs, beforeTok, afterTok);
    }

    /**
     * 两条路径的共同核心：构造摘要 prompt、发请求、解析 &lt;summary&gt;、
     * 拼接恢复段、追加近期原文边界裁剪。
     * 入口先拍一次 recoverySnapshot，整个生命周期只使用这一份快照，避免恢复段渲染期间
     * 另一线程通过 recordFile 造成「声明的工具/文件」与「Stream 调用时刻状态」漂移。
     */
    static List<Message> runSummary(Input in) throws CompactException {
        List<Message> oldMsgs = in.conv().getMessages();
        List<Recovery.FileReadRecord> snapshot = in.recovery().snapshot();
        String summaryText;
        try {
            summaryText = summarizeOnce(in, oldMsgs);
        } catch (PromptTooLongException p) {
            summaryText = ptlRetry(in, oldMsgs, p);
        } catch (IOException e) {
            throw new CompactException("摘要请求失败: " + e.getMessage(), e);
        }
        String recoveryText = Recovery.buildRecoveryAttachment(snapshot, in.toolDefs());
        String combined = "## 历史会话摘要\n" + summaryText + "\n\n" + recoveryText;
        Message summaryAndRecovery = new Message(Message.Role.USER, combined);
        List<Message> recentTail = pickRecentTail(oldMsgs);
        return joinAfterSummary(summaryAndRecovery, recentTail);
    }

    /** 发一次摘要请求：工具留空；文本累加；捕获 usage 但不更新主对话锚点；PTL 通过 cause 识别。 */
    static String summarizeOnce(Input in, List<Message> msgs) throws PromptTooLongException, IOException {
        List<Message> promptMsgs = SummaryPrompt.buildSummaryPrompt(msgs);
        Request req = new Request(promptMsgs, List.of(), new SystemPrompt("", ""), "");
        BlockingQueue<StreamEvent> queue = in.client().stream(req);
        StringBuilder text = new StringBuilder();
        try {
            while (true) {
                StreamEvent ev = queue.poll(500, TimeUnit.MILLISECONDS);
                if (ev == null) {
                    continue;
                }
                switch (ev) {
                    case StreamEvent.TextDelta d -> text.append(d.text());
                    case StreamEvent.StreamEnd s -> {
                        return SummaryPrompt.extractSummary(text.toString());
                    }
                    case StreamEvent.Error e -> {
                        if (e.cause() instanceof PromptTooLongException p) {
                            throw p;
                        }
                        throw new IOException(e.message());
                    }
                    default -> { /* ToolCallComplete / UsageEvent / ThinkingDelta 忽略 */ }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("摘要请求被中断", ie);
        }
    }

    /** 实现 F27 的丢消息组策略：前 PTL_RETRY_LIMIT 次丢最旧 1 组，之后按比例丢，直至能塞下或全部丢光。 */
    static String ptlRetry(Input in, List<Message> msgs, Throwable firstErr) throws CompactException {
        List<List<Message>> groups = new ArrayList<>(groupByUserTurn(msgs));
        Throwable lastErr = firstErr;
        int retries = 0;
        while (true) {
            if (groups.isEmpty()) {
                throw new CompactException("ptl retry exhausted", lastErr);
            }
            int drop = retries < CompactConstants.PTL_RETRY_LIMIT
                    ? 1
                    : Math.max(1, (int) Math.ceil(groups.size() * CompactConstants.PTL_DROP_PERCENTAGE));
            int toDrop = Math.min(drop, groups.size());
            for (int i = 0; i < toDrop; i++) {
                groups.remove(0);
            }
            if (groups.isEmpty()) {
                throw new CompactException("ptl retry exhausted", lastErr);
            }
            try {
                return summarizeOnce(in, flatten(groups));
            } catch (PromptTooLongException p) {
                lastErr = p;
                retries++;
            } catch (IOException e) {
                throw new CompactException("摘要请求失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 从 msgs 尾部累加，满足「累计 token ≥ RECENT_KEEP_TOKENS 且 条数 ≥ RECENT_KEEP_MESSAGES」
     * 两个下界都满足后才停手（F11 的择宽语义）。之后做 tool_use/tool_result 配对修正：
     * 若截断点夹在配对中间，向前推到 assistant 工具调用之前。
     */
    static List<Message> pickRecentTail(List<Message> msgs) {
        if (msgs.isEmpty()) {
            return List.of();
        }
        int startIdx = msgs.size() - 1;
        long charsSum = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            charsSum += Token.messageChars(List.of(msgs.get(i)));
            long tokens = (long) Math.ceil(charsSum / CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
            int count = msgs.size() - i;
            startIdx = i;
            if (tokens >= CompactConstants.RECENT_KEEP_TOKENS
                    && count >= CompactConstants.RECENT_KEEP_MESSAGES) {
                break;
            }
        }
        // 配对修正：起点若是落单 tool_result，前移到上一个 assistant（带 toolCalls）之前
        while (startIdx < msgs.size() && msgs.get(startIdx).getRole() == Message.Role.TOOL) {
            startIdx--;
        }
        if (startIdx < 0) {
            startIdx = 0;
        }
        return new ArrayList<>(msgs.subList(startIdx, msgs.size()));
    }

    /** 摘要+恢复消息与近期原文拼接，避免 user/user 连续违反 Anthropic 协议（F12a）。 */
    static List<Message> joinAfterSummary(Message summaryAndRecovery, List<Message> recent) {
        List<Message> out = new ArrayList<>();
        out.add(summaryAndRecovery);
        if (recent.isEmpty()) {
            return out;
        }
        Message first = recent.get(0);
        if (first.getRole() == Message.Role.USER) {
            out.add(new Message(Message.Role.ASSISTANT, "(已加载上下文摘要与恢复信息。请继续。)"));
            out.addAll(recent);
            return out;
        }
        if (first.getRole() == Message.Role.TOOL) {
            int idx = 0;
            while (idx < recent.size() && recent.get(idx).getRole() == Message.Role.TOOL) {
                idx++;
            }
            if (idx < recent.size()) {
                out.addAll(recent.subList(idx, recent.size()));
            }
            return out;
        }
        out.addAll(recent);
        return out;
    }

    /** 按 F27 的「用户提交 → 一组 assistant/tool 往返」分组，给 ptlRetry 用。 */
    static List<List<Message>> groupByUserTurn(List<Message> msgs) {
        List<List<Message>> groups = new ArrayList<>();
        List<Message> cur = null;
        for (Message m : msgs) {
            if (m.getRole() == Message.Role.USER) {
                if (cur != null && !cur.isEmpty()) {
                    groups.add(cur);
                }
                cur = new ArrayList<>();
                cur.add(m);
            } else {
                if (cur == null) {
                    cur = new ArrayList<>();
                }
                cur.add(m);
            }
        }
        if (cur != null && !cur.isEmpty()) {
            groups.add(cur);
        }
        return groups;
    }

    private static List<Message> flatten(List<List<Message>> groups) {
        List<Message> out = new ArrayList<>();
        for (List<Message> g : groups) {
            out.addAll(g);
        }
        return out;
    }
}
