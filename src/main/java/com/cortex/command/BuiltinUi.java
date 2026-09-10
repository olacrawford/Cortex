package com.cortex.command;

import com.cortex.permission.Mode;

/** 5 条影响界面命令（F10）：/exit /plan /compact /resume /clear。idle 守护由 dispatcher 统一完成。 */
public final class BuiltinUi {

    private BuiltinUi() {}

    /** /exit：关闭 TUI 进程（F12）。 */
    public static Command.Handler exit() {
        return (ui, args) -> ui.quit();
    }

    /** /plan：切换到计划模式（F13）。 */
    public static Command.Handler plan() {
        return (ui, args) -> {
            ui.setMode(Mode.PLAN);
            ui.println("已进入计划模式：模型仅可用只读工具产出计划，用 /do 批准执行");
        };
    }

    /** /compact：手动触发上下文压缩（F15）。 */
    public static Command.Handler compact() {
        return (ui, args) -> ui.forceCompact();
    }

    /** /resume：打开历史会话列表（F16），行为沿用 ch09。 */
    public static Command.Handler resume() {
        return (ui, args) -> ui.openResumeMenu();
    }

    /** /clear：关闭旧会话存档、开新会话、清空内存消息与累计计数（F17）。 */
    public static Command.Handler clear() {
        return (ui, args) -> ui.clearAndNewSession();
    }
}
