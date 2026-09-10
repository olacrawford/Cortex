package com.cortex.command;

import com.cortex.worktree.GitTestSupport;

import static org.junit.jupiter.api.Assertions.*;
import com.cortex.worktree.WorktreeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** /worktree 命令分发（T13/F24-F29）：子命令解析、null accessor 兜底、输出文案。 */
class WorktreeCommandTest {

    @TempDir
    Path tmp;

    /** 可观测 Ui 桩。 */
    private static final class StubUi implements Ui {
        final List<String> prints = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        WorktreeAccessor accessor;

        @Override public void println(String msg) { prints.add(msg); }
        @Override public void error(String msg) { errors.add(msg); }
        @Override public com.cortex.permission.Mode mode() { return com.cortex.permission.Mode.DEFAULT; }
        @Override public void setMode(com.cortex.permission.Mode m) {}
        @Override public void injectAndSend(String label, String prompt) {}
        @Override public long usageIn() { return 0; }
        @Override public long usageOut() { return 0; }
        @Override public String modelName() { return ""; }
        @Override public String cwd() { return ""; }
        @Override public int toolCount() { return 0; }
        @Override public List<String> memoryFiles() { return List.of(); }
        @Override public String sessionPath() { return ""; }
        @Override public String sessionId() { return ""; }
        @Override public List<String> skillNames() { return List.of(); }
        @Override public List<String> hookLines() { return List.of(); }
        @Override public List<String> hookSources() { return List.of(); }
        @Override public WorktreeAccessor worktreeAccessor() { return accessor; }
        @Override public void quit() {}
        @Override public void forceCompact() {}
        @Override public void openResumeMenu() {}
        @Override public void clearAndNewSession() {}
        @Override public boolean idle() { return true; }
    }

    /** 基于 WorktreeManager 的简易 accessor（与 TuiWorktreeAccessor 同构，测试不依赖 tui 包）。 */
    private static final class SimpleAccessor implements WorktreeAccessor {
        final WorktreeManager mgr;

        SimpleAccessor(WorktreeManager mgr) { this.mgr = mgr; }

        @Override public WorktreeSummary create(String slug) throws Exception {
            var wt = mgr.create(slug, "HEAD", true);
            return new WorktreeSummary(wt.name(), wt.path().toString(), wt.branch(), true, false);
        }

        @Override public List<WorktreeSummary> list() {
            return mgr.list().stream()
                    .map(w -> new WorktreeSummary(w.name(), w.path().toString(), w.branch(), w.manual(), false))
                    .toList();
        }

        @Override public WorktreeSummary enter(String slug) throws Exception {
            mgr.enter(slug);
            return mgr.get(slug).map(w -> new WorktreeSummary(w.name(), w.path().toString(),
                    w.branch(), w.manual(), true)).orElseThrow();
        }

        @Override public ExitResult exitCurrent(boolean remove, boolean discard) throws Exception {
            var cur = mgr.currentSession();
            if (cur == null) {
                throw new java.io.IOException("当前不在任何 worktree 中");
            }
            var r = mgr.exit(cur.worktreeName(),
                    remove ? com.cortex.worktree.ExitAction.REMOVE : com.cortex.worktree.ExitAction.KEEP,
                    new com.cortex.worktree.ExitOptions(discard));
            return new ExitResult(r.removed(), r.path(), r.branch());
        }

        @Override public void remove(String slug, boolean discard) throws Exception {
            mgr.remove(slug, new com.cortex.worktree.ExitOptions(discard));
        }
    }

    private WorktreeManager mgr() throws Exception {
        return new WorktreeManager(GitTestSupport.initRepo(tmp.resolve("repo-" + System.nanoTime())));
    }

    private static Command worktreeCommand() {
        var reg = new com.cortex.command.CommandRegistry();
        Builtins.registerAll(reg);
        return reg.lookup("worktree").orElseThrow();
    }

    @Test
    void create_list_remove子命令() throws Exception {
        StubUi ui = new StubUi();
        ui.accessor = new SimpleAccessor(mgr());

        Command cmd = worktreeCommand();
        cmd.handler().handle(ui, "create demo-feature");
        assertEquals(1, ui.prints.size());
        assertTrue(ui.prints.get(0).contains("Worktree 已创建"), ui.prints.get(0));
        assertTrue(ui.prints.get(0).contains("worktree-demo-feature"));

        ui.prints.clear();
        cmd.handler().handle(ui, "list");
        assertTrue(ui.prints.get(0).contains("demo-feature"));
        assertTrue(ui.prints.get(0).contains("[manual]"), "手动创建应带 [manual] 标记");

        ui.prints.clear();
        cmd.handler().handle(ui, "remove demo-feature --discard");
        assertTrue(ui.prints.get(0).contains("已删除"));
    }

    @Test
    void enter_exit带变更保护() throws Exception {
        StubUi ui = new StubUi();
        WorktreeManager mgr = mgr();
        ui.accessor = new SimpleAccessor(mgr);
        Command cmd = worktreeCommand();

        cmd.handler().handle(ui, "create wt-x");
        cmd.handler().handle(ui, "enter wt-x");
        assertTrue(ui.prints.stream().anyMatch(s -> s.contains("已进入 wt-x")));

        // 制造变更后 exit --remove（无 discard）→ 错误
        Files.writeString(mgr.get("wt-x").orElseThrow().path().resolve("x.txt"), "x");
        cmd.handler().handle(ui, "exit --remove");
        assertTrue(ui.errors.stream().anyMatch(s -> s.contains("未提交修改") || s.contains("拒绝删除")),
                "应报变更保护: " + ui.errors);

        cmd.handler().handle(ui, "exit --remove --discard");
        assertTrue(ui.prints.stream().anyMatch(s -> s.contains("已退出并删除")));
    }

    @Test
    void slug校验失败给出拒绝提示() throws Exception {
        StubUi ui = new StubUi();
        ui.accessor = new SimpleAccessor(mgr());
        Command cmd = worktreeCommand();

        cmd.handler().handle(ui, "create ../etc");
        assertEquals(1, ui.errors.size(), "AC6 场景：路径遍历应被拒绝");
        assertTrue(ui.errors.get(0).contains("拒绝"));
    }

    @Test
    void accessor为null时所有子命令报错() throws Exception {
        StubUi ui = new StubUi(); // accessor = null（Worktree 未启用）
        Command cmd = worktreeCommand();
        for (String args : new String[]{"create x", "list", "enter x", "exit", "remove x"}) {
            ui.errors.clear();
            cmd.handler().handle(ui, args);
            assertEquals(1, ui.errors.size(), "args=" + args);
            assertTrue(ui.errors.get(0).contains("未启用"));
        }
    }

    @Test
    void 未知子命令输出用法() throws Exception {
        StubUi ui = new StubUi();
        ui.accessor = new SimpleAccessor(mgr());
        Command cmd = worktreeCommand();
        cmd.handler().handle(ui, "frobnicate");
        assertTrue(ui.errors.get(0).contains("用法"));
    }
}
