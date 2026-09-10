package com.cortex.command;

import com.cortex.permission.Mode;
import com.cortex.prompt.Reminder;

/** 2 条提示词命令（F11）：/do /review——追加固定文本 user 消息并立即触发 LLM 回合。 */
public final class BuiltinPrompt {

    /** /review 注入的固定审查请求文案（不读 git diff、不收集外部上下文，F23）。 */
    public static final String REVIEW_DIRECTIVE =
            "请审查当前上下文中的代码变更与已读取的文件，指出潜在 bug、可读性问题和可简化处。";

    private BuiltinPrompt() {}

    /** /do：先切回默认模式，再注入执行指令并触发回合（F14），外部行为与实施前一致。 */
    public static Command.Handler doRun() {
        return (ui, args) -> {
            ui.setMode(Mode.DEFAULT);
            ui.injectAndSend(Reminder.EXECUTE_DIRECTIVE, Reminder.EXECUTE_DIRECTIVE);
        };
    }

    /** /review：注入固定审查请求并触发回合（F23）。 */
    public static Command.Handler review() {
        return (ui, args) -> ui.injectAndSend("/review", REVIEW_DIRECTIVE);
    }
}
