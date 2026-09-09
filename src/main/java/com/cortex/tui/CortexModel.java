package com.cortex.tui;

import com.cortex.agent.Agent;
import com.cortex.agent.AgentEvent;
import com.cortex.agent.ApprovalRequest;
import com.cortex.agent.CancelToken;
import com.cortex.agent.CompactEvent;
import com.cortex.agent.CompactPhase;
import com.cortex.agent.Phase;
import com.cortex.agent.SessionRuntime;
import com.cortex.agent.ToolEvent;
import com.cortex.command.Builtins;
import com.cortex.command.CommandRegistry;
import com.cortex.command.Dispatch;
import com.cortex.command.Kind;
import com.cortex.compact.Token;
import com.cortex.compact.state.SessionContext;
import com.cortex.config.ProviderConfig;
import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.ToolDef;
import com.cortex.memory.Manager;
import com.cortex.permission.Mode;
import com.cortex.permission.Outcome;
import com.cortex.permission.PermissionEngine;
import com.cortex.prompt.Prompt;
import com.cortex.session.SessionInfo;
import com.cortex.session.SessionList;
import com.cortex.session.SessionLoader;
import com.cortex.session.Writer;
import com.cortex.skill.Skill;
import com.cortex.skill.SkillCatalog;
import com.cortex.skill.SkillExecutor;
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


import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

/**
 * 主 TUI 模型：多 provider 选择、输入、Agent 单轮闭环事件渲染、spinner 计时、scrollback 提交与错误反馈。
 * 状态机：{@link AppState#PROVIDER_SELECT}（多 provider）→ {@link AppState#CHAT}。
 * 阶段9：斜杠命令统一走 {@link CommandRegistry} 分发（handler 仅依赖 {@link com.cortex.command.Ui} 抽象），
 * 输入首字符为 "/" 时弹出补全菜单。
 * 阶段10：实现 {@link com.cortex.skill.SkillHost}，provider 就绪后把技能注册为 [skill] 标记的 PROMPT 命令。
 * 阶段12：实现 {@link com.cortex.skill.SkillForkHost}（fork 走 SubAgent 底座 agent.LaunchFork）；
 * 持有 task.Manager 与 Agent 工具——consumeTaskDone 注入 &lt;task-notification&gt;、子 Agent 审批转发回主 TUI。
 */
public class CortexModel implements Model, com.cortex.command.Ui, com.cortex.skill.SkillForkHost {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(60);
    /** 工具结果摘要最多展示的行数（完整结果已回灌进对话历史）。 */
    private static final int SUMMARY_MAX_LINES = 8;

    /** 执行中的工具（用于动态区 Running… 指示；null 表示当前没有工具在跑）。 */
    private record ToolDisplay(String name, String args) {}

    private final List<ProviderConfig> providers;
    private final ToolRegistry registry;
    private final PermissionEngine engine;
    private final SessionRuntime runtime;
    private final Manager memMgr;
    private final String instructionText;
    private final String memoryText;
    private final Path sessionsDir;
    private ConversationManager conversation = new ConversationManager();

    // /resume 会话列表状态（ch09）
    private Writer writer;
    private List<SessionInfo> allResumeSessions = List.of();
    private List<SessionInfo> resumeList = List.of();
    private int resumeIndex;
    private String resumeFilter = "";

    // 斜杠命令体系（阶段9）：注册中心 + 补全菜单 + handler 执行期输出缓冲
    private final CommandRegistry cmdRegistry = new CommandRegistry();
    private final CompletionMenu completion = new CompletionMenu();
    /** handler 执行期间 Ui.println/error/动作方法缓冲的渲染命令，dispatchSlash 返回时统一带出。 */
    private List<Command> cmdOutputs = new ArrayList<>();
    /** injectAndSend 标记：handler 返回后由 dispatchSlash 统一开启 LLM 回合。 */
    private boolean cmdStartTurn;

    // 技能系统（阶段10）：编目中心（可空 = 技能未装配）+ inline 执行的激活态
    private final SkillCatalog skillCatalog;
    private final Set<String> activeSkillNames = new LinkedHashSet<>();
    /** allowed_tools 过滤器：inline 模式仅记录不生效（phase-10 Plan 决议，安全由权限引擎兜底）。 */
    private volatile java.util.function.Predicate<String> toolFilter;

    // Hook 系统（阶段11）：可空 = 未装配
    private final com.cortex.hook.HookEngine hookEngine;

