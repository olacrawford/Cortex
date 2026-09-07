package com.cortex.agent;

/**
 * 一轮请求的 token 用量（与 llm.Usage 同义，在 agent 包内解耦持有）。
 */
public record Usage(long inputTokens, long outputTokens) {}
