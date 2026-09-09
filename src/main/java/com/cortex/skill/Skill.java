package com.cortex.skill;

import java.nio.file.Path;

/**
 * 已加载的技能：meta + 提示词正文 + 来源目录。
 * phase-1 加载只保证 meta（yaml+prompt.md 形态的 {@code promptBody} 可能为 null、{@code bodyLoaded=false}），
 * phase-2 由 {@link SkillCatalog#getFull} 按需重读正文（F4 热更新）。
 */
public record Skill(
        SkillMeta meta,
        String promptBody,
        Path sourceDir,
        boolean bodyLoaded) {

    /** 返回带新正文的副本（元数据与来源不变）。 */
    public Skill withBody(String newBody) {
        return new Skill(meta, newBody, sourceDir, true);
    }
}
