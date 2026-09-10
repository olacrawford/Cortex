package com.cortex.team;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * tmux 后端（T12/F15/F16）：split-window（$TMUX 内）或 new-session -d（外）启动
 * {@code cortex --team-member ...} 子进程；wake 用 send-keys 空行触发子进程 stdin scanner；
 * kill 用 kill-pane。命令构造可经 {@link Runner} 注入测试。
 */
public final class TmuxBackend implements Backend {

    /** 命令执行器：args → stdout（注入测试 fake 用）。 */
    public interface Runner {
        String run(List<String> args) throws IOException;
    }

    private final String cortexJar;   // cortex 可执行引用（jar 路径或启动命令）
    private final Runner runner;

    public TmuxBackend(String cortexJar) {
        this(cortexJar, TmuxBackend::exec);
    }

    public TmuxBackend(String cortexJar, Runner runner) {
        this.cortexJar = cortexJar;
        this.runner = runner;
    }

    @Override
    public BackendType type() {
        return BackendType.TMUX;
    }

    @Override
    public SpawnResult spawn(SpawnRequest req) throws IOException {
        boolean inTmux = System.getenv("TMUX") != null;
        List<String> args = new ArrayList<>(List.of("tmux"));
        if (inTmux) {
            args.addAll(List.of("split-window", "-h", "-P", "-F", "#{pane_id}"));
        } else {
            // F16：tmux 会话外 → detached 新 session（不静默回退 in-process）
            args.addAll(List.of("new-session", "-d", "-P", "-F", "#{pane_id}"));
        }
        args.add("--");
        args.addAll(memberCommand(req));
        String paneId = runner.run(args).strip();
        return new SpawnResult(paneId, req.agentId());
    }

    @Override
    public void wake(String paneId, String agentId) throws IOException {
        runner.run(List.of("tmux", "send-keys", "-t", paneId, "", "Enter"));
    }

    @Override
    public void kill(String paneId, String agentId) throws IOException {
        try {
            runner.run(List.of("tmux", "kill-pane", "-t", paneId));
        } catch (IOException e) {
            // pane 已不存在：忽略（F15）
        }
    }

    /** 队员子进程命令（F15）：initialPrompt 不走命令行，由 spawn 前预写 mailbox。 */
    List<String> memberCommand(SpawnRequest req) {
        List<String> cmd = new ArrayList<>(List.of(
                "java", "-jar", cortexJar, "--team-member",
                "--team", req.teamName(),
                "--member", req.memberName(),
                "--agent-id", req.agentId(),
                "--session-dir", req.sessionDir(),
                "--worktree", req.worktreePath()));
        if (req.agentType() != null && !req.agentType().isBlank()) {
            cmd.addAll(List.of("--agent-type", req.agentType()));
        }
        if (req.model() != null && !req.model().isBlank()) {
            cmd.addAll(List.of("--model", req.model()));
        }
        if (req.planModeRequired()) {
            cmd.add("--plan-mode");
        }
        return cmd;
    }

    private static String exec(List<String> args) throws IOException {
        try {
            Process p = new ProcessBuilder(args).start();
            byte[] out;
            try (var in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            byte[] err;
            try (var es = p.getErrorStream()) {
                err = es.readAllBytes();
            }
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("tmux 命令超时: " + String.join(" ", args));
            }
            if (p.exitValue() != 0) {
                throw new IOException("tmux 命令失败(exit " + p.exitValue() + "): "
                        + new String(err, StandardCharsets.UTF_8).strip());
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tmux 命令被中断", e);
        }
    }
}
