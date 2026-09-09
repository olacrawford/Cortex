package com.cortex.tui;

import com.cortex.agent.Agent;
import com.cortex.agent.AgentEvent;
import com.cortex.agent.ApprovalRequest;
import com.cortex.agent.CancelToken;
import com.cortex.agent.CompactEvent;
import com.cortex.agent.Phase;
import com.cortex.agent.SessionRuntime;
import com.cortex.agent.ToolEvent;
import com.cortex.config.ProviderConfig;
import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.ToolDef;
import com.cortex.permission.Mode;
import com.cortex.permission.Outcome;
import com.cortex.permission.PermissionEngine;
import com.cortex.prompt.Prompt;
import com.cortex.prompt.Reminder;
import com.cortex.tool.ToolRegistry;
import com.cortex.tui.tea.Command;
import com.cortex.tui.tea.CompactNoticeMessage;
import com.cortex.tui.tea.KeyPressMessage;
import com.cortex.tui.tea.Message;
import com.cortex.tui.tea.Model;
import com.cortex.tui.tea.MouseMessage;
import com.cortex.tui.tea.Program;
import com.cortex.tui.tea.QuitMessage;
import com.cortex.tui.tea.StreamTickMessage;
import com.cortex.tui.tea.UpdateResult;
import com.cortex.tui.tea.WindowSizeMessage;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

/**
 * 主 TUI 模型：多 provider 选择、输入、Agent 单轮闭环事件渲染、spinner 计时、scrollback 提交与错误反馈。
 * 状态机：{@link AppState#PROVIDER_SELECT}（多 provider）→ {@link AppState#CHAT}。
 */
public class CortexModel implements Model {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(60);
    /** 工具结果摘要最多展示的行数（完整结果已回灌进对话历史）。 */
    private static final int SUMMARY_MAX_LINES = 8;

    /** 执行中的工具（用于动态区 Running… 指示；null 表示当前没有工具在跑）。 */
    private record ToolDisplay(String name, String args) {}

    private final List<ProviderConfig> providers;
    private final ToolRegistry registry;
    private final PermissionEngine engine;
    private final SessionRuntime runtime;
    private final ConversationManager conversation = new ConversationManager();

    private AppState state = AppState.CHAT;
    private int width = 80;

    // provider 选择
    private int selectIndex;

    // 当前会话
    private ProviderConfig activeProvider;
    private LlmClient client;
    private Agent agent;
    private Program program;

    private String input = "";
    private boolean streaming;
    private StringBuilder streamBuf = new StringBuilder();
    private BlockingQueue<AgentEvent> agentQueue;
    private final List<ToolDisplay> curTools = new ArrayList<>();
    private CancelToken turnCancel;
    private long requestStartMs;
    private long tickCounter;
    private boolean doneAtLeastOnce;

    // 人在回路（待批准）状态
    private ApprovalRequest pending;
    private int approveCursor;

    // 权限模式（跨轮保持；初始值取自三层配置）
    private Mode mode;
    private int iter;
    private long usageIn;
    private long usageOut;

    /** 已提交（渲染定型并写入 scrollback）的消息列表，用于退出时 dumpHistory。 */
    private final List<String> committed = new ArrayList<>();

    public CortexModel(List<ProviderConfig> providers, ToolRegistry registry, PermissionEngine engine,
                       SessionRuntime runtime) {
        this.providers = providers;
        this.registry = registry;
        this.engine = engine;
        this.runtime = runtime;
        this.mode = engine.startMode();
        if (providers.size() == 1) {
            this.state = AppState.CHAT;
            activate(providers.get(0));
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
    }

    /** 由 Main 注入 Program，供后台线程向 UI 事件循环投递消息。 */
    public void attach(Program program) {
        this.program = program;
    }

    @Override
    public Command init() {
        return Command.checkWindowSize();
    }

    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        if (msg instanceof WindowSizeMessage size) {
            width = size.width();
            if (!doneAtLeastOnce) {
                doneAtLeastOnce = true;
                // 多 provider 选择画面已内联渲染 banner，这里只对单 provider 直进 CHAT 打印。
                if (state == AppState.CHAT) {
                    return new UpdateResult<>(this, Command.batch(
                            Command.println(bannerBlock()),
                            Command.println("就绪。")));
                }
            }
            return new UpdateResult<>(this, null);
        }
        if (msg instanceof QuitMessage) {
            return new UpdateResult<>(this, null);
        }
        if (msg instanceof CompactNoticeMessage notice) {
            return pushSystemMessage(notice.text());
        }
        if (msg instanceof StreamTickMessage) {
            return onStreamTick();
        }
        if (msg instanceof KeyPressMessage key) {
            return onKey(key);
        }
        if (msg instanceof MouseMessage mouse) {
            return onMouse(mouse);
        }
        return new UpdateResult<>(this, null);
    }

