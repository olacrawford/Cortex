package com.cortex.tui;

import com.cortex.agent.SessionRuntime;
import com.cortex.config.ProviderConfig;
import com.cortex.conversation.ConversationManager;
import com.cortex.permission.Mode;
import com.cortex.permission.PermissionEngine;
import com.cortex.tool.ToolRegistry;
import com.cortex.tui.tea.Command;
import com.cortex.tui.tea.KeyPressMessage;
import com.cortex.tui.tea.Model;
import com.cortex.tui.tea.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段9 斜杠命令分发端到端单测：真实 CortexModel 上驱动按键，验证分发路由、
 * 三类命令守卫与补全菜单键位（不触网——仅覆盖不启动 LLM 回合的命令）。
 */
class SlashDispatchTest {

    @TempDir
    Path tmp;

    private CortexModel app;

    @BeforeEach
    void setUp() {
        ProviderConfig p = new ProviderConfig();
        p.setName("test");
        p.setProtocol("anthropic");
        p.setApiKey("sk-test");
        p.setModel("test-model");
        // 指向不可达的本地端口：测试中的回合会立即连接失败，不触外网
        p.setBaseUrl("http://127.0.0.1:1");
        app = new CortexModel(List.of(p), ToolRegistry.createDefault(),
                PermissionEngine.create(tmp, System.err), SessionRuntime.empty(200_000),
                null, null, "", "", tmp.resolve(".cortex/sessions"));
        // 触发一次窗口尺寸消息让模型进入就绪态
        app.update(new com.cortex.tui.tea.WindowSizeMessage(120, 40));
    }

    // ─── 按键驱动 ───

    private void type(String text) {
        for (char c : text.toCharArray()) {
            app.update(new KeyPressMessage(String.valueOf(c), new char[]{c}));
        }
    }

    private UpdateResult<? extends Model> pressEnter() {
        return app.update(new KeyPressMessage("enter", new char[0]));
    }

    private String pressEnterAndCapture() {
        return stripAnsi(renderCommands(pressEnter().command()));
    }

    private static String renderCommands(Command cmd) {
        StringBuilder sb = new StringBuilder();
        collect(cmd, sb);
        return sb.toString();
    }

    private static void collect(Command c, StringBuilder sb) {
        if (c instanceof Command.Batch b) {
            for (Command x : b.commands()) {
                collect(x, sb);
            }
        } else if (c instanceof Command.Println p) {
            sb.append(p.text()).append('\n');
        }
    }

    private static boolean containsTick(Command cmd) {
        if (cmd instanceof Command.Tick) {
            return true;
        }
        if (cmd instanceof Command.Batch b) {
            return b.commands().stream().anyMatch(SlashDispatchTest::containsTick);
        }
        return false;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    private ConversationManager conv() {
        return app.conversationForTest();
    }

    // ─── 分发路由 ───

    @Test
    void help_列出12条命令_不进对话不触发回合() {
        type("/help");
        UpdateResult<? extends Model> r = pressEnter();
        String out = stripAnsi(renderCommands(r.command()));
        for (String n : List.of("clear", "compact", "do", "exit", "help", "memory",
                "permission", "plan", "resume", "review", "session", "status")) {
            assertTrue(out.contains("/" + n), "缺命令: " + n);
        }
        assertEquals(0, conv().size());
        assertFalse(containsTick(r.command()), "纯本地命令不得触发 Agent 回合");
    }

    @Test
    void unknownCommand_友好提示_不触发回合() {
        type("/foobar");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("未知命令"));
        assertTrue(out.contains("/help"));
        assertEquals(0, conv().size());
    }

