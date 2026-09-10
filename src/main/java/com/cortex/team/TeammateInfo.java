package com.cortex.team;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 队员信息（F2），Team config.json 的 members 数组元素。
 *
 * @param name             Lead 分配的队员名（Team 内唯一；lead 成员固定叫 lead）
 * @param agentId          对应 BackgroundTask.id（in-process）或生成 id（Pane）
 * @param agentType        subagent 定义名；Fork 路径为空串
 * @param model            模型覆盖（空串 = inherit）
 * @param worktreePath     队员 Worktree 绝对路径
 * @param branch           Worktree 分支名
 * @param backendType      执行后端（可 per-member 不同）
 * @param paneId           tmux pane / iterm2 split id（in-process 为空串）
 * @param isActive         null/true=活跃，false=空闲（终止后直接移除成员）
 * @param planModeRequired spawn 时是否强制 plan 模式起步
 * @param sessionDir       队员独立 session 目录（绝对路径字符串）
 */
public record TeammateInfo(
        @JsonProperty("name") String name,
        @JsonProperty("agentId") String agentId,
        @JsonProperty("agentType") String agentType,
        @JsonProperty("model") String model,
        @JsonProperty("worktreePath") String worktreePath,
        @JsonProperty("branch") String branch,
        @JsonProperty("backendType") BackendType backendType,
        @JsonProperty("paneId") String paneId,
        @JsonProperty("isActive") Boolean isActive,
        @JsonProperty("planModeRequired") boolean planModeRequired,
        @JsonProperty("sessionDir") String sessionDir) {

    /** 是否活跃（null 视为活跃——刚 spawn 尚未空闲）。 */
    public boolean active() {
        return isActive == null || isActive;
    }

    public TeammateInfo withActive(boolean active) {
        return new TeammateInfo(name, agentId, agentType, model, worktreePath, branch,
                backendType, paneId, active, planModeRequired, sessionDir);
    }
}
