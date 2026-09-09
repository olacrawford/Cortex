package com.cortex.hook;

import java.util.Map;
import java.util.Optional;

/**
 * Agent 生命周期事件（F9）：11 个固定时刻。
 * {@link #isBlocking()} 为 true 的事件（PreToolUse / UserPromptSubmit）允许 hook 通过约定信号
 * （shell exit 2 / http decision=block）拦截主流程；其余事件仅执行副作用。
 */
public enum Event {
    SESSION_START("SessionStart"),
    SESSION_END("SessionEnd"),
    SESSION_RESUME("SessionResume"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    STOP("Stop"),
    PRE_USER_MESSAGE("PreUserMessage"),
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    PRE_COMPACT("PreCompact"),
    POST_COMPACT("PostCompact"),
    NOTIFICATION("Notification");

    private static final Map<String, Event> BY_NAME = Map.copyOf(byName());

    /** 事件名（yaml 配置与 JSON payload 用驼峰写法）。 */
    private final String wireName;

    Event(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** 拦截类事件：hook 可表达阻断（F28：不允许 async）。 */
    public boolean isBlocking() {
        return this == PRE_TOOL_USE || this == USER_PROMPT_SUBMIT;
    }

    /** 按名字解析（大小写不敏感，接受驼峰与全大写）；未知返回 empty（AC11）。 */
    public static Optional<Event> parse(String s) {
        if (s == null || s.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NAME.get(s.strip().toLowerCase()));
    }

    private static Map<String, Event> byName() {
        java.util.Map<String, Event> m = new java.util.HashMap<>();
        for (Event e : values()) {
            m.put(e.wireName.toLowerCase(), e);
            m.put(e.name().toLowerCase(), e);
        }
        return m;
    }
}
