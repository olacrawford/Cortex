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

    @TempDir
    Path userHome;

    private CortexModel app;
    private com.cortex.skill.SkillCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        ProviderConfig p = new ProviderConfig();
        p.setName("test");
        p.setProtocol("anthropic");
        p.setApiKey("sk-test");
        p.setModel("test-model");
        // 指向不可达的本地端口：测试中的回合会立即连接失败，不触外网
        p.setBaseUrl("http://127.0.0.1:1");
        // 阶段10：项目层放一个测试技能 + 用户层覆盖同名技能
        catalog = new com.cortex.skill.SkillCatalog();
        java.nio.file.Files.createDirectories(tmp.resolve(".cortex/skills/test-skill"));
        java.nio.file.Files.writeString(tmp.resolve(".cortex/skills/test-skill/SKILL.md"),
                "---\nname: test-skill\ndescription: 测试技能\n---\n请按步骤测试当前工作区。");
        java.nio.file.Files.createDirectories(tmp.resolve(".cortex/skills/args-skill"));
        java.nio.file.Files.writeString(tmp.resolve(".cortex/skills/args-skill/SKILL.md"),
                "---\nname: args-skill\ndescription: 参数技能\n---\n请分析：$ARGUMENTS");
        catalog.loadCatalog(tmp, userHome.resolve("skills"));

        app = new CortexModel(List.of(p), ToolRegistry.createDefault(),
                PermissionEngine.create(tmp, System.err), SessionRuntime.empty(200_000),
                null, null, "", "", tmp.resolve(".cortex/sessions"), catalog);
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
        assertTrue(viewAll.contains("/hooks"));
        assertTrue(viewAll.contains("↓ 8 more"));
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
        assertTrue(out.contains("test-skill"), "↓ 后回车应执行次条候选 skills（列出技能清单）");
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

    // ─── 阶段10：技能命令 ───

    @Test
    void 技能注册为skill命令并出现在help中() {
        assertTrue(app.skillNames().contains("test-skill"));
        type("/help");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("/test-skill"));
        assertTrue(out.contains("[skill]"), "技能命令 description 应以 [skill] 结尾（N7）");
    }

    @Test
    void 无参执行skill注入正文并提示成功() {
        type("/test-skill");
        UpdateResult<? extends Model> r = pressEnter();
        String out = stripAnsi(renderCommands(r.command()));
        assertTrue(out.contains("skill(test-skill) Successfully loaded skill"), "UI 应有成功提示（F12）");
        assertEquals(1, conv().size());
        assertEquals("请按步骤测试当前工作区。", conv().getMessages().get(0).getContent());
        assertTrue(containsTick(r.command()), "skill 命令应触发 LLM 回合");
    }

    @Test
    void 带参执行skill替换ARGUMENTS占位符() {
        type("/args-skill 分析性能问题");
        pressEnter();
        assertEquals(1, conv().size());
        assertEquals("请分析：分析性能问题", conv().getMessages().get(0).getContent(),
                "$ARGUMENTS 应被参数替换（F8）");
    }

    @Test
    void 无占位符技能带参追加UserRequest段() {
        type("/test-skill 检查日志");
        pressEnter();
        String body = conv().getMessages().get(0).getContent();
        assertTrue(body.contains("## User Request"));
        assertTrue(body.contains("检查日志"));
    }

    @Test
    void 内置命令带参仍按未命中处理() {
        type("/status xx");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("未知命令"), "非 skill 命令不接受参数（阶段9 F7 语义保留）");
        assertEquals(0, conv().size());
    }

    @Test
    void skills命令列出技能() {
        type("/skills");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("test-skill"));
        assertTrue(out.contains("args-skill"));
    }

    @Test
    void 内置命令优先于同名技能注册路径() {
        // wireSkillsToAgent 对已占用命令名跳过注册（registerSkillCommand 防冲突）：
        // /status 仍是内置行为而非任何技能
        type("/status");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("Mode:"));
    }

    // ─── 阶段11：Hook 集成 ───

    @Test
    void userPromptSubmit被hook拦截时不进对话且输入保留() throws Exception {
        ProviderConfig p = new ProviderConfig();
        p.setName("test");
        p.setProtocol("anthropic");
        p.setApiKey("sk-test");
        p.setModel("test-model");
        p.setBaseUrl("http://127.0.0.1:1");
        // UserPromptSubmit 拦截：prompt 含 delete（忽略大小写）→ exit 2 拒绝
        com.cortex.hook.HookEngine blocking = new com.cortex.hook.HookEngine(
                List.of(new com.cortex.hook.HookRule("warn-delete", com.cortex.hook.Event.USER_PROMPT_SUBMIT,
                        new com.cortex.hook.Condition(com.cortex.hook.CombineMode.ALL_OF, List.of(
                                new com.cortex.hook.AtomCondition("prompt",
                                        com.cortex.permission.Matchers.compile("~(?i)delete", false)))),
                        new com.cortex.hook.Action.Shell("echo \"prompt contains delete keyword\" >&2; exit 2"),
                        false, false, java.time.Duration.ofSeconds(5), "test")),
                List.of(), new com.cortex.hook.HookExecutor());
        com.cortex.skill.SkillCatalog emptyCatalog = new com.cortex.skill.SkillCatalog();
        Path emptyUser = java.nio.file.Files.createDirectories(tmp.resolve("user-skills-empty"));
        emptyCatalog.loadCatalog(tmp, emptyUser);
        CortexModel hooked = new CortexModel(List.of(p), ToolRegistry.createDefault(),
                PermissionEngine.create(tmp, System.err), SessionRuntime.empty(200_000),
                null, null, "", "", tmp.resolve(".cortex/sessions"), emptyCatalog, blocking);

        for (char c : "请帮我 delete 那个文件".toCharArray()) {
            hooked.update(new KeyPressMessage(String.valueOf(c), new char[]{c}));
        }
        UpdateResult<? extends Model> r = hooked.update(new KeyPressMessage("enter", new char[0]));
        String out = stripAnsi(renderCommands(r.command()));
        assertTrue(out.contains("[hook warn-delete]"), "拦截提示（F32）");
        assertTrue(out.contains("prompt contains delete keyword"));
        assertEquals(0, hooked.conversationForTest().size(), "被拦截消息不进对话历史");
        // 输入保留：view 中输入框仍是原文本（未消费）
        assertTrue(stripAnsi(hooked.view()).contains("delete 那个文件"), "输入框内容保留供重新编辑");
    }

    @Test
    void sessionStart注入的prompt进入runtime提醒队列() throws Exception {
        ProviderConfig p = new ProviderConfig();
        p.setName("test");
        p.setProtocol("anthropic");
        p.setApiKey("sk-test");
        p.setModel("test-model");
        p.setBaseUrl("http://127.0.0.1:1");
        com.cortex.skill.SkillCatalog emptyCatalog = new com.cortex.skill.SkillCatalog();
        Path emptyUser = java.nio.file.Files.createDirectories(tmp.resolve("user-skills-empty2"));
        emptyCatalog.loadCatalog(tmp, emptyUser);
        com.cortex.hook.HookEngine promptHook = new com.cortex.hook.HookEngine(
                List.of(new com.cortex.hook.HookRule("zh-cn", com.cortex.hook.Event.SESSION_START, null,
                        new com.cortex.hook.Action.Prompt("用 zh-CN 回复"),
                        false, false, java.time.Duration.ofSeconds(5), "test")),
                List.of(), new com.cortex.hook.HookExecutor());
        SessionRuntime runtime = SessionRuntime.empty(200_000);
        new CortexModel(List.of(p), ToolRegistry.createDefault(),
                PermissionEngine.create(tmp, System.err), runtime,
                null, null, "", "", tmp.resolve(".cortex/sessions"), emptyCatalog, promptHook);
        // activate 在构造时同步调用 dispatchSessionStart → prompt 已入队
        assertEquals(List.of("用 zh-CN 回复"), runtime.takeReminders(), "SessionStart 注入进 reminder 队列（AC6）");
        assertTrue(runtime.takeReminders().isEmpty(), "takeReminders 取走后清空（F21）");
    }

    @Test
    void hooks命令_无hook时输出NoHooksLoaded() {
        type("/hooks");
        String out = pressEnterAndCapture();
        assertTrue(out.contains("No hooks loaded."));
    }
}
