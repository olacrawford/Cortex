package com.cortex.skill;

import java.util.List;

/**
 * 技能元数据（SKILL.md frontmatter 或 skill.yaml）：
 * name 缺省取目录名小写化并把空格换 {@code -}；mode 缺省 {@code inline}（兼容旧写法 {@code context: fork}）；
 * forkContext 仅 fork 模式生效，缺省 {@code none}。
 */
public record SkillMeta(
        String name,
        String description,
        String whenToUse,
        List<String> tags,
        List<String> allowedTools,
        String mode,
        String model,
        String forkContext) {

    public SkillMeta {
        tags = tags == null ? List.of() : List.copyOf(tags);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        mode = mode == null || mode.isBlank() ? "inline" : mode.strip();
        forkContext = forkContext == null || forkContext.isBlank() ? "none" : forkContext.strip();
    }

    /** mode == "fork" 视作 fork 模式；其它值（含 inline）按 inline 处理。 */
    public boolean isFork() {
        return "fork".equals(mode);
    }
}