    // ─── 人在回路：待批准三选一（F8）───

    /** 待批准态按键：↑↓/j/k 移光标，回车确认，数字键 1/2/3 直选，y/n 便捷键，Esc/Ctrl+C 取消。 */
    private UpdateResult<? extends Model> onApprovalKey(KeyPressMessage key) {
        switch (key.key()) {
            case "up", "left" -> {
                approveCursor = (approveCursor + 2) % 3;
                return new UpdateResult<>(this, null);
            }
            case "down", "right" -> {
                approveCursor = (approveCursor + 1) % 3;
                return new UpdateResult<>(this, null);
            }
            case "enter" -> {
                return commitApproval(outcomeForIndex(approveCursor));
            }
            case "esc", "ctrl+c" -> {
                // 取消：先解阻塞（兜底 DENY_ONCE），再取消本轮（N4）
                return cancelApproval();
            }
            default -> {
                return switch (key.key()) {
                    case "1" -> commitApproval(Outcome.ALLOW_ONCE);
                    case "2" -> commitApproval(Outcome.ALLOW_FOREVER);
                    case "3" -> commitApproval(Outcome.DENY_ONCE);
                    case "y" -> commitApproval(Outcome.ALLOW_ONCE);
                    case "n", "d" -> commitApproval(Outcome.DENY_ONCE);
                    default -> new UpdateResult<>(this, null);
                };
            }
        }
    }

    private UpdateResult<? extends Model> commitApproval(Outcome outcome) {
        if (pending != null) {
            pending.respond().offer(outcome);
        }
        pending = null;
        return new UpdateResult<>(this, null);
    }

    /** approving 态取消：兜底 DENY_ONCE 解 agent 阻塞，再取消本轮（不退出程序，N4）。 */
    private UpdateResult<? extends Model> cancelApproval() {
        if (pending != null) {
            pending.respond().offer(Outcome.DENY_ONCE);
        }
        pending = null;
        if (turnCancel != null) {
            turnCancel.cancel();
        }
        return new UpdateResult<>(this, null);
    }

    private static Outcome outcomeForIndex(int index) {
        return switch (index) {
            case 1 -> Outcome.ALLOW_FOREVER;
            case 2 -> Outcome.DENY_ONCE;
            default -> Outcome.ALLOW_ONCE;
        };
    }

    // ─── provider 选择 ───
    private UpdateResult<? extends Model> onKey(KeyPressMessage key) {
        // 人在回路（待批准）态：按键全部由三选一菜单消费（F8）
        if (pending != null) {
            return onApprovalKey(key);
        }
        switch (key.key()) {
            case "shift+tab" -> {
                // 仅空闲态生效：循环切换权限模式（F7），跨轮保持
                if (state == AppState.CHAT && !streaming) {
                    mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
                    String notice = Styles.MUTED.apply("⊕ 权限模式已切换为 " + mode.displayName());
                    committed.add(notice);
                    return new UpdateResult<>(this, Command.println(notice));
                }
                return new UpdateResult<>(this, null);
            }
            case "ctrl+c" -> {
                // 流式态：取消本轮、回空闲态、不退出（F7）；其余状态退出程序
                if (streaming) {
                    if (turnCancel != null) {
                        turnCancel.cancel();
                    }
                    return new UpdateResult<>(this, null);
                }
                return new UpdateResult<>(this, Command.quit());
            }
            case "esc" -> {
                if (streaming && turnCancel != null) {
                    turnCancel.cancel();
                }
                return new UpdateResult<>(this, null);
            }
            case "up", "left" -> {
                if (state == AppState.PROVIDER_SELECT) {
                    selectIndex = (selectIndex - 1 + providers.size()) % providers.size();
                    return new UpdateResult<>(this, null);
                }
                return new UpdateResult<>(this, null);
            }
            case "down", "right" -> {
                if (state == AppState.PROVIDER_SELECT) {
                    selectIndex = (selectIndex + 1) % providers.size();
                    return new UpdateResult<>(this, null);
                }
                return new UpdateResult<>(this, null);
            }
            case "enter" -> {
                if (state == AppState.PROVIDER_SELECT) {
                    ProviderConfig chosen = providers.get(selectIndex);
                    activate(chosen);
                    state = AppState.CHAT;
                    return new UpdateResult<>(this, Command.batch(
                            Command.println(bannerBlock()),
                            Command.println("已连接: " + chosen.getName() + " (" + chosen.getModel() + ")"),
                            Command.println("就绪。")));
                }
                return submit();
            }
            case "backspace" -> {
                if (!streaming && !input.isEmpty()) {
                    input = input.substring(0, input.length() - 1);
                }
                return new UpdateResult<>(this, null);
            }
            default -> {
                // 普通字符：拼入输入缓冲（流式期间忽略输入）
                if (!streaming) {
                    input += new String(key.runes());
                }
                return new UpdateResult<>(this, null);
            }
        }
    }

