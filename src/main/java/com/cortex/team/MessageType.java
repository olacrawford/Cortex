package com.cortex.team;

/** 结构化消息类型（F9/G32）。 */
public enum MessageType {
    TEXT("text"),
    SHUTDOWN_REQUEST("shutdown_request"),
    SHUTDOWN_RESPONSE("shutdown_response"),
    PLAN_APPROVAL_RESPONSE("plan_approval_response");

    private final String wire;

    MessageType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static MessageType fromWire(String v) {
        for (MessageType t : values()) {
            if (t.wire.equalsIgnoreCase(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知消息类型: " + v);
    }
}
