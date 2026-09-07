package com.cortex.llm;

/**
 * 一轮请求的 token 用量（协议无关，由各适配器在流结束后统一上抛）。
 */
public record Usage(long inputTokens, long outputTokens) {}