    private UpdateResult<? extends Model> onMouse(MouseMessage mouse) {
        // 本期不使用鼠标导航；滚动 scrollback 由终端原生回滚支持
        return new UpdateResult<>(this, null);
    }

    private void activate(ProviderConfig provider) {
        this.activeProvider = provider;
        this.client = LlmClient.create(provider);
        this.runtime.contextWindow = provider.effectiveContextWindow();
        this.agent = new Agent(client, registry, Prompt.VERSION, engine, runtime);
    }

    // ─── 提交一轮对话 ───
    private UpdateResult<? extends Model> submit() {
        String text = input.strip();
        input = "";
        if (text.isEmpty()) {
            return new UpdateResult<>(this, null);
        }
        Optional<Commands.CommandHandler> handler = Commands.dispatchCommand(text);
        if (handler.isPresent()) {
            return handler.get().handle(this);
        }

        conversation.addUserMessage(text);
        String userLine = Styles.USER_PREFIX.apply("❯ ") + text;
        committed.add(userLine);
        return startTurn(userLine);
    }

    /** 启动一轮 Agent Loop：per-turn 取消句柄 + 事件队列 + tick 轮询。 */
    private UpdateResult<? extends Model> startTurn(String userLine) {
        streamBuf = new StringBuilder();
        curTools.clear();
        iter = 0;
        streaming = true;
        requestStartMs = System.currentTimeMillis();
        tickCounter = 0;
        turnCancel = new CancelToken();
        // Agent 虚拟线程内跑 ReAct 循环（请求 → 权限判定 → 工具 → 回灌 → 下一轮……直到停止条件）
        agentQueue = agent.run(conversation, mode, turnCancel);

        return new UpdateResult<>(this, Command.batch(
                Command.println(userLine),
                Command.tick(POLL_INTERVAL, t -> new StreamTickMessage())));
    }

    // ─── Agent 事件轮询 ───
    private UpdateResult<? extends Model> onStreamTick() {
        tickCounter++;
        if (agentQueue == null) {
            return new UpdateResult<>(this, null);
        }

        boolean turnOver = false;
        List<Command> outputs = new ArrayList<>();
        AgentEvent ev;
        try {
            while ((ev = agentQueue.poll()) != null) {
                switch (ev) {
                    case AgentEvent.Text delta -> streamBuf.append(delta.delta());
                    case AgentEvent.Tool tool -> turnOver |= handleToolEvent(tool, outputs);
                    case CompactEvent compact -> {
                        String text = Commands.formatCompactNotice(compact);
                        String line = Styles.MUTED.apply("⊕ " + text);
                        committed.add(line);
                        outputs.add(Command.println(line));
                    }
                    case AgentEvent.UsageReport u -> {
                        usageIn += u.usage().inputTokens();
                        usageOut += u.usage().outputTokens();
                        if (Boolean.getBoolean("cortex.debug")) {
                            // 调试用：把每轮用量（含缓存写/读）以灰字打进 scrollback（F4 验证通道）
                            String line = Styles.MUTED.apply(String.format(
                                    "⟐ usage in=%d out=%d cacheW=%d cacheR=%d",
                                    u.usage().inputTokens(), u.usage().outputTokens(),
                                    u.usage().cacheWrite(), u.usage().cacheRead()));
                            committed.add(line);
                            outputs.add(Command.println(line));
                        }
                    }
                    case AgentEvent.Iter i -> iter = i.iter();
                    case AgentEvent.Notice n -> {
                        String line = Styles.MUTED.apply("⊕ " + n.message());
                        committed.add(line);
                        outputs.add(Command.println(line));
                    }
                    case AgentEvent.Approval approval -> pending = approval.request();
                    case AgentEvent.Done done -> {
                        outputs.addAll(finishTurn());
                        turnOver = true;
                    }
                    case AgentEvent.Failed failed -> {
                        outputs.addAll(handleError(failed.message()));
                        turnOver = true;
                    }
                }
            }
        } catch (Exception e) {
            outputs.addAll(handleError(e.getMessage() != null ? e.getMessage() : e.toString()));
            turnOver = true;
        }

        if (turnOver) {
            return new UpdateResult<>(this, Command.batch(outputs.toArray(new Command[0])));
        }
        if (!outputs.isEmpty()) {
            // 轮询中途产出的定型输出（preamble/工具行/结果摘要）随 tick 一起带出，否则会被丢弃
            List<Command> cmds = new ArrayList<>(outputs);
            cmds.add(Command.tick(POLL_INTERVAL, t -> new StreamTickMessage()));
            return new UpdateResult<>(this, Command.batch(cmds.toArray(new Command[0])));
        }
        return new UpdateResult<>(this, Command.tick(POLL_INTERVAL, t -> new StreamTickMessage()));
    }

