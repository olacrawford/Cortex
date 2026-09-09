package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.permission.Outcome;

import java.util.Optional;

/**
 * Agent 工具对后台任务管理器的窄接口（T17）：由 {@code task.Manager} 实现，
 * 打破 agent ↔ task 循环依赖（agent 只见接口，task 单向依赖 agent）。
 */
public interface TaskManagerPort {

    /** 后台启动：起虚拟线程跑 runToCompletion，立即返回任务 ID（F16）。 */
    String launch(Agent agent, ConversationManager conv, String name, String task);

    /**
     * 前台启动（可被超时接管，F17-②）：注册任务并立即在虚拟线程开跑，
     * 调用方用返回句柄同步等待至多 AUTO_BACKGROUND_MS。
     */
    SubAgentRun startForeground(Agent agent, ConversationManager conv, String name, String task);

    /** 子 Agent 审批升级（F13）：转发到主 TUI 并阻塞等裁决；无转发器时返回 empty 走默认路径。 */
    Optional<Outcome> upgradeApproval(ApprovalRequest req);
}
