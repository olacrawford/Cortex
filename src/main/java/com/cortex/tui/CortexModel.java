package com.cortex.tui;

import com.cortex.config.ProviderConfig;
import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.StreamEvent;
import com.cortex.prompt.PromptBuilder;
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
 * 主 TUI 模型：多 provider 选择、输入、流式接收、spinner 计时、scrollback 提交与错误反馈。
 * 状态机：{@link AppState#PROVIDER_SELECT}（多 provider）→ {@link AppState#CHAT}。
 */
public class CortexModel implements Model {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(60);

    private final List<ProviderConfig> providers;
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
    private BlockingQueue<StreamEvent> streamQueue;
    private long requestStartMs;
    private long tickCounter;
    private boolean doneAtLeastOnce;

    /** 已提交（渲染定型并写入 scrollback）的消息列表，用于退出时 dumpHistory。 */
    private final List<String> committed = new ArrayList<>();

    public CortexModel(List<ProviderConfig> providers) {
        this.providers = providers;
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
        streaming = true;
        requestStartMs = System.currentTimeMillis();
        tickCounter = 0;
        streamQueue = client.stream(conversation, List.of());

        return new UpdateResult<>(this, Command.batch(
                Command.println(userLine),
                Command.tick(POLL_INTERVAL, t -> new StreamTickMessage())));
    }

    // ─── 流式轮询 ───
    private UpdateResult<? extends Model> onStreamTick() {
        tickCounter++;
        if (streamQueue == null) {
            return new UpdateResult<>(this, null);
        }
        boolean endSeen = false;
        StreamEvent.Error err = null;

        try {
            while (true) {
                StreamEvent ev = streamQueue.poll();
                if (ev == null) {
                    break;
                }
                if (ev instanceof StreamEvent.TextDelta delta) {
                    streamBuf.append(delta.text());
                } else if (ev instanceof StreamEvent.StreamEnd endEvent) {
                    endSeen = true;
                } else if (ev instanceof StreamEvent.Error errorEvent) {
                    endSeen = true;
                    err = errorEvent;
                }
                // ThinkingDelta 接收即丢弃
            }
        } catch (Exception e) {
            endSeen = true;
            err = new StreamEvent.Error(e.getMessage() != null ? e.getMessage() : e.toString());
        }

        if (endSeen) {
            long elapsedMs = System.currentTimeMillis() - requestStartMs;
            if (err != null) {
                return onError(err.message());
            }
            String rendered = MarkdownRenderer.render(streamBuf.toString(), width);
            String assistantBlock = Styles.ASSISTANT_PREFIX.apply("● ")
                    + rendered + elapsedSuffix(elapsedMs);
            committed.add(assistantBlock);
            conversation.addAssistantMessage(streamBuf.toString());
            streaming = false;
            return new UpdateResult<>(this, Command.batch(
                    Command.println(assistantBlock),
                    Command.println(separatorLine())));
        }

        return new UpdateResult<>(this, Command.tick(POLL_INTERVAL, t -> new StreamTickMessage()));
    }

    private UpdateResult<? extends Model> onError(String message) {
        streaming = false;
        String errorLine = Styles.ERROR.apply("✖ 请求失败：") + message;
        committed.add(errorLine);
        return new UpdateResult<>(this, Command.batch(
                Command.println(errorLine),
                Command.println(separatorLine())));
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
            String verb = SpinnerVerbs.pick(elapsed);
            char frame = SpinnerVerbs.frameAt(tickCounter);
            sb.append(Styles.SPINNER.apply(String.valueOf(frame))
                    .concat(" " + verb + "… (" + seconds + "s)"))
                    .append("\r\n\r\n");
            sb.append(streamBuf);
            sb.append("\r\n");
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
