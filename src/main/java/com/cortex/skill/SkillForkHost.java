package com.cortex.skill;

import com.cortex.conversation.Message;

import java.util.List;

/**
 * fork 执行宿主：在 {@link SkillHost} 之上追加隔离子 Agent 能力。
 * fork 模式本期仅程序化调用（spec 主流程「执行 fork（programmatic）」），TUI 未接入。
 */
public interface SkillForkHost extends SkillHost {

    /**
     * 起隔离子 Agent 执行渲染后的 SOP。
     *
     * @param body         渲染后的提示词正文
     * @param seed         按 fork_context 生成的种子消息（none/recent/full）
     * @param allowedTools 工具白名单（空 = 不限制）
     * @param model        模型覆盖（null/空 = 沿用主 provider）
     * @return 子 Agent 最终 assistant 文本
     */
    String runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model);

    /** 父对话消息快照，供 buildForkSeed 生成 recent/full 种子。 */
    List<Message> snapshotParentMessages();
}
