package com.cortex.team;

import java.io.IOException;

/**
 * 队员执行后端（F12）：spawn/wake/kill 统一抽象。
 * in-process 用 agentId，Pane 后端用 paneId，接口统一传两者。
 */
public interface Backend {

    BackendType type();

    /**
     * 启动一个队员。Pane 后端执行 split-window/it2 split + send-keys 启动子进程 CLI；
     * in-process 在同进程起 virtual thread 跑 runToCompletion。返回 SpawnResult（paneId 或空）。
     */
    SpawnResult spawn(SpawnRequest req) throws IOException;

    /** 消息到达时唤醒目标（Pane 后端 send-keys；in-process no-op）。 */
    void wake(String paneId, String agentId) throws IOException;

    /** 终止（Pane 后端 kill pane；in-process cancel virtual thread）。 */
    void kill(String paneId, String agentId) throws IOException;

    /** spawn 请求（F13）：subAgent/conv/taskManager 用 Object 避免 backend→agent/task 反向依赖。 */
    record SpawnRequest(
            String teamName,
            String memberName,
            String agentId,
            String worktreePath,
            String sessionDir,
            String agentType,
            String model,
            String initialPrompt,
            boolean planModeRequired,
            Object subAgent,
            Object conv,
            Object taskManager) {}

    /** spawn 结果（F12）：paneId（in-process 为空串）+ agentId。 */
    record SpawnResult(String paneId, String agentId) {}
}
