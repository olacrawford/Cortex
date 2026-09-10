package com.cortex.command;

import java.util.List;

/**
 * 内置命令元数据：名字（不带 "/" 前缀，全小写）、别名、一句描述、执行类型、是否隐藏、处理函数。
 * hidden 命令不出现在 /help 与补全菜单中，但 dispatcher 仍可命中（为未来 Skill 系统预留）。
 */
public record Command(
        String name,
        List<String> aliases,
        String description,
        Kind kind,
        boolean hidden,
        Handler handler) {

    public Command {
        aliases = List.copyOf(aliases);
    }

    /**
     * 命令处理函数：只依赖 {@link Ui} 抽象，不持有具体 TUI 类型（F33/F35）。
     *
     * @param args 命令名之后的尾随参数串（"/worktree create x" 传 "create x"；无参传空串）。
     *             仅描述以 "[args]" 结尾（或 [skill]）的命令会由 dispatcher 以带参形式命中。
     */
    @FunctionalInterface
    public interface Handler {
        void handle(Ui ui, String args) throws Exception;
    }
}
