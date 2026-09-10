package com.cortex.team;

import java.io.IOException;
import java.util.List;

/**
 * iTerm2 后端（T13/F17）：经 it2 CLI split-pane 启动队员子进程。仅命令构造与调用，
 * CI 无法实跑（官方 it2 CLI 以实际安装版本为准）。
 */
public final class Iterm2Backend implements Backend {

    /** 命令执行器（测试注入）。 */
    public interface Runner {
        String run(List<String> args) throws IOException;
    }

    private final String cortexJar;
    private final Runner runner;

    public Iterm2Backend(String cortexJar) {
        this(cortexJar, Iterm2Backend::exec);
    }

    public Iterm2Backend(String cortexJar, Runner runner) {
        this.cortexJar = cortexJar;
        this.runner = runner;
    }

    @Override
    public BackendType type() {
        return BackendType.ITERM2;
    }

    @Override
    public SpawnResult spawn(SpawnRequest req) throws IOException {
        // it2 split-pane 接收要执行的命令；pane id 由 it2 输出解析
        List<String> cmd = new TmuxBackend(cortexJar).memberCommand(req);
        List<String> args = new java.util.ArrayList<>(List.of("it2", "split-pane"));
        args.addAll(cmd);
        String paneId = runner.run(args).strip();
        return new SpawnResult(paneId, req.agentId());
    }

    @Override
    public void wake(String paneId, String agentId) throws IOException {
        runner.run(List.of("it2", "send-text", "--pane", paneId, ""));
    }

    @Override
    public void kill(String paneId, String agentId) throws IOException {
        try {
            runner.run(List.of("it2", "close-pane", "--pane", paneId));
        } catch (IOException e) {
            // pane 已不存在：忽略
        }
    }

    private static String exec(List<String> args) throws IOException {
        try {
            Process p = new ProcessBuilder(args).start();
            byte[] out;
            try (var in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("it2 命令超时");
            }
            if (p.exitValue() != 0) {
                throw new IOException("it2 命令失败(exit " + p.exitValue() + ")");
            }
            return new String(out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("it2 命令被中断", e);
        }
    }
}
