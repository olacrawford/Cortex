package com.cortex.skill;

import com.cortex.tool.ToolRegistry;

import java.util.function.Predicate;

/**
 * inline 执行时 executor 操作宿主（TUI/Agent 层）的窄接口（N4 反向解耦）：
 * skill 包不 import agent / tui，宿主实现本接口把能力切片暴露出来。
 */
public interface SkillHost {

    /** 记录技能激活态（name → 渲染后 SOP），供 Active Skills 上下文装配。 */
    void activateSkill(String name, String body);

    /**
     * 按 allowed_tools 设置工具过滤。按 phase-10 Plan 决议，inline 模式不真正切换主对话工具集
     * （安全由权限引擎兜底），实现方可以仅记录该过滤器。
     */
    void setToolFilter(Predicate<String> allowedTools);

    /** 工具注册中心，供 {@code assertAllowedToolsExist} 校验白名单（N5）。 */
    ToolRegistry toolRegistry();
}
