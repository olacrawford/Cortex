package com.cortex.tui;

import com.cortex.agent.Agent;
import com.cortex.agent.AgentEvent;
import com.cortex.agent.Phase;
import com.cortex.agent.ToolEvent;
import com.cortex.config.ProviderConfig;
import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.prompt.PromptBuilder;
import com.cortex.tool.ToolRegistry;
import com.cortex.tui.tea.Command;
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
    private final ConversationManager conversation = new ConversationManager();

    private AppState state = AppState.CHAT;
    private Program program;
    private int width = 80;
    private int height = 24;

    // provider 选择
    private int selectIndex;

    // 当前会话
    private ProviderConfig activeProvider;
    private LlmClient client;

    private String input = "";
    private boolean streaming;
    private StringBuilder streamBuf = new StringBuilder();
    private BlockingQueue<AgentEvent> agentQueue;
    private ToolDisplay curTool;
    private long requestStartMs;
    private long tickCounter;
    private boolean doneAtLeastOnce;

    /** 已提交（渲染定型并写入 scrollback）的消息列表，用于退出时 dumpHistory。 */
    private final List<String> committed = new ArrayList<>();

    public CortexModel(List<ProviderConfig> providers, ToolRegistry registry) {
        this.providers = providers;
        this.registry = registry;
        if (providers.size() == 1) {
            this.state = AppState.CHAT;
            activate(providers.get(0));
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
    }

    public void setProgram(Program program) {
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
            height = size.height();
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
        if (msg instanceof QuitMessage q) {
            return new UpdateResult<>(this, null);
        }
        if (msg instanceof StreamTickMessage tick) {
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

    // ─── provider 选择 ───
    private UpdateResult<? extends Model> onKey(KeyPressMessage key) {
        switch (key.key()) {
            case "ctrl+c" -> {
                return new UpdateResult<>(this, Command.quit());
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
        this.client = LlmClient.create(provider, PromptBuilder.buildSystemPrompt());
    }

    // ─── 提交一轮对话 ───
    private UpdateResult<? extends Model> submit() {
        String text = input.strip();
        input = "";
        if (text.isEmpty()) {
            return new UpdateResult<>(this, null);
        }
        if (text.equals("/exit") || text.equals("exit")) {
            return new UpdateResult<>(this, Command.quit());
        }

        conversation.addUserMessage(text);
        String userLine = Styles.USER_PREFIX.apply("❯ ") + text;
        committed.add(userLine);

        streamBuf = new StringBuilder();
        curTool = null;
        streaming = true;
        requestStartMs = System.currentTimeMillis();
        tickCounter = 0;
        // Agent 虚拟线程内完成「请求#1 → 执行工具 → 结果回灌 → 请求#2」整条链路
        agentQueue = new Agent(client, registry).run(conversation);

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

    /** 工具事件：START 把已流出的 preamble 定型并显示执行指示；END 提交工具行与结果摘要。 */
    private boolean handleToolEvent(AgentEvent.Tool tool, List<Command> outputs) {
        ToolEvent event = tool.event();
        if (event.phase() == Phase.START) {
            if (!streamBuf.isEmpty()) {
                String preamble = Styles.ASSISTANT_PREFIX.apply("● ") + streamBuf;
                committed.add(preamble);
                outputs.add(Command.println(preamble));
                streamBuf = new StringBuilder();
            }
            curTool = new ToolDisplay(event.name(), event.args());
        } else {
            String line = toolLine(event.name(), event.args());
            String summary = toolResultSummary(event.result(), event.isError());
            committed.add(line);
            committed.add(summary);
            outputs.add(Command.println(line));
            outputs.add(Command.println(summary));
            curTool = null;
        }
        return false;
    }

    /** 最终答复定型：markdown 渲染 + 耗时后缀，写入 scrollback。 */
    private List<Command> finishTurn() {
        long elapsedMs = System.currentTimeMillis() - requestStartMs;
        String rendered = MarkdownRenderer.render(streamBuf.toString(), width);
        String block = Styles.ASSISTANT_PREFIX.apply("● ") + rendered + elapsedSuffix(elapsedMs);
        committed.add(block);
        streamBuf = new StringBuilder();
        streaming = false;
        return List.of(Command.println(block), Command.println(separatorLine()));
    }

    private List<Command> handleError(String message) {
        streaming = false;
        curTool = null;
        String errorLine = Styles.ERROR.apply("✖ 请求失败：") + message;
        committed.add(errorLine);
        return List.of(Command.println(errorLine), Command.println(separatorLine()));
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
        if (streaming) {
            long elapsed = System.currentTimeMillis() - requestStartMs;
            int seconds = (int) (elapsed / 1000);
            char frame = SpinnerVerbs.frameAt(tickCounter);
            if (curTool != null) {
                // 工具执行中：显示工具行 + Running…，界面持续刷新不冻结（N2）
                sb.append(Styles.SPINNER.apply(String.valueOf(frame))
                        .concat(" " + Styles.TOOL_MARK.apply("● ")
                                + Styles.TOOL_NAME.apply(curTool.name())
                                + Styles.MUTED.apply("(" + curTool.args() + ")"))
                        .concat("  Running… (" + seconds + "s)"))
                        .append("\r\n\r\n");
            } else {
                String verb = SpinnerVerbs.pick(elapsed);
                sb.append(Styles.SPINNER.apply(String.valueOf(frame))
                        .concat(" " + verb + "… (" + seconds + "s)"))
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
        String providerName = activeProvider != null ? activeProvider.getName() : "";
        String model = activeProvider != null ? activeProvider.getModel() : "";
        String left = Styles.STATUS_PROVIDER.apply(providerName);
        String right = Styles.STATUS_MODEL.apply(model);
        int usable = Math.max(width - 2, 1);
        int leftLen = left.replaceAll("\u001B\\[[0-9;]*m", "").length();
        String pad = " ".repeat(Math.max(1, usable - leftLen - right.replaceAll("\u001B\\[[0-9;]*m", "").length()));
        return left + pad + right;
    }

    private String bannerBlock() {
        return Styles.BANNER.apply(PromptBuilder.renderBanner())
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
}
