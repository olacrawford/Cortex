package com.cortex.agent;

/**
 * 一轮请求的 token 用量（与 llm.Usage 同义，在 agent 包内解耦持有）。
 * cacheWrite/cacheRead 透传缓存命中信息，供调试输出验证缓存策略（F4）。
 */
public record Usage(long inputTokens, long outputTokens, long cacheWrite, long cacheRead) {}
