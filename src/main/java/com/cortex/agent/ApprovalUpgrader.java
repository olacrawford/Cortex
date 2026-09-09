package com.cortex.agent;

import com.cortex.permission.Outcome;

import java.util.Optional;

/**
 * 子 Agent 审批升级回调（F13）：子 Agent 在权限判定为 Ask 时调用，
 * 实现方把请求转发到主 TUI 弹窗并阻塞等用户三选一。
 * 返回 {@link Optional#empty()} 表示不接管，调用方回落默认路径（emit Approval 事件）。
 */
@FunctionalInterface
public interface ApprovalUpgrader {

    /**
     * 处理一条审批请求。
     *
     * @param req 待批准请求（自带 respond 队列，实现方负责等结果）
     * @return 用户裁决；empty = 不接管
     */
    Optional<Outcome> upgrade(ApprovalRequest req) throws InterruptedException;
}
