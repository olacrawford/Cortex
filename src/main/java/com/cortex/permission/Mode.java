package com.cortex.permission;

import java.util.Optional;

/**
 * 权限模式四档：规则未命中时的兜底裁决矩阵见 {@link PermissionEngine#modeFallback}。
 * 运行时可经 Shift+Tab 循环切换，/plan 与 /do 作为计划工作流的专用进出。
 */
public enum Mode {
    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS;

    public String displayName() {
        return switch (this) {
            case DEFAULT -> "default";
            case ACCEPT_EDITS -> "acceptEdits";
            case PLAN -> "plan";
            case BYPASS -> "bypassPermissions";
        };
    }

    /** 大小写不敏感识别四档名；未知返回 empty。 */
    public static Optional<Mode> parse(String s) {
        if (s == null) {
            return Optional.empty();
        }
        for (Mode m : values()) {
            if (m.displayName().equalsIgnoreCase(s.trim())) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