    /** 工具事件：START 把已流出的 preamble 定型并挂执行指示；END 按序弹出队首工具、提交工具行与结果摘要。 */
    private boolean handleToolEvent(AgentEvent.Tool tool, List<Command> outputs) {
        ToolEvent event = tool.event();
        if (event.phase() == Phase.START) {
            if (!streamBuf.isEmpty()) {
                String preamble = Styles.ASSISTANT_PREFIX.apply("● ") + streamBuf;
                committed.add(preamble);
                outputs.add(Command.println(preamble));
                streamBuf = new StringBuilder();
            }
            curTools.add(new ToolDisplay(event.name(), event.args()));
        } else {
            // Agent 保证 START/END 都按调用序发出，弹队首即对应工具（重名也不会错位）
            ToolDisplay started = curTools.isEmpty() ? new ToolDisplay(event.name(), event.args()) : curTools.remove(0);
            String line = toolLine(started.name(), started.args());
            String summary = toolResultSummary(event.result(), event.isError());
            committed.add(line);
            committed.add(summary);
            outputs.add(Command.println(line));
            outputs.add(Command.println(summary));
        }
        return false;
    }

    /** 本轮结束定型：把累计文本（最终答复或被取消时的部分文本）渲染落 scrollback。幂等。 */
    private List<Command> finishTurn() {
        if (!streaming) {
            return List.of();
        }
        List<Command> outs = new ArrayList<>();
        if (!streamBuf.isEmpty()) {
            long elapsedMs = System.currentTimeMillis() - requestStartMs;
            String block = Styles.ASSISTANT_PREFIX.apply("● ")
                    + MarkdownRenderer.render(streamBuf.toString(), width) + elapsedSuffix(elapsedMs);
            committed.add(block);
            outs.add(Command.println(block));
        }
        resetTurnState();
        outs.add(Command.println(separatorLine()));
        return outs;
    }

    private List<Command> handleError(String message) {
        if (!streaming) {
            return List.of();
        }
        String errorLine = Styles.ERROR.apply("✖ 请求失败：") + message;
        committed.add(errorLine);
        resetTurnState();
        return List.of(Command.println(errorLine), Command.println(separatorLine()));
    }

    /** 回空闲态；mode 与累计用量跨轮保留。 */
    private void resetTurnState() {
        streaming = false;
        streamBuf = new StringBuilder();
        curTools.clear();
        iter = 0;
        turnCancel = null;
        agentQueue = null;
    }

    // ─── 工具行渲染（Claude Code 风格）───
    private String toolLine(String name, String args) {
        return Styles.TOOL_MARK.apply("● ")
                + Styles.TOOL_NAME.apply(name)
                + Styles.MUTED.apply("(" + args + ")");
    }

    private String toolResultSummary(String result, boolean isError) {
        String text = abbreviate(result);
        return Styles.MUTED.apply("  ⎿ ")
                + (isError ? Styles.ERROR.apply(text) : Styles.TOOL_RESULT.apply(text));
    }

