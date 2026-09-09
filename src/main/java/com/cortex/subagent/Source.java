package com.cortex.subagent;

/**
 * Agent 定义文件的来源层级（F5/F6）：优先级 PROJECT &gt; USER &gt; BUILTIN &gt; PLUGIN。
 * 同名定义按此顺序后者加载时覆盖前者；PLUGIN 本期恒为空（F8 插件加载器不做）。
 */
public enum Source {
    BUILTIN,
    USER,
    PROJECT,
    PLUGIN;

    @Override
    public String toString() {
        return switch (this) {
            case BUILTIN -> "builtin";
            case USER -> "user";
            case PROJECT -> "project";
            case PLUGIN -> "plugin";
        };
    }
}
