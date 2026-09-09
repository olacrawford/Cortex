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

    /** /skills：列出已安装技能名（阶段10）；为空时给出放置路径引导。 */
    public static Command.Handler skills() {
        return ui -> {
            List<String> names = ui.skillNames();
            if (names.isEmpty()) {
                ui.println("无已安装技能。\n\n把技能目录（含 SKILL.md）放到 .cortex/skills/ 或 ~/.cortex/skills/，"
                        + "或用 install_skill 工具从远程安装。");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                sb.append(names.get(i));
                if (i < names.size() - 1) {
                    sb.append("\n");
                }
            }
            ui.println(sb.toString());
        };
    }

    /** /hooks：按 event 分组列出已加载 hook 与加载来源（阶段11 F34/F35）。 */
    public static Command.Handler hooks() {
        return ui -> {
            List<String> lines = ui.hookLines();
            if (lines.isEmpty()) {
                ui.println("No hooks loaded.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                sb.append(lines.get(i));
                if (i < lines.size() - 1) {
                    sb.append("\n");
                }
            }
            sb.append("\n\nLoaded from: ").append(String.join(", ", ui.hookSources()));
            ui.println(sb.toString());
        };
    }
}
