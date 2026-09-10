package com.cortex.team;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 队员执行后端（F11）：in-process 同进程虚拟线程；tmux / iterm2 独立 pane 子进程。
 */
public enum BackendType {
    TMUX("tmux"),
    ITERM2("iterm2"),
    IN_PROCESS("in-process");

    private final String wire;

    BackendType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wireValue() {
        return wire;
    }

    @JsonCreator
    public static BackendType fromWire(String v) {
        for (BackendType t : values()) {
            if (t.wire.equalsIgnoreCase(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知后端类型: " + v);
    }

    /** Pane 后端（独立子进程）判定。 */
    public boolean isPane() {
        return this == TMUX || this == ITERM2;
    }
}