    @Test
    void 大小写不敏感_Help与help一致() {
        type("/Help");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("/status"));
        assertEquals(0, conv().size());
    }

    @Test
    void 带参数的斜杠按未命中处理() {
        type("/help xx");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("未知命令"));
        assertEquals(0, conv().size());
    }

    @Test
    void 空回车与纯空白不进任何路径() {
        UpdateResult<? extends Model> emptyEnter = pressEnter();
        assertEquals(0, conv().size());
        type("   ");
        pressEnter();
        assertEquals(0, conv().size());
        String out = stripAnsi(renderCommands(emptyEnter.command() == null ? null : emptyEnter.command()));
        assertFalse(out.contains("未知命令"));
    }

    @Test
    void 普通文本写入对话并触发回合() {
        type("你好");
        UpdateResult<? extends Model> r = pressEnter();
        assertEquals(1, conv().size());
        assertTrue(containsTick(r.command()), "普通文本应触发 Agent tick 轮询");
    }

    // ─── Kind 守卫 ───

    @Test
    void plan本地命令_切模式不触发回合() {
        type("/plan");
        pressEnter();
        assertEquals(Mode.PLAN, app.mode());
        assertEquals(0, conv().size());
    }

    @Test
    void 非idle时UI命令被拒() {
        type("/plan");
        app.markBusyForTest();
        String out = pressEnterAndCapture();
        assertTrue(out.contains("请等待当前任务完成"));
        assertEquals(Mode.DEFAULT, app.mode(), "busy 时模式不得切换");
    }

    @Test
    void session与memory与status本地输出() {
        type("/session");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("Session:"));
        assertTrue(out.contains("Path:"));

        type("/memory");
        out = pressEnterAndCapture();
        assertTrue(out.contains("无已加载的记忆文件"));

        type("/status");
        out = pressEnterAndCapture();
        assertTrue(out.contains("Mode:"));
        assertTrue(out.contains("Tokens:"));
        assertTrue(out.contains("Tools:"));
        assertTrue(out.contains("Memories:"));
        assertTrue(out.contains("Model:"));
        assertTrue(out.contains("Directory:"));
    }

    // ─── 补全菜单键位 ───

    @Test
    void 补全菜单_斜杠激活_前缀过滤() {
        type("/");
        String viewAll = stripAnsi(app.view());
        // 12 条候选超过 MAX_ROWS=8：首屏可见前 8 条 + 滚动提示（N5），第 9 条起的 /resume 不可见
        assertTrue(viewAll.contains("/clear"));
        assertTrue(viewAll.contains("/plan"));
        assertTrue(viewAll.contains("↓ 4 more"));
        assertFalse(viewAll.contains("/session"), "超出首屏的候选应被滚动窗口隐藏");

        type("s");
        String view = stripAnsi(app.view());
        assertTrue(view.contains("/session"));
        assertTrue(view.contains("/status"));
        assertFalse(view.contains("/clear"), "前缀过滤后不匹配项应消失");
    }

    @Test
    void 补全菜单_回车执行高亮项() {
        type("/stat");
        // 唯一匹配 status，直接回车执行
        String out = pressEnterAndCapture();
        assertTrue(out.contains("Mode:"));
        assertTrue(stripAnsi(app.view()).contains("Send a message..."), "执行后输入框应清空");
    }

    @Test
    void 补全菜单_esc关闭保留输入() {
        type("/s");
        app.update(new KeyPressMessage("esc", new char[0]));
        assertFalse(app.view().contains("/session"), "ESC 后菜单应消失");
    }

    @Test
    void 补全菜单_退格清空后消失() {
        type("/s");
        app.update(new KeyPressMessage("backspace", new char[0]));
        app.update(new KeyPressMessage("backspace", new char[0]));
        assertFalse(app.view().contains("/status"));
    }

    @Test
    void 补全菜单_上下键移动高亮后回车执行次条() {
        type("/s"); // 候选：session, status；高亮在 session
        app.update(new KeyPressMessage("down", new char[0]));
        String out = pressEnterAndCapture();
        assertTrue(out.contains("Mode:"), "↓ 后回车应执行 status（输出 Mode: 行）");
    }

    @Test
    void 补全菜单_零匹配时回车走未命中提示() {
        type("/zzz");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("未知命令"));
    }

    @Test
    void handler抛异常时兜底为错误提示() {
        app.registerForTest(new com.cortex.command.Command("boom", List.of(), "会抛异常的命令",
                com.cortex.command.Kind.LOCAL, false,
                ui -> {
                    throw new IllegalStateException("炸了");
                }));
        type("/boom");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("✖"), "handler 异常应渲染为错误提示");
        assertTrue(out.contains("炸了"));
    }
}
