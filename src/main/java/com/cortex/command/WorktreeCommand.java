package com.cortex.command;

import java.util.List;

/**
 * /worktree 命令（F24-F29）：Worktree 手动管理入口——create / list / enter / exit / remove。
 * LOCAL 命令（不进对话历史）；描述以 {@code [args]} 结尾标记接受尾随参数（阶段13 dispatcher 扩展）。
 * 通过 {@link Ui#worktreeAccessor()} 取能力；accessor 为 null（Worktree 未启用）时报错兜底。
 */
public final class WorktreeCommand {

    private WorktreeCommand() {}

    public static Command command() {
        return new Command("worktree", List.of(),
                "管理 Worktree 隔离目录: create <slug> | list | enter <slug> | exit [--remove] [--discard]"
                        + " | remove <slug> [--discard] [args]",
                Kind.LOCAL, false, WorktreeCommand::handle);
    }

    static void handle(Ui ui, String args) throws Exception {
        WorktreeAccessor wt = ui.worktreeAccessor();
        String trimmed = args == null ? "" : args.strip();
        String sub = trimmed.split("\\s+", 2)[0].strip().toLowerCase();
        String rest = trimmed.length() > sub.length() ? trimmed.substring(sub.length()).strip() : "";
        if (wt == null && List.of("create", "list", "", "enter", "exit", "remove").contains(sub)) {
            ui.error("Worktree 功能未启用（当前目录不是 git 仓库）");
            return;
        }
        switch (sub) {
            case "create" -> {
                if (rest.isEmpty()) {
                    ui.error("用法: /worktree create <slug>");
                    return;
                }
                try {
                    WorktreeSummary s = wt.create(rest);
                    ui.println("Worktree 已创建: " + s.path() + " (分支 " + s.branch() + ")");
                } catch (IllegalArgumentException e) {
                    ui.error("无效的名称，已拒绝: " + e.getMessage());
                }
            }
            case "list", "" -> {
                List<WorktreeSummary> all = wt.list();
                if (all.isEmpty()) {
                    ui.println("（当前没有 Worktree）");
                    return;
                }
                List<String> lines = all.stream()
                        .map(s -> s.name() + "  " + s.path() + "  " + s.branch()
                                + (s.manual() ? "  [manual]" : "")
                                + (s.active() ? "  [active]" : ""))
                        .toList();
                ui.println(String.join("\n", lines));
            }
            case "enter" -> {
                if (rest.isEmpty()) {
                    ui.error("用法: /worktree enter <slug>");
                    return;
                }
                try {
                    WorktreeSummary s = wt.enter(rest);
                    ui.println("已进入 " + s.name() + ": " + s.path()
                            + "（之后的文件操作在该目录内进行）");
                } catch (IllegalArgumentException e) {
                    ui.error("无效的名称，已拒绝: " + e.getMessage());
                }
            }
            case "exit" -> {
                boolean remove = rest.contains("--remove");
                boolean discard = rest.contains("--discard");
                try {
                    WorktreeAccessor.ExitResult r = wt.exitCurrent(remove, discard);
                    ui.println(r.removed()
                            ? "已退出并删除 Worktree: " + r.path() + "（分支 " + r.branch() + "）"
                            : "已退出 Worktree: " + r.path() + "（目录保留）");
                } catch (java.io.IOException e) {
                    ui.error(e.getMessage() != null ? e.getMessage() : e.toString());
                }
            }
            case "remove" -> {
                boolean discard = rest.contains("--discard");
                String slug = rest.replace("--discard", "").strip();
                if (slug.isEmpty()) {
                    ui.error("用法: /worktree remove <slug> [--discard]");
                    return;
                }
                try {
                    wt.remove(slug, discard);
                    ui.println("已删除 Worktree: " + slug);
                } catch (java.io.IOException e) {
                    ui.error(e.getMessage() != null ? e.getMessage() : e.toString());
                }
            }
            default -> ui.error("用法: /worktree create <slug> | list | enter <slug>"
                    + " | exit [--remove] [--discard] | remove <slug> [--discard]");
        }
    }
}
