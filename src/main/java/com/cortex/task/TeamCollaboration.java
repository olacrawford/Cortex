package com.cortex.task;

import java.util.List;

/**
 * Team 协作能力端口（T22/T31）：ch13 的 TaskList/TaskGet/SendMessage 工具在队员上下文
 * （ToolContext.teammate）下经本端口分派到 Team 语义；无上下文时维持 ch13 行为。
 * 由 {@code team.TeamManager} 实现（team 已单向依赖 task，端口避免反向依赖）。
 */
public interface TeamCollaboration {

    /**
     * 发送消息（F31/F34）。
     *
     * @param to      队员名 / agentId / "*" 广播
     * @param approve plan_approval_response 的裁决（其他类型传 null）
     * @return 给模型的 JSON 结果（deliveredTo/timestamp）
     */
    String sendMessage(String teamName, String fromMember, String to,
                       String summary, String message, String type,
                       Boolean approve, String feedback) throws Exception;

    /** 新建任务（F26），返回 JSON（taskId）。 */
    String taskCreate(String teamName, String title, String description,
                      String assignee, List<String> blockedBy) throws Exception;

    /** 任务详情（F27），未知 id 抛 TeamException。 */
    String taskGet(String teamName, String taskId) throws Exception;

    /** 任务列表（F28，可按 status 过滤），带 isReady。 */
    String taskList(String teamName, String statusFilter) throws Exception;

    /** 任务更新（F29，依赖双向维护）；null 字段不更新。 */
    String taskUpdate(String teamName, String taskId, String title, String description,
                      String status, String assignee,
                      List<String> addBlocks, List<String> addBlockedBy,
                      List<String> removeBlocks, List<String> removeBlockedBy) throws Exception;
}
