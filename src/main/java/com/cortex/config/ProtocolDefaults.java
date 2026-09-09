package com.cortex.config;

/**
 * 各协议默认上下文窗口（token）。定义在 config 自身，不放 compact 包，避免 config → compact 反向依赖。
 */
public final class ProtocolDefaults {

    public static final int DEFAULT_ANTHROPIC_CONTEXT_WINDOW = 200000;
    public static final int DEFAULT_OPENAI_CONTEXT_WINDOW = 128000;

    private ProtocolDefaults() {}
}
