package com.cortex.skill;

import com.cortex.conversation.Message;
import com.cortex.tool.ToolRegistry;

import java.util.HashSet;
import java.util.List;

/**
 * 技能执行器（F6/F7）：inline 与 fork 两种执行模式。
 * 全部为静态方法——执行逻辑无状态，宿主能力通过 {@link SkillHost}/{@link SkillForkHost} 注入（N4）。
 */
public final class SkillExecutor {

    /** fork_context=recent 时取父对话尾部的最大消息条数。 */
    public static final int FORK_RECENT_COUNT = 5;

    private SkillExecutor() {}

    /**
     * inline 执行（F6）：校验工具白名单（N5 fail-fast）→ 渲染 prompt →
     * 记录激活态 → 按 allowed_tools 通知宿主过滤（inline 模式宿主仅记录，安全由权限引擎兜底）→
     * 返回渲染后的 body，由调用方注入对话。
     */
    public static String executeInline(Skill skill, String args, SkillHost host) {
        assertAllowedToolsExist(skill, host.toolRegistry());
        String rendered = substituteArguments(skill.promptBody(), args);
        host.activateSkill(skill.meta().name(), rendered);
        if (!skill.meta().allowedTools().isEmpty()) {
            host.setToolFilter(new HashSet<>(skill.meta().allowedTools())::contains);
        }
        return rendered;
    }

    /**
     * fork 执行（F7）：校验 → 渲染 → 按 fork_context 生成种子消息 →
     * 交给宿主起隔离子 Agent，返回子 Agent 最终 assistant 文本。
     */
    public static String executeFork(Skill skill, String args, SkillForkHost host) {
        assertAllowedToolsExist(skill, host.toolRegistry());
        String rendered = substituteArguments(skill.promptBody(), args);
        List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
        return host.runSubAgent(rendered, seed, skill.meta().allowedTools(), skill.meta().model());
    }

    /**
     * 参数渲染（F8）：args 空白原样返回；body 含 {@code $ARGUMENTS} 占位符则替换；
     * 否则在末尾追加 {@code ## User Request} 段。
     */
    public static String substituteArguments(String body, String args) {
        if (body == null) {
            return "";
        }
        if (args == null || args.isBlank()) {
            return body;
        }
        if (body.contains("$ARGUMENTS")) {
            return body.replace("$ARGUMENTS", args);
        }
        return body + "\n\n## User Request\n\n" + args;
    }

    /**
     * fork 种子消息（F9）：{@code full} 全量拷贝父消息；{@code recent} 取尾部最多
     * {@value #FORK_RECENT_COUNT} 条；其他（含 {@code none}）返回空。
     */
    public static List<Message> buildForkSeed(String forkContext, List<Message> parent) {
        if (parent == null || parent.isEmpty()) {
            return List.of();
        }
        if ("full".equals(forkContext)) {
            return List.copyOf(parent);
        }
        if ("recent".equals(forkContext)) {
            int from = Math.max(0, parent.size() - FORK_RECENT_COUNT);
            return List.copyOf(parent.subList(from, parent.size()));
        }
        return List.of();
    }

    /** allowed_tools 引用了未注册工具时抛 {@link IllegalStateException}（N5：执行前暴露配置错误）。 */
    public static void assertAllowedToolsExist(Skill skill, ToolRegistry registry) {
        for (String tool : skill.meta().allowedTools()) {
            if (registry.get(tool).isEmpty()) {
                throw new IllegalStateException(
                        "技能 " + skill.meta().name() + " 的 allowed_tools 引用了未注册工具: " + tool);
            }
        }
    }
}
