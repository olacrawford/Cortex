package com.cortex.team;

import com.cortex.agent.Agent;
import com.cortex.conversation.ConversationManager;
import com.cortex.task.Manager;

import java.io.IOException;

/**
 * in-process 后端（T14/F18）：队员复用 ch13 的 {@link Manager} 在同进程 virtual thread
 * 跑 runToCompletion；wake 为 no-op（下一轮 Loop 自动读邮箱）；kill 走 TaskManager.stop。
 */
public final class InProcessBackend implements Backend {

    private final Manager taskManager;

    public InProcessBackend(Manager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public BackendType type() {
        return BackendType.IN_PROCESS;
    }

    @Override
    public SpawnResult spawn(SpawnRequest req) throws IOException {
        Agent subAgent = (Agent) req.subAgent();
        ConversationManager conv = (ConversationManager) req.conv();
        // launch 的 name=memberName：TaskManager 经 AgentNameRegistry 注册 name→id（T21）
        String agentId = taskManager.launch(subAgent, conv, req.memberName(), req.initialPrompt());
        return new SpawnResult("", agentId);
    }

    @Override
    public void wake(String paneId, String agentId) {
        // 同进程：下一轮 Loop 自动读邮箱，无需唤醒
    }

    @Override
    public void kill(String paneId, String agentId) {
        taskManager.stop(agentId);
    }
}
