package com.cortex.tui;

import com.cortex.agent.Agent;
import com.cortex.agent.CompactEvent;
import com.cortex.agent.CompactPhase;
import com.cortex.llm.ToolDef;
import com.cortex.tui.tea.Model;
import com.cortex.tui.tea.UpdateResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TUI 内置命令分发（F21）：以 {@code /} 开头的输入走命令路径，不发送给 LLM。
 * 已有 {@code /exit}、{@code /plan}、{@code /do} 迁移到统一注册表 {@link #BUILTIN_COMMANDS}，
 * 并新增 {@code /compact}；未注册命令走未知命令兜底，给出可用命令提示。
 */
public final class Commands {

    private Commands() {}

    @FunctionalInterface
    public interface CommandHandler {
        UpdateResult<? extends Model> handle(CortexModel app);
    }

    /** 可用命令提示（固定顺序，便于展示）。 */
    public static final String AVAILABLE_HINT = "/exit /plan /do /compact /resume";

    public static final Map<String, CommandHandler> BUILTIN_COMMANDS = Map.of(
            "/exit", Commands::handleExit,
            "/plan", Commands::handlePlan,
            "/do", Commands::handleDo,
            "/compact", Commands::handleCompact,
            "/resume", Commands::handleResume);

    /** 检查输入是否以 "/" 开头；命中返回对应命令处理器，未注册返回未知命令 handler。 */
    public static Optional<CommandHandler> dispatchCommand(String input) {
        if (input == null || !input.startsWith("/")) {
            return Optional.empty();
        }
        CommandHandler h = BUILTIN_COMMANDS.get(input);
        if (h != null) {
            return Optional.of(h);
        }
        return Optional.of(app -> app.pushSystemMessage("未知命令: " + input + ",可用命令: " + AVAILABLE_HINT));
    }

    static UpdateResult<? extends Model> handleExit(CortexModel app) {
        return app.commandExit();
    }

    static UpdateResult<? extends Model> handlePlan(CortexModel app) {
        return app.commandPlan();
    }

    static UpdateResult<? extends Model> handleDo(CortexModel app) {
        return app.commandDo();
    }

    /** /resume：进入会话列表恢复流程（仅空闲态可用）。 */
    static UpdateResult<? extends Model> handleResume(CortexModel app) {
        return app.commandResume();
    }

    /** /compact：虚拟线程调 runForceCompact，完成后回投 UI 渲染系统消息。命令不写入对话历史。 */
    static UpdateResult<? extends Model> handleCompact(CortexModel app) {
        Thread.ofVirtual().start(() -> {
            List<ToolDef> defs = app.currentDefs();
            Agent.ForceCompactResult res = app.runForceCompact(defs);
            CompactEvent ev = res.error() != null
                    ? new CompactEvent(CompactPhase.AFTER_AUTO, 0, 0, res.error())
                    : new CompactEvent(CompactPhase.AFTER_AUTO, res.before(), res.after(), null);
            app.pushCompactNotice(formatCompactNotice(ev));
        });
        return new UpdateResult<>(app, null);
    }

    /** 统一渲染压缩状态提示文案（自动 / 紧急 / 手动三条路径共用）。 */
    public static String formatCompactNotice(CompactEvent ev) {
        return switch (ev.phase()) {
            case BEFORE_AUTO -> "正在压缩上下文...";
            case BEFORE_EMERGENCY -> "上下文撞墙,自动压缩中...";
            case AFTER_AUTO, AFTER_EMERGENCY -> ev.error() != null
                    ? "压缩失败:" + ev.error().getMessage()
                    : "已压缩,token 从 " + ev.before() + " 降至 " + ev.after();
        };
    }
}
