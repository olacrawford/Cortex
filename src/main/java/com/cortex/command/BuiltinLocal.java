package com.cortex.command;

import java.util.List;

/** 5 条纯本地命令（F9）：/help /status /memory /permission /session。 */
public final class BuiltinLocal {

    private BuiltinLocal() {}

    /** /help：按命令名字典序输出"<命令>  <描述>"两列对齐清单（F18）。reg 由 registerAll 闭包注入。 */
    public static Command.Handler help(CommandRegistry reg) {
        return ui -> {
            List<Command> cmds = reg.visible();
            int width = 0;
            for (Command c : cmds) {
                width = Math.max(width, c.name().length() + 2);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cmds.size(); i++) {
                Command c = cmds.get(i);
                sb.append(String.format("%-" + width + "s", "/" + c.name())).append(c.description());
                if (i < cmds.size() - 1) {
                    sb.append("\n");
                }
            }
            ui.println(sb.toString());
        };
    }

    /** /status：固定顺序输出 6 行 key:value（F19/N8）。 */
    public static Command.Handler status() {
        return ui -> ui.println(String.join("\n",
                "Mode:      " + ui.mode().displayName(),
                "Tokens:    " + ui.usageIn() + " in / " + ui.usageOut() + " out",
                "Tools:     " + ui.toolCount() + " enabled",
                "Memories:  " + ui.memoryFiles().size() + " files",
                "Model:     " + ui.modelName(),
                "Directory: " + ui.cwd()));
    }

    /** /memory：逐行输出已加载记忆文件名；为空时给提示（F20）。 */
    public static Command.Handler memory() {
        return ui -> {
            List<String> files = ui.memoryFiles();
            if (files.isEmpty()) {
                ui.println("无已加载的记忆文件");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                sb.append(files.get(i));
                if (i < files.size() - 1) {
                    sb.append("\n");
                }
            }
            ui.println(sb.toString());
        };
    }

    /** /permission：输出当前权限模式名称（与 /status 的 Mode 值同形式，F21）。 */
    public static Command.Handler permission() {
        return ui -> ui.println(ui.mode().displayName());
    }

    /** /session：输出当前会话标识与存档路径（F22）。 */
    public static Command.Handler session() {
        return ui -> ui.println("Session: " + ui.sessionId() + "\nPath: " + ui.sessionPath());
    }
}
