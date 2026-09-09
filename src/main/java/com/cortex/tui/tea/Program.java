package com.cortex.tui.tea;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Bubble Tea 风格的运行时：JLine 管理 raw mode + 按键读取，内联渲染（cursor-up 覆写）。
 */
public class Program {

    private Model model;
    private Terminal terminal;
    private BindingReader bindingReader;
    private final BlockingQueue<Message> inputQueue = new LinkedBlockingQueue<>();
    private final List<Scheduled> scheduled = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int linesRendered = 0;
    private String lastView = null;
    private long scheduleSeq = 0;
    private boolean started = false;

    private record Scheduled(long id, Instant at, Duration delay, Function<Instant, Message> fn) {
    }

    public Program(Model model) {
        this.model = model;
    }

    /** 供外部线程向事件循环投递消息（流式事件、窗口尺寸、定时器结果等）。 */
    public void send(Message msg) {
        inputQueue.offer(msg);
    }

    public int getWidth() {
        return terminal != null ? terminal.getWidth() : 80;
    }

    public int getHeight() {
        return terminal != null ? terminal.getHeight() : 24;
    }

    public void run() {
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            if ("dumb".equals(terminal.getType())) {
                System.out.println("Cortex 需要交互式终端（TTY）。");
                return;
            }
            terminal.enterRawMode();
            bindingReader = new BindingReader(terminal.reader());
            running.set(true);
            started = true;

            handleSignals();

            // 模型初始化
            execute(model.init());

            // 按键读取线程（阻塞，独立于主循环）
            Thread keyThread = Thread.ofVirtual().name("key-reader").start(this::readKeysLoop);

            // 主事件循环
            eventLoop();

            running.set(false);
            keyThread.interrupt();
        } catch (Exception e) {
            System.err.println("Cortex 启动失败: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void handleSignals() {
        terminal.handle(Terminal.Signal.WINCH,
                sig -> send(new WindowSizeMessage(terminal.getWidth(), terminal.getHeight())));
        terminal.handle(Terminal.Signal.INT, sig -> send(new KeyPressMessage("ctrl+c", new char[]{3})));
    }

    private void readKeysLoop() {
        KeyMap<KeyPressMessage> keymap = buildKeyMap();
        KeyPressMessage printable = new KeyPressMessage("\u0000PRINTABLE\u0000", new char[0]);
        keymap.setUnicode(printable);
        // Latin-1 内的未绑定字符（ASCII 字母/数字等）走 nomatch 兜底，
        // 否则会被 BindingReader 静默丢弃（unicode 回退只覆盖码点 >= KEYMAP_LENGTH 的字符）
        keymap.setNomatch(printable);

        while (running.get()) {
            try {
                KeyPressMessage kpm = bindingReader.readBinding(keymap);
                if (kpm == null) {
                    if (!running.get()) {
                        break;
                    }
                    continue;
                }
                if (kpm == printable) {
                    String text = bindingReader.getLastBinding();
                    if (text == null || text.isEmpty()) {
                        continue;
                    }
                    kpm = new KeyPressMessage(text, text.toCharArray());
                }
                inputQueue.offer(kpm);
            } catch (Exception e) {
                if (running.get()) {
                    inputQueue.offer(new QuitMessage());
                }
                break;
            }
        }
    }

    private KeyMap<KeyPressMessage> buildKeyMap() {
        KeyMap<KeyPressMessage> m = new KeyMap<>();
        // lone ESC 与方向键序列（ESC [ x）共前缀；默认 1000ms 消歧等待让裸 ESC 迟迟不触发，调短到 150ms
        m.setAmbiguousTimeout(150);
        m.bind(new KeyPressMessage("enter", new char[]{'\r'}), "\r");
        m.bind(new KeyPressMessage("enter", new char[]{'\n'}), "\n");
        m.bind(new KeyPressMessage("ctrl+c", new char[]{3}), KeyMap.ctrl('C'));
        m.bind(new KeyPressMessage("ctrl+d", new char[]{4}), KeyMap.ctrl('D'));
        m.bind(new KeyPressMessage("ctrl+j", new char[]{10}), KeyMap.ctrl('J'));
        m.bind(new KeyPressMessage("backspace", new char[]{127}), KeyMap.del());
        m.bind(new KeyPressMessage("backspace", new char[]{8}), "\b");
        m.bind(new KeyPressMessage("up", new char[0]), "\u001b[A");
        m.bind(new KeyPressMessage("shift+tab", new char[0]), "\u001b[Z");
        m.bind(new KeyPressMessage("down", new char[0]), "\u001b[B");
        m.bind(new KeyPressMessage("right", new char[0]), "\u001b[C");
        m.bind(new KeyPressMessage("left", new char[0]), "\u001b[D");
        m.bind(new KeyPressMessage("up", new char[0]), "\u001bOA");
        m.bind(new KeyPressMessage("down", new char[0]), "\u001bOB");
        m.bind(new KeyPressMessage("right", new char[0]), "\u001bOC");
        m.bind(new KeyPressMessage("left", new char[0]), "\u001bOD");
        m.bind(new KeyPressMessage("alt+enter", new char[]{'\n'}), KeyMap.alt("\r"));
        m.bind(new KeyPressMessage("alt+enter", new char[]{'\n'}), KeyMap.alt("\n"));
        m.bind(new KeyPressMessage("esc", new char[]{27}), KeyMap.esc());
        return m;
    }

    private void eventLoop() {
        while (running.get()) {
            Message msg = null;
            try {
                msg = inputQueue.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (msg != null) {
                if (msg instanceof QuitMessage) {
                    break;
                }
                UpdateResult<? extends Model> result = model.update(msg);
                model = result.model();
                execute(result.command());
                if (!running.get()) {
                    break;
                }
            }
            executeDueTicks();

            String view = model.view();
            if (view != null && !view.equals(lastView)) {
                render(view);
                lastView = view;
            }
        }
    }

    private void execute(Command command) {
        if (command == null) {
            return;
        }
        switch (command) {
            case Command.Tick tick -> schedule(tick.delay(), tick.fn());
            case Command.Println println -> writeToScrollback(println.text());
            case Command.CheckWindowSize ignored -> send(
                    new WindowSizeMessage(terminal.getWidth(), terminal.getHeight()));
            case Command.Quit quit -> running.set(false);
            case Command.Batch batch -> {
                for (Command c : batch.commands()) {
                    execute(c);
                }
            }
        }
    }

    private void schedule(Duration delay, Function<Instant, Message> fn) {
        scheduled.add(new Scheduled(++scheduleSeq, Instant.now().plus(delay), delay, fn));
    }

    private void executeDueTicks() {
        Instant now = Instant.now();
        for (Scheduled s : scheduled) {
            if (!s.at().isAfter(now)) {
                scheduled.remove(s);
                try {
                    Message m = s.fn().apply(now);
                    if (m != null) {
                        inputQueue.offer(m);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 将一段文本写入终端 scrollback：先清空当前 view，再写入文本。 */
    private void writeToScrollback(String text) {
        clearView();
        if (text != null && !text.isEmpty()) {
            terminal.writer().print(text);
            terminal.writer().print("\r\n");
        }
        terminal.writer().flush();
    }

    private void clearView() {
        if (linesRendered > 0) {
            StringBuilder sb = new StringBuilder();
            // 光标停在视图最后一行的行尾，上移 N-1 行即回到视图首行；多移一行会擦掉视图上方的 scrollback
            sb.append("\u001b[").append(linesRendered - 1).append("A");
            sb.append("\r");
            sb.append("\u001b[J");
            terminal.writer().print(sb.toString());
            terminal.writer().flush();
            linesRendered = 0;
            lastView = null;
        }
    }

    /** 内联渲染：cursor-up 覆写，不清除终端上方已有的 scrollback 内容。 */
    private void render(String view) {
        String[] lines = view.split("\n", -1);
        int newLines = lines.length;
        StringBuilder sb = new StringBuilder();
        if (linesRendered > 0) {
            sb.append("\u001b[").append(linesRendered - 1).append("A");
        }
        sb.append("\r");
        sb.append("\u001b[J");
        for (int i = 0; i < newLines; i++) {
            sb.append(lines[i]);
            sb.append("\u001b[0m");
            if (i < newLines - 1) {
                sb.append("\r\n");
            }
        }
        terminal.writer().print(sb.toString());
        terminal.writer().flush();
        linesRendered = newLines;
    }

    private void cleanup() {
        try {
            clearView();
        } catch (Exception ignored) {
        }
        try {
            String history = model.dumpHistory();
            if (started && history != null && !history.isEmpty()) {
                terminal.writer().print(history);
                terminal.writer().print("\r\n");
                terminal.writer().flush();
            }
        } catch (Exception ignored) {
        }
        try {
            terminal.close();
        } catch (Exception ignored) {
        }
    }
}