    /** UI 摘要：最多 8 行，超出以省略标注（完整结果已回灌给模型）。 */
    private static String abbreviate(String result) {
        if (result == null || result.isBlank()) {
            return "（无输出）";
        }
        String[] lines = result.strip().split("\n", -1);
        if (lines.length <= SUMMARY_MAX_LINES) {
            return String.join("\n", lines);
        }
        return String.join("\n", List.of(lines).subList(0, SUMMARY_MAX_LINES))
                + "\n…（其余 " + (lines.length - SUMMARY_MAX_LINES) + " 行省略）";
    }

    private String elapsedSuffix(long elapsedMs) {
        int seconds = (int) (elapsedMs / 1000);
        return Styles.MUTED.apply("  (" + seconds + "s)");
    }

    // ─── view 渲染 ───
    @Override
    public String view() {
        StringBuilder sb = new StringBuilder();
        if (state == AppState.PROVIDER_SELECT) {
            renderProviderSelect(sb);
        } else {
            renderChat(sb);
        }
        if (sb.length() == 0) {
            sb.append(" ");
        }
        return sb.toString();
    }

    private void renderProviderSelect(StringBuilder sb) {
        sb.append(bannerBlock());
        sb.append("\r\n请选择模型提供方：\r\n\r\n");
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig p = providers.get(i);
            String marker = i == selectIndex ? "▸ " : "  ";
            String label = p.getName() + "  (" + p.getModel() + ")";
            sb.append(marker)
                    .append(i == selectIndex ? Styles.SELECT_ACTIVE.apply(label) : Styles.SELECT_IDLE.apply(label))
                    .append("\r\n");
        }
        sb.append("\r\n\n按 ↑/↓ 选择，Enter 确认。");
    }

    private void renderChat(StringBuilder sb) {
        if (pending != null) {
            sb.append(approvalBlock(pending, approveCursor));
            sb.append("\r\n");
        } else if (streaming) {
            long elapsed = System.currentTimeMillis() - requestStartMs;
            int seconds = (int) (elapsed / 1000);
            char frame = SpinnerVerbs.frameAt(tickCounter);
            String roundSuffix = iter > 0 ? " · 第 " + iter + " 轮" : "";
            if (!curTools.isEmpty()) {
                // 并发批：逐行列出在执行的工具（N2：界面持续刷新不冻结）
                for (ToolDisplay t : curTools) {
                    sb.append(Styles.SPINNER.apply(String.valueOf(frame))
                            .concat(" " + Styles.TOOL_MARK.apply("● ")
                                    + Styles.TOOL_NAME.apply(t.name())
                                    + Styles.MUTED.apply("(" + t.args() + ")")))
                            .append("\r\n");
                }
                sb.append(Styles.SPINNER.apply("  Running… (" + seconds + "s)")).append("\r\n\r\n");
            } else {
                String verb = SpinnerVerbs.pick(elapsed);
                sb.append(Styles.SPINNER.apply(String.valueOf(frame))
                        .concat(" " + verb + "… (" + seconds + "s" + roundSuffix + ")"))
                        .append("\r\n\r\n");
                sb.append(streamBuf);
                sb.append("\r\n");
            }
        } else {
            // 展示最近一条已提交的助手回复（若存在且未超出高度）
            sb.append("\r\n");
        }
        sb.append(separatorLine()).append("\r\n");
        sb.append(Styles.INPUT_PROMPT.apply("❯ ")).append(input.isEmpty() ? Styles.MUTED.apply("Send a message...") : input);
        sb.append("\r\n").append(separatorLine()).append("\r\n");
        sb.append(statusBar());
    }

    private String separatorLine() {
        int n = Math.min(Math.max(width - 2, 1), 200);
        return "─".repeat(n);
    }

    private String statusBar() {
        // 左侧常驻显示当前权限模式（取代 provider 名，F7/AC9）
        String left = switch (mode) {
            case DEFAULT -> Styles.SELECT_IDLE.apply("DEFAULT");
            case ACCEPT_EDITS -> Styles.SELECT_ACTIVE.apply("ACCEPT EDITS");
            case PLAN -> Styles.SPINNER.apply("PLAN");
            case BYPASS -> Styles.ERROR.apply("BYPASS");
        };
        String model = activeProvider != null ? activeProvider.getModel() : "";
        if (usageIn > 0 || usageOut > 0) {
            model += "  ↑" + compact(usageIn) + " ↓" + compact(usageOut) + " tok";
        }
        String right = Styles.STATUS_MODEL.apply(model);
        int usable = Math.max(width - 2, 1);
        int leftLen = left.replaceAll("\u001B\\[[0-9;]*m", "").length();
        String pad = " ".repeat(Math.max(1, usable - leftLen - right.replaceAll("\u001B\\[[0-9;]*m", "").length()));
        return left + pad + right;
    }

    /** 待批准多行块（F8）：工具名 + 参数 + 原因 + 三选一菜单（光标高亮）。 */
    private String approvalBlock(ApprovalRequest req, int cursor) {
        StringBuilder sb = new StringBuilder();
        sb.append(Styles.TOOL_MARK.apply("● "))
                .append(Styles.TOOL_NAME.apply(req.name()))
                .append(Styles.MUTED.apply("(" + req.args() + ")"))
                .append("\r\n");
        sb.append(Styles.MUTED.apply("  原因：" + req.reason())).append("\r\n");
        sb.append(Styles.INPUT_PROMPT.apply("  是否继续?")).append("\r\n");
        String[] items = {
                "1. 允许本次",
                "2. 永久允许（写入本地配置）",
                "3. 拒绝本次"
        };
        for (int i = 0; i < items.length; i++) {
            String prefix = i == cursor ? Styles.SELECT_ACTIVE.apply("  > ") : "    ";
            String label = i == cursor ? Styles.SELECT_ACTIVE.apply(items[i]) : Styles.MUTED.apply(items[i]);
            sb.append(prefix).append(label).append("\r\n");
        }
        sb.append(Styles.MUTED.apply("  ↑↓ 选择 · 回车确认 · 数字键直选 · Esc 取消"));
        return sb.toString();
    }

    /** 用量紧凑格式（如 1.2k）。 */
    private static String compact(long n) {
        if (n >= 1_000_000) {
            return "%.1fM".formatted(n / 1_000_000.0);
        }
        if (n >= 1_000) {
            return "%.1fk".formatted(n / 1_000.0);
        }
        return String.valueOf(n);
    }

    private String bannerBlock() {
        return Styles.BANNER.apply(Prompt.renderBanner())
                .concat("\r\n" + Styles.MUTED.apply("工作目录: " + System.getProperty("user.dir")));
    }

    // ─── 退出时历史 ───
    @Override
    public String dumpHistory() {
        StringBuilder sb = new StringBuilder();
        sb.append(bannerBlock()).append("\r\n\r\n");
        for (String line : committed) {
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    // ─── Commands 处理器辅助 ───

    /** /exit：退出主循环。 */
    UpdateResult<? extends Model> commandExit() {
        return new UpdateResult<>(this, Command.quit());
    }

    /** /plan：切换 Plan Mode，输出提示。 */
    UpdateResult<? extends Model> commandPlan() {
        mode = Mode.PLAN;
        String hint = Styles.MUTED.apply("⊕ 已进入计划模式：模型仅可用只读工具产出计划，用 /do 批准执行");
        committed.add(hint);
        return new UpdateResult<>(this, Command.println(hint));
    }

    /** /do：切回 DEFAULT，注入执行指令并启动一轮。 */
    UpdateResult<? extends Model> commandDo() {
        mode = Mode.DEFAULT;
        conversation.addUserMessage(Reminder.EXECUTE_DIRECTIVE);
        String doLine = Styles.USER_PREFIX.apply("❯ ") + Reminder.EXECUTE_DIRECTIVE;
        committed.add(doLine);
        return startTurn(doLine);
    }

    /** 渲染一条系统消息到 scrollback（命令路径 / 未知命令 / 手动压缩提示）。不写入 conversation。 */
    UpdateResult<? extends Model> pushSystemMessage(String text) {
        String line = Styles.MUTED.apply("⊕ " + text);
        committed.add(line);
        return new UpdateResult<>(this, Command.println(line));
    }

    /** 后台线程回投：把手动压缩结果文本推给 UI 线程。 */
    void pushCompactNotice(String text) {
        if (program != null) {
            program.send(new CompactNoticeMessage(text));
        }
    }

    /** 当前工具定义（与下一次 run 的 Request.tools 保持一致）。 */
    List<ToolDef> currentDefs() {
        return registry.definitions();
    }

    /** 手动 /compact 调用入口（由 Commands 在后台线程触发）。 */
    Agent.ForceCompactResult runForceCompact(List<ToolDef> defs) {
        return agent.runForceCompact(conversation, defs);
    }
}
