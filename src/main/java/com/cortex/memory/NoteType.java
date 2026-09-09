package com.cortex.memory;

/**
 * 笔记类型（F27）：四类。wire() 返回 snake_case 名，fromWire 反查。
 */
public enum NoteType {
    USER_PREFERENCE("user_preference"),
    CORRECTION_FEEDBACK("correction_feedback"),
    PROJECT_KNOWLEDGE("project_knowledge"),
    REFERENCE_MATERIAL("reference_material");

    private final String wire;

    NoteType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static NoteType fromWire(String s) {
        for (NoteType t : values()) {
            if (t.wire.equals(s)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知笔记类型: " + s);
    }
}
