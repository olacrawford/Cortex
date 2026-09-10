package com.cortex.tui;

import com.cortex.command.WorktreeAccessor;
import com.cortex.command.WorktreeSummary;
import com.cortex.worktree.ExitAction;
import com.cortex.worktree.ExitOptions;
import com.cortex.worktree.ExitReport;
import com.cortex.worktree.Worktree;
import com.cortex.worktree.WorktreeManager;
import com.cortex.worktree.WorktreeSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link WorktreeAccessor} 的 TUI 适配（T14/F24-F28）：转发 {@link WorktreeManager}，
 * 并在 enter/exit/remove 时同步 TUI 的 activeCwd——activeCwd 非空时主 Agent 每轮
 * 把它作为 explicit cwd 注入工具调用（F18/F28）。
 */
public final class TuiWorktreeAccessor implements WorktreeAccessor {

    private final WorktreeManager mgr;
    private final Consumer<Path> activeCwdSetter;
    private final Supplier<Path> activeCwdGetter;

    public TuiWorktreeAccessor(WorktreeManager mgr, Consumer<Path> activeCwdSetter,
                               Supplier<Path> activeCwdGetter) {
        this.mgr = mgr;
        this.activeCwdSetter = activeCwdSetter;
        this.activeCwdGetter = activeCwdGetter;
    }

    @Override
    public WorktreeSummary create(String slug) throws Exception {
        Worktree wt = mgr.create(slug, "HEAD", true); // manual=true：手动创建不走自动清理（G13）
        return toSummary(wt);
    }

    @Override
    public List<WorktreeSummary> list() {
        return mgr.list().stream().map(this::toSummary).toList();
    }

    @Override
    public WorktreeSummary enter(String slug) throws Exception {
        WorktreeSession session = mgr.enter(slug);
        activeCwdSetter.accept(Files.exists(Path.of(session.worktreePath()))
                ? Path.of(session.worktreePath()) : null);
        Worktree wt = mgr.get(slug).orElse(null);
        return wt != null ? toSummary(wt)
                : new WorktreeSummary(slug, session.worktreePath(), "", false, true);
    }

    @Override
    public ExitResult exitCurrent(boolean remove, boolean discard) throws Exception {
        WorktreeSession cur = mgr.currentSession();
        if (cur == null) {
            throw new IOException("当前不在任何 worktree 中");
        }
        ExitReport report = mgr.exit(cur.worktreeName(),
                remove ? ExitAction.REMOVE : ExitAction.KEEP, new ExitOptions(discard));
        activeCwdSetter.accept(null); // 还原为 JVM 当前目录（F12-3/F28）
        return new ExitResult(report.removed(), report.path(), report.branch());
    }

    @Override
    public void remove(String slug, boolean discard) throws Exception {
        Path removed = mgr.get(slug).map(Worktree::path).orElse(null);
        mgr.remove(slug, new ExitOptions(discard));
        Path cur = activeCwdGetter.get();
        if (removed != null && cur != null
                && cur.toAbsolutePath().normalize().equals(removed.toAbsolutePath().normalize())) {
            activeCwdSetter.accept(null);
        }
    }

    private WorktreeSummary toSummary(Worktree wt) {
        WorktreeSession session = mgr.currentSession();
        boolean active = session != null && session.worktreePath().equals(wt.path().toString());
        return new WorktreeSummary(wt.name(), wt.path().toString(), wt.branch(), wt.manual(), active);
    }
}
