package com.cortex.skill;

/** 技能来源层级：用户全局层（~/.cortex/skills）优先级低于项目层（.cortex/skills）。 */
public enum SkillSource {
    USER,
    PROJECT;

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
