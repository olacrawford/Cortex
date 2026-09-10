package com.cortex.agent;

/**
 * Agent 工具对 Team 派队能力的窄接口（T19）：由 {@code team.TeamManager} 实现，
 * agent 包只依赖本接口（team 单向依赖 agent，无循环）。
 */
public interface TeamHook {

    /**
     * 派一名队员进团队。
     *
     * @return 给模型的 JSON 结果（memberName/agentId/worktree/backend/paneId）
     */
    String spawnTeammate(TeamSpawnRequest req) throws Exception;

    /**
     * 派队请求（Agent 工具参数的传递形态）。
     *
     * @param teamName         目标团队（sanitized 名，存在性由实现校验）
     * @param prompt           初始任务
     * @param memberName       队员名（空则实现自动生成）
     * @param subagentType     角色定义名（空 = general-purpose）
     * @param model            模型覆盖（空 = inherit）
     * @param planModeRequired true = 队员以 PLAN 模式起步（F48，Plan 提交-Lead 审批工作流）
     */
    record TeamSpawnRequest(
            String teamName,
            String prompt,
            String memberName,
            String subagentType,
            String model,
            Boolean planModeRequired) {}
}