    // SubAgent 系统（阶段12）：后台任务管理器 + Agent 工具（可空 = 未装配）
    private final com.cortex.task.Manager taskMgr;
    private final com.cortex.agent.AgentTool agentTool;
    /** 审批串行化闸：pending 弹窗之外的后续请求排队，杜绝互相覆盖丢请求。 */
    private final Object approvalGate = new Object();
    private final java.util.ArrayDeque<ApprovalRequest> approvalQueue = new java.util.ArrayDeque<>();

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
    private volatile ApprovalRequest pending;
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
        this(providers, registry, engine, runtime, null, null, "", "", Path.of("").toAbsolutePath(), null, null);
    }

    public CortexModel(List<ProviderConfig> providers, ToolRegistry registry, PermissionEngine engine,
                       SessionRuntime runtime, Writer writer, Manager memMgr,
                       String instructionText, String memoryText, Path sessionsDir) {
        this(providers, registry, engine, runtime, writer, memMgr, instructionText, memoryText,
                sessionsDir, null, null);
    }

    public CortexModel(List<ProviderConfig> providers, ToolRegistry registry, PermissionEngine engine,
                       SessionRuntime runtime, Writer writer, Manager memMgr,
                       String instructionText, String memoryText, Path sessionsDir,
                       SkillCatalog skillCatalog) {
        this(providers, registry, engine, runtime, writer, memMgr, instructionText, memoryText,
                sessionsDir, skillCatalog, null);
    }

    public CortexModel(List<ProviderConfig> providers, ToolRegistry registry, PermissionEngine engine,
                       SessionRuntime runtime, Writer writer, Manager memMgr,
                       String instructionText, String memoryText, Path sessionsDir,
                       SkillCatalog skillCatalog, com.cortex.hook.HookEngine hookEngine) {
        this(providers, registry, engine, runtime, writer, memMgr, instructionText, memoryText,
                sessionsDir, skillCatalog, hookEngine, null, null);
    }

    /** 阶段12 全参构造：额外接收后台任务管理器与 Agent 工具（均可空 = 未装配）。 */
    public CortexModel(List<ProviderConfig> providers, ToolRegistry registry, PermissionEngine engine,
                       SessionRuntime runtime, Writer writer, Manager memMgr,
                       String instructionText, String memoryText, Path sessionsDir,
                       SkillCatalog skillCatalog, com.cortex.hook.HookEngine hookEngine,
                       com.cortex.task.Manager taskMgr, com.cortex.agent.AgentTool agentTool) {
        this.providers = providers;
        this.registry = registry;
        this.engine = engine;
        this.runtime = runtime;
        this.writer = writer;
        this.memMgr = memMgr;
        this.instructionText = instructionText == null ? "" : instructionText;
        this.memoryText = memoryText == null ? "" : memoryText;
        this.sessionsDir = sessionsDir;
        this.skillCatalog = skillCatalog;
        this.hookEngine = hookEngine;
        this.taskMgr = taskMgr;
        this.agentTool = agentTool;
        // 阶段11：hook 引擎挂到 runtime，供 Agent 各 emit 点读取
        if (hookEngine != null) {
            runtime.hookEngine = hookEngine;
        }
        // 阶段9：注册 12 条内置命令；名字/别名冲突在启动期立即抛 IllegalStateException 终止启动（F2/N4）
        Builtins.registerAll(cmdRegistry);
        if (writer != null) {
            this.conversation = new ConversationManager(writer::onAppend, writer::onReplace);
        }
        this.mode = engine.startMode();
        if (providers.size() == 1) {
            this.state = AppState.CHAT;
            activate(providers.get(0));
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
        // 阶段12：任务完成通知消费线程 + 子 Agent 审批转发（T25/T26/F13）
        if (taskMgr != null) {
            taskMgr.setApprovalForwarder(this::upgradeApprovalFromSubAgent);
            Thread.ofVirtual().name("task-done-consumer").start(this::consumeTaskDone);
        }
    }

    // ─── SubAgent 集成（阶段12）───

    /**
     * 审批请求统一入口（F13）：主 Agent（事件流）与子 Agent（转发器）共用。
     * pending 空闲则直接弹窗，否则排队——保证任何请求都不会被覆盖丢失。
     */
    private void offerApproval(ApprovalRequest req) {
        synchronized (approvalGate) {
            if (pending == null) {
                pending = req;
            } else {
                approvalQueue.addLast(req);
            }
        }
        if (program != null) {
            program.send(new com.cortex.tui.tea.SubAgentApprovalMessage());
        }
    }

    /** 当前裁决完成 → 从队列晋升下一个待批准请求。 */
    private void promoteNextApproval() {
        synchronized (approvalGate) {
            pending = approvalQueue.pollFirst();
        }
        if (pending != null && program != null) {
            program.send(new com.cortex.tui.tea.SubAgentApprovalMessage());
        }
    }

    /**
     * 子 Agent 审批转发器（F13）：入队弹窗并阻塞等用户三选一。
     * 返回 empty（含中断兜底 DENY_ONCE 后）让子 Agent 回落默认路径。
     */
    private java.util.Optional<Outcome> upgradeApprovalFromSubAgent(ApprovalRequest req) {
        offerApproval(req);
        try {
            return java.util.Optional.of(req.respond().take());
        } catch (InterruptedException e) {
            req.respond().offer(Outcome.DENY_ONCE); // 中断兜底解阻塞，让子 Agent 以拒绝收尾
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        }
    }

    /** 任务完成通知消费（F16/F19）：done 队列阻塞读 → &lt;task-notification&gt; 进 runtime reminder 区。 */
    private void consumeTaskDone() {
        try {
            while (true) {
                String id = taskMgr.doneQueue().take();
                taskMgr.get(id).ifPresent(bt ->
                        runtime.appendReminders(List.of(com.cortex.tui.Tasks.buildTaskNotification(bt))));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 进程退出随虚拟线程终止
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
                            Command.println("就绪。输入 /help 查看可用命令。")));
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
        if (msg instanceof com.cortex.tui.tea.SubAgentApprovalMessage) {
            // 子 Agent 审批请求已抢占 pending：仅重绘 view 显示弹窗（F13）
            return new UpdateResult<>(this, null);
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
        promoteNextApproval();
        return new UpdateResult<>(this, null);
    }

    /** approving 态取消：兜底 DENY_ONCE 解 agent 阻塞，再取消本轮（不退出程序，N4）。 */
    private UpdateResult<? extends Model> cancelApproval() {
        if (pending != null) {
            pending.respond().offer(Outcome.DENY_ONCE);
        }
        promoteNextApproval();
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
        // /resume 会话列表导航（ch09）
        if (state == AppState.RESUMING) {
            return onResumeKey(key);
        }
        // 补全菜单激活时优先消费 ↑/↓/Tab/回车/ESC（F29~F32）
        if (state == AppState.CHAT && !streaming) {
            UpdateResult<? extends Model> menuHandled = handleCompletionKey(key);
            if (menuHandled != null) {
                return menuHandled;
            }
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
                            Command.println("就绪。输入 /help 查看可用命令。")));
                }
                return submit();
            }
            case "backspace" -> {
                if (!streaming && !input.isEmpty()) {
                    input = input.substring(0, input.length() - 1);
                    syncCompletionFromInput();
                }
                return new UpdateResult<>(this, null);
            }
            default -> {
                // 普通字符：拼入输入缓冲（流式期间忽略输入）
                if (!streaming) {
                    input += new String(key.runes());
                    syncCompletionFromInput();
                }
                return new UpdateResult<>(this, null);
            }
        }
    }

    // ─── 斜杠命令补全菜单（阶段9）───

    /** 输入内容变化后刷新补全菜单：首字符为 "/" 激活并前缀过滤，否则关闭（F24/F26）。 */
    private void syncCompletionFromInput() {
        completion.update(input, cmdRegistry);
    }

    /**
     * 菜单激活时的键位处理：消费返回非 null UpdateResult；返回 null 表示透传给输入编辑/提交路径。
     * 零匹配时回车透传（走未命中提示分支）、Tab/ESC 仅关闭菜单（F32b）。
     */
    private UpdateResult<? extends Model> handleCompletionKey(KeyPressMessage key) {
        if (!completion.active()) {
            return null;
        }
        switch (key.key()) {
            case "up" -> {
                completion.moveUp();
                return new UpdateResult<>(this, null);
            }
            case "down" -> {
                completion.moveDown();
                return new UpdateResult<>(this, null);
            }
            case "tab" -> {
                com.cortex.command.Command sel = completion.selected();
                completion.hide();
                if (sel != null) {
                    input = "/" + sel.name();
                    return submit();
                }
                return new UpdateResult<>(this, null);
            }
            case "enter" -> {
                com.cortex.command.Command sel = completion.selected();
                if (sel == null) {
                    return null; // 零匹配：回车走 submit 的未命中提示分支（F32b）
                }
                completion.hide();
                input = "/" + sel.name();
                return submit();
            }
            case "esc" -> {
                completion.hide(); // 输入内容保持不变，光标回到输入框（F31）
                return new UpdateResult<>(this, null);
            }
            default -> {
                return null; // 其余键透传给输入编辑（F32）
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
        agent.setMemory(memMgr, instructionText, memoryText);
        // 阶段12：Agent 工具回填主 Agent 引用（provider 可重选，activate 时刷新，T29）
        if (agentTool != null) {
            agentTool.setParent(agent);
        }
        if (memMgr != null) {
            memMgr.setProvider(client, provider.getModel());
        }
        if (writer != null) {
            writer.setModel(provider.getModel());
        }
        // 阶段10：provider 就绪后把技能注册为斜杠命令（F11）
        wireSkillsToAgent();
        // 阶段11：SessionStart——env 就绪、首条 user 消息之前（F9）
        dispatchSessionStart();
    }

    // ─── 技能系统（阶段10）───

    /** 把编目中心里的每个技能注册为 [skill] 标记的 PROMPT 命令；同名已占用时跳过（内置命令优先）。 */
    public void wireSkillsToAgent() {
        if (skillCatalog == null) {
            return;
        }
        for (Skill s : skillCatalog.list()) {
            registerSkillCommand(s.meta().name());
        }
    }

    private void registerSkillCommand(String name) {
        if (cmdRegistry.lookup(name).isPresent()) {
            return;
        }
        Skill s = skillCatalog.get(name);
        String desc = s != null && s.meta().description() != null && !s.meta().description().isBlank()
                ? s.meta().description().strip()
                : name + " 技能";
        cmdRegistry.register(new com.cortex.command.Command(name, List.of(), desc + " [skill]",
                com.cortex.command.Kind.PROMPT, false, ui -> executeSkillCommand(name, "", "/" + name)));
    }

    /** 执行 [skill] 命令：getFull 热加载正文（F4）→ inline 渲染注入 → 成功提示（F12）。 */
    private void executeSkillCommand(String name, String args, String label) {
        if (skillCatalog == null || skillCatalog.get(name) == null) {
            println("技能 " + name + " 不存在或未加载");
            return;
        }
        Skill skill = skillCatalog.getFull(name);
        if (skill.meta().isFork()) {
            println("技能 " + name + " 为 fork 模式,请通过 SkillExecutor.executeFork 程序化调用");
            return;
        }
        String rendered;
        try {
            rendered = SkillExecutor.executeInline(skill, args, this);
        } catch (IllegalStateException e) {
            error(e.getMessage());
            return;
        }
        if (rendered == null || rendered.isBlank()) {
            error("技能 " + name + " 正文为空");
            return;
        }
        injectAndSend(label, rendered);
        println("skill(" + name + ") Successfully loaded skill");
    }

    // ─── 提交一轮对话 ───
    private UpdateResult<? extends Model> submit() {
        String text = input.strip();
        if (text.isEmpty()) {
            // 空输入与纯空白早返回，不进分发器也不进 LLM（F5）
            input = "";
            return new UpdateResult<>(this, null);
        }
        Dispatch.Parsed parsed = Dispatch.parse(text);
        if (parsed.isSlash()) {
            input = "";
            return dispatchSlash(text, parsed);
        }
        // UserPromptSubmit：写入对话历史之前，可拦截（F9/F32）——被拦截则保留输入等用户重新编辑
        com.cortex.hook.DispatchResult hook = dispatchSessionEvent(
                com.cortex.hook.Event.USER_PROMPT_SUBMIT,
                Map.of("prompt", text));
        if (hook.blocked()) {
            String line = Styles.ERROR.apply("[hook " + hook.blockingHookName() + "] " + hook.reason());
            committed.add(line);
            return new UpdateResult<>(this, Command.println(line));
        }
        input = "";
        conversation.addUserMessage(text);
        String userLine = Styles.USER_PREFIX.apply("❯ ") + text;
        committed.add(userLine);
        return startTurn(userLine);
    }

    // ─── Hook 会话事件分派（阶段11）───

    /** 构造通用 payload（event/session_id/cwd/mode，F10）并合并特化字段。 */
    private com.cortex.hook.Payload basePayload(com.cortex.hook.Event event, Map<String, Object> extras) {
        Map<String, Object> data = new java.util.TreeMap<>();
        data.put("event", event.wireName());
        data.put("session_id", runtime.session != null ? runtime.session.sessionId() : "");
        data.put("cwd", System.getProperty("user.dir"));
        data.put("mode", mode == null ? "default" : mode.displayName());
        if (extras != null) {
            data.putAll(extras);
        }
        return new com.cortex.hook.Payload(data);
    }

    /** tui 侧会话事件分派（SessionStart/End/Resume/UserPromptSubmit）；注入 prompt 入 runtime 队列。 */
    private com.cortex.hook.DispatchResult dispatchSessionEvent(com.cortex.hook.Event event,
                                                                Map<String, Object> extras) {
        if (hookEngine == null) {
            return com.cortex.hook.DispatchResult.empty();
        }
        com.cortex.hook.DispatchResult result = hookEngine.dispatch(event, basePayload(event, extras));
        runtime.appendReminders(result.injectedPrompts());
        return result;
    }

    /** SessionStart：进入会话（启动 / /clear 之后）调用（F9）。 */
    private void dispatchSessionStart() {
        dispatchSessionEvent(com.cortex.hook.Event.SESSION_START, null);
    }

    /** SessionEnd：/clear、/resume 切换、/exit 之前调用（F9）。 */
    private void dispatchSessionEnd() {
        dispatchSessionEvent(com.cortex.hook.Event.SESSION_END, null);
    }

    /**
     * 斜杠命令分发（F3/F6）：lookup 命中后按 Kind 做 idle 守护（UI/PROMPT 仅 idle 可执行，N3a），
     * handler 异常兜底为错误提示，最后把 handler 缓冲的输出与待启动回合统一带出。
     */
    private UpdateResult<? extends Model> dispatchSlash(String text, Dispatch.Parsed parsed) {
        cmdOutputs = new ArrayList<>();
        cmdStartTurn = false;
        Optional<com.cortex.command.Command> cmdOpt = cmdRegistry.lookup(parsed.name());
        String args = "";
        if (cmdOpt.isEmpty() && text.strip().length() > 1) {
            // 阶段10：整串（含参数）未命中时按「命令 + 参数」拆分重试——仅 [skill] 命令接受参数。
            // Dispatch.parse 对带参输入返回空 name，因此这里从原始输入重新拆分。
            String body = text.strip().substring(1).strip();
            String[] parts = body.split("\\s+", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                Optional<com.cortex.command.Command> sk = cmdRegistry.lookup(parts[0].toLowerCase());
                if (sk.isPresent() && isSkillCommand(sk.get())) {
                    cmdOpt = sk;
                    args = parts[1].strip();
                }
            }
        }
        if (cmdOpt.isEmpty()) {
            // 退化输入（纯 "/" 或带参数）不拼悬空斜杠（N4 对应提示文案约束）
            String hint = parsed.name().isEmpty()
                    ? "未知命令,输入 /help 查看可用命令"
                    : "未知命令: /" + parsed.name() + ",输入 /help 查看可用命令";
            println(hint);
        } else {
            com.cortex.command.Command cmd = cmdOpt.get();
            if ((cmd.kind() == Kind.UI || cmd.kind() == Kind.PROMPT) && !idle()) {
                error("请等待当前任务完成");
            } else if (isSkillCommand(cmd)) {
                // [skill] 标记命令由分发器直接执行（args 已拆出），不走零参 handler
                executeSkillCommand(cmd.name(), args, text.strip());
            } else {
                try {
                    cmd.handler().handle(this);
                } catch (Exception e) {
                    error(e.getMessage() != null ? e.getMessage() : e.toString());
                }
            }
        }
        List<Command> outs = new ArrayList<>(cmdOutputs);
        if (cmdStartTurn) {
            outs.add(beginTurn());
        }
        if (outs.isEmpty()) {
            return new UpdateResult<>(this, null);
        }
        return new UpdateResult<>(this, Command.batch(outs.toArray(new Command[0])));
    }

    /** [skill] 标记识别（F11/N7）：注册技能命令时 description 以 "[skill]" 结尾。 */
    private static boolean isSkillCommand(com.cortex.command.Command cmd) {
        return cmd.description().endsWith("[skill]");
    }

    /** 启动一轮 Agent Loop：定型 user 行 + per-turn 取消句柄 + 事件队列 + tick 轮询。 */
    private UpdateResult<? extends Model> startTurn(String userLine) {
        return new UpdateResult<>(this, Command.batch(
                Command.println(userLine),
                beginTurn()));
    }

    /** Agent Loop 的共享状态准备（startTurn 与 injectAndSend 两条路径共用）；返回 tick 轮询命令。 */
    private Command beginTurn() {
        streamBuf = new StringBuilder();
        curTools.clear();
        iter = 0;
        streaming = true;
        requestStartMs = System.currentTimeMillis();
        tickCounter = 0;
        turnCancel = new CancelToken();
        // Agent 虚拟线程内跑 ReAct 循环（请求 → 权限判定 → 工具 → 回灌 → 下一轮……直到停止条件）
        agentQueue = agent.run(conversation, mode, turnCancel);
        return Command.tick(POLL_INTERVAL, t -> new StreamTickMessage());
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
                        String text = formatCompactNotice(compact);
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
                    case AgentEvent.Approval approval -> offerApproval(approval.request());
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
        } else if (state == AppState.RESUMING) {
            sb.append(renderResume());
        } else {
            renderChat(sb);
        }
        if (sb.length() == 0) {
            sb.append(" ");
        }
        return sb.toString();
    }

    private String renderResume() {
        StringBuilder sb = new StringBuilder();
        sb.append(bannerBlock());
        sb.append("\r\n选择要恢复的会话（输入字符搜索，Enter 恢复，Esc 取消）\r\n\r\n");
        if (resumeList.isEmpty()) {
            sb.append("  （无匹配会话）\r\n");
        }
        for (int i = 0; i < resumeList.size(); i++) {
            SessionInfo s = resumeList.get(i);
            String label = s.title() + "  ·  " + (s.model() == null ? "?" : s.model())
                    + "  ·  " + s.size() + "B";
            String marker = i == resumeIndex ? "▸ " : "  ";
            sb.append(marker)
                    .append(i == resumeIndex ? Styles.SELECT_ACTIVE.apply(label) : Styles.SELECT_IDLE.apply(label))
                    .append("\r\n");
        }
        if (!resumeFilter.isEmpty()) {
            sb.append("\r\n搜索: ").append(resumeFilter).append("\r\n");
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
        sb.append("\r\n");
        // 补全菜单紧贴输入框下方、状态栏上方（N6）
        for (String menuLine : completion.renderLines()) {
            sb.append(menuLine).append("\r\n");
        }
        sb.append(separatorLine()).append("\r\n");
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

    /** 统一渲染压缩状态提示文案（自动 / 紧急 / 手动三条路径共用）。 */
    static String formatCompactNotice(CompactEvent ev) {
        return switch (ev.phase()) {
            case BEFORE_AUTO -> "正在压缩上下文...";
            case BEFORE_EMERGENCY -> "上下文撞墙,自动压缩中...";
            case AFTER_AUTO, AFTER_EMERGENCY -> ev.error() != null
                    ? "压缩失败:" + ev.error().getMessage()
                    : "已压缩,token 从 " + ev.before() + " 降至 " + ev.after();
        };
    }

    // ─── Ui 接口实现（阶段9）：命令 handler 操作 TUI 的唯一通道 ───

    @Override
    public void println(String msg) {
        String line = Styles.MUTED.apply("⊕ " + msg);
        committed.add(line);
        cmdOutputs.add(Command.println(line));
    }

    @Override
    public void error(String msg) {
        String line = Styles.ERROR.apply("✖ " + msg);
        committed.add(line);
        cmdOutputs.add(Command.println(line));
    }

    @Override
    public com.cortex.permission.Mode mode() {
        return mode;
    }

    @Override
    public void quit() {
        // SessionEnd：进程退出前（F9）
        dispatchSessionEnd();
        cmdOutputs.add(Command.quit());
    }

    @Override
    public List<String> hookLines() {
        if (hookEngine == null || hookEngine.rules().isEmpty()) {
            return List.of();
        }
        // 按 event 分组（保留 yaml 声明顺序）：`  <name>  <event>  <action.type>  <flags>`（F34）
        Map<com.cortex.hook.Event, List<com.cortex.hook.HookRule>> grouped = new java.util.LinkedHashMap<>();
        for (com.cortex.hook.HookRule rule : hookEngine.rules()) {
            grouped.computeIfAbsent(rule.event(), e -> new ArrayList<>()).add(rule);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<com.cortex.hook.Event, List<com.cortex.hook.HookRule>> e : grouped.entrySet()) {
            lines.add(e.getKey().wireName() + ":");
            for (com.cortex.hook.HookRule rule : e.getValue()) {
                String flags = (rule.onlyOnce() ? " [once]" : "") + (rule.async() ? " [async]" : "");
                lines.add("  " + rule.name() + "  " + rule.action().typeName() + flags);
            }
        }
        return lines;
    }

    @Override
    public List<String> hookSources() {
        return hookEngine == null ? List.of() : hookEngine.sources();
    }

    @Override
    public void setMode(com.cortex.permission.Mode m) {
        this.mode = m;
    }

    @Override
    public void injectAndSend(String displayLabel, String presetPrompt) {
        conversation.addUserMessage(presetPrompt);
        String userLine = Styles.USER_PREFIX.apply("❯ ") + displayLabel;
        committed.add(userLine);
        cmdOutputs.add(Command.println(userLine));
        cmdStartTurn = true;
    }

    @Override
    public long usageIn() {
        return usageIn;
    }

    @Override
    public long usageOut() {
        return usageOut;
    }

    @Override
    public String modelName() {
        return activeProvider != null ? activeProvider.getModel() : "";
    }

    @Override
    public String cwd() {
        return System.getProperty("user.dir");
    }

    @Override
    public int toolCount() {
        return registry.count();
    }

    @Override
    public List<String> memoryFiles() {
        if (memMgr == null) {
            return List.of();
        }
        com.cortex.memory.Manager.Files files = memMgr.listFiles();
        List<String> all = new ArrayList<>(files.project());
        all.addAll(files.user());
        return List.copyOf(all);
    }

    @Override
    public List<String> skillNames() {
        return skillCatalog == null ? List.of() : List.copyOf(skillCatalog.names());
    }

    // ─── SkillHost 实现（阶段10）───

    @Override
    public void activateSkill(String name, String body) {
        activeSkillNames.add(name);
    }

    /** inline 模式仅记录过滤器，不真正切换主对话工具集（phase-10 Plan 决议，安全由权限引擎兜底）。 */
    @Override
    public void setToolFilter(java.util.function.Predicate<String> allowed) {
        this.toolFilter = allowed;
    }

    // ─── SkillForkHost 实现（阶段12 F33/AC17）：fork 走 SubAgent 底座 ───

    @Override
    public String runSubAgent(String body, java.util.List<com.cortex.conversation.Message> seed,
                              java.util.List<String> allowedTools, String model) {
        if (agent == null) {
            return "（子 Agent 未就绪：尚未连接 provider。）";
        }
        // model 覆盖本期不切换 provider（与 Agent 工具同策略，T17）
        try {
            return com.cortex.agent.LaunchFork.launch(agent, seed, body, allowedTools, new CancelToken());
        } catch (Agent.MaxTurnsReachedException mt) {
            return mt.lastAssistantText() == null || mt.lastAssistantText().isBlank()
                    ? "（子 Agent 达到最大轮数。）" : mt.lastAssistantText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "（子 Agent 被中断。）";
        } catch (Exception e) {
            return "（子 Agent 执行失败：" + (e.getMessage() != null ? e.getMessage() : e.toString()) + "）";
        }
    }

    @Override
    public java.util.List<com.cortex.conversation.Message> snapshotParentMessages() {
        return conversation.getMessages();
    }

    @Override
    public com.cortex.tool.ToolRegistry toolRegistry() {
        return registry;
    }

    @Override
    public String sessionPath() {
        return writer != null ? writer.path().toString() : "";
    }

    @Override
    public String sessionId() {
        return runtime.session != null ? runtime.session.sessionId() : "";
    }

    /** /compact：虚拟线程调 runForceCompact，完成后回投 UI 渲染系统消息。命令不写入对话历史。 */
    @Override
    public void forceCompact() {
        Thread.ofVirtual().start(() -> {
            List<ToolDef> defs = currentDefs();
            Agent.ForceCompactResult res = runForceCompact(defs);
            CompactEvent ev = res.error() != null
                    ? new CompactEvent(CompactPhase.AFTER_AUTO, 0, 0, res.error())
                    : new CompactEvent(CompactPhase.AFTER_AUTO, res.before(), res.after(), null);
            pushCompactNotice(formatCompactNotice(ev));
        });
    }

    /** /resume：进入会话列表恢复流程（idle 守护已由 dispatcher 按 Kind 统一完成）。 */
    @Override
    public void openResumeMenu() {
        try {
            List<SessionInfo> all = SessionList.list(sessionsDir);
            if (all.isEmpty()) {
                println("没有可恢复的会话");
                return;
            }
            this.allResumeSessions = all;
            this.resumeList = filterResume(all, "");
            this.resumeIndex = 0;
            this.resumeFilter = "";
            this.state = AppState.RESUMING;
            cmdOutputs.add(Command.println(renderResume()));
        } catch (IOException e) {
            error("会话扫描失败: " + e.getMessage());
        }
    }

    /** /clear：关旧 writer → 开新会话存档 → 重建 conversation 挂新 writer → 重置 runtime 压缩态与累计计数（F17）。 */
    @Override
    public void clearAndNewSession() {
        // SessionEnd：关闭旧会话之前（F9）
        dispatchSessionEnd();
        try {
            if (writer != null) {
                writer.close();
            }
            SessionContext newSesCtx = SessionContext.create(workspaceRoot());
            Writer newWriter = Writer.create(newSesCtx.sessionDir());
            newWriter.setModel(activeProvider != null ? activeProvider.getModel() : null);
            this.writer = newWriter;
            // 旧 writer 关闭后其 hook 已失效，必须重建 conversation 才能挂上新 writer
            this.conversation = new ConversationManager(newWriter::onAppend, newWriter::onReplace);
            runtime.resetForNewSession(newSesCtx);
            iter = 0;
            usageIn = 0;
            usageOut = 0;
            activeSkillNames.clear();
            committed.clear();
            println("已结束当前会话,开启新会话 " + newSesCtx.sessionId());
            // SessionStart：/clear 新建会话之后（F9）
            dispatchSessionStart();
        } catch (IOException e) {
            error("开启新会话失败: " + e.getMessage());
        }
    }

    @Override
    public boolean idle() {
        return state == AppState.CHAT && !streaming;
    }

    /** 渲染一条系统消息到 scrollback（后台压缩提示 / 恢复失败等路径）。不写入 conversation。 */
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

    /** 手动 /compact 调用入口（由 forceCompact 在后台线程触发）。 */
    Agent.ForceCompactResult runForceCompact(List<ToolDef> defs) {
        return agent.runForceCompact(conversation, defs);
    }

    // ─── 测试观察点（包私有）───
    ConversationManager conversationForTest() {
        return conversation;
    }

    /** 注入自定义命令用于分发行为单测（如 handler 抛异常兜底）。 */
    void registerForTest(com.cortex.command.Command cmd) {
        cmdRegistry.register(cmd);
    }

    /** 不启动 Agent 直接标记流式态，用于验证 UI/PROMPT 命令的 idle 守护。 */
    void markBusyForTest() {
        this.streaming = true;
    }

    // ─── /resume 会话列表导航（ch09）───

    private UpdateResult<? extends Model> onResumeKey(KeyPressMessage key) {
        switch (key.key()) {
            case "up", "left" -> {
                resumeIndex = (resumeIndex - 1 + resumeList.size()) % resumeList.size();
                return new UpdateResult<>(this, null);
            }
            case "down", "right" -> {
                resumeIndex = (resumeIndex + 1) % resumeList.size();
                return new UpdateResult<>(this, null);
            }
            case "enter" -> {
                if (resumeList.isEmpty()) {
                    return new UpdateResult<>(this, null);
                }
                return doResumeSession(resumeList.get(resumeIndex));
            }
            case "esc", "ctrl+c" -> {
                state = AppState.CHAT;
                return new UpdateResult<>(this, null);
            }
            case "backspace" -> {
                if (!resumeFilter.isEmpty()) {
                    resumeFilter = resumeFilter.substring(0, resumeFilter.length() - 1);
                    resumeList = filterResume(allResumeSessions, resumeFilter);
                    resumeIndex = 0;
                }
                return new UpdateResult<>(this, null);
            }
            default -> {
                String r = new String(key.runes());
                if (!r.isEmpty()) {
                    resumeFilter += r;
                    resumeList = filterResume(allResumeSessions, resumeFilter);
                    resumeIndex = 0;
                }
                return new UpdateResult<>(this, null);
            }
        }
    }

    private static List<SessionInfo> filterResume(List<SessionInfo> all, String filter) {
        if (filter == null || filter.isEmpty()) {
            return all;
        }
        String f = filter.toLowerCase();
        return all.stream().filter(s -> s.title().toLowerCase().contains(f)).toList();
    }

    private UpdateResult<? extends Model> doResumeSession(SessionInfo info) {
        // SessionEnd：切换离开旧会话之前（F9）
        dispatchSessionEnd();
        try {
            List<com.cortex.conversation.Message> loaded = SessionLoader.load(info.dir());
            // token 超限时先压缩一次
            int cw = runtime.contextWindow;
            if (!loaded.isEmpty() && cw > 20000 + 13000) {
                long est = Token.estimateTokens(0, loaded, 0);
                if (est >= cw - 20000 - 13000) {
                    try (Writer tmp = Writer.open(info.dir())) {
                        ConversationManager tmpConv = ConversationManager.fromMessages(
                                loaded, tmp::onAppend, tmp::onReplace);
                        agent.runForceCompact(tmpConv, currentDefs());
                        loaded = tmpConv.getMessages();
                    }
                }
            }
            // 时间跨度提醒（>6h）
            long hours = Duration.between(info.modifiedAt(), java.time.Instant.now()).toHours();
            if (hours > 6) {
                loaded = new ArrayList<>(loaded);
                loaded.add(new com.cortex.conversation.Message(com.cortex.conversation.Message.Role.USER,
                        "[系统提示] 本会话已暂停 " + hours + " 小时。部分上下文可能已过时，如需最新信息请重新读取相关文件。"));
            }
            Writer newWriter = Writer.open(info.dir());
            ConversationManager newConv = ConversationManager.fromMessages(
                    loaded, newWriter::onAppend, newWriter::onReplace);
            newWriter.setModel(activeProvider != null ? activeProvider.getModel() : null);
            this.writer = newWriter;
            this.conversation = newConv;
            this.runtime.session = SessionContext.open(workspaceRoot(), info.id());
            this.state = AppState.CHAT;
            String msg = "已恢复会话 " + info.id() + "，共 " + loaded.size() + " 条消息";
            committed.add(Styles.MUTED.apply("⊕ " + msg));
            // SessionResume：恢复完成、首条 user 消息之前（F9）
            dispatchSessionEvent(com.cortex.hook.Event.SESSION_RESUME, null);
            return new UpdateResult<>(this, Command.println(Styles.MUTED.apply("⊕ " + msg)));
        } catch (Exception e) {
            this.state = AppState.CHAT;
            return pushSystemMessage("恢复失败: " + e.getMessage());
        }
    }

    private Path workspaceRoot() {
        return sessionsDir.getParent() == null ? Path.of("").toAbsolutePath() : sessionsDir.getParent().getParent();
    }
}
