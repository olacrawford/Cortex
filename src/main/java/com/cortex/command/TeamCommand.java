package com.cortex.command;

import java.util.List;

/**
 * /team 命令（T27/F59-F62）：list / info / delete / kill；LOCAL 命令，[args] 标记接受尾随参数。
 */
public final class TeamCommand {

    private TeamCommand() {}

    public static Command command() {
        return new Command("team", List.of(),
                "团队管理: list | info <name> | delete <name> [--force] | kill <member> [args]",
                Kind.LOCAL, false, TeamCommand::handle);
    }

    static void handle(Ui ui, String args) throws Exception {
        TeamAccessor team = ui.teamAccessor();
        String trimmed = args == null ? "" : args.strip();
        String sub = trimmed.split("\\s+", 2)[0].strip().toLowerCase();
        String rest = trimmed.length() > sub.length() ? trimmed.substring(sub.length()).strip() : "";
        if (team == null) {
            ui.error("Team 功能未装配");
            return;
        }
        switch (sub) {
            case "list", "" -> {
                List<String> lines = team.list();
                ui.println(lines.isEmpty() ? "（当前没有团队）" : String.join("\n", lines));
            }
            case "info" -> {
                if (rest.isEmpty()) {
                    ui.error("用法: /team info <name>");
                    return;
                }
                List<String> lines = team.info(rest.strip());
                ui.println(lines.isEmpty() ? "团队 " + rest.strip() + " 不存在" : String.join("\n", lines));
            }
            case "delete" -> {
                boolean force = rest.contains("--force");
                String name = rest.replace("--force", "").strip();
                if (name.isEmpty()) {
                    ui.error("用法: /team delete <name> [--force]");
                    return;
                }
                team.delete(name, force);
                ui.println("已删除团队: " + name);
            }
            case "kill" -> {
                String member = rest.strip();
                if (member.isEmpty()) {
                    ui.error("用法: /team kill <member>");
                    return;
                }
                String teamName = team.teamOfMember(member);
                if (teamName == null) {
                    ui.error("未找到队员: " + member);
                    return;
                }
                team.kill(teamName, member);
                ui.println("已终止队员 " + member + "（团队 " + teamName + "）");
            }
            default -> ui.error("用法: /team list | info <name> | delete <name> [--force] | kill <member>");
        }
    }
}
