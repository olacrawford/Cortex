package com.cortex.llm;

/**
 * 一轮请求的 token 用量（协议无关，由各适配器在流结束后统一上抛）。
 * cacheWrite/cacheRead 为缓存字段（F4）：Anthropic 取缓存创建/读取 token；
 * OpenAI 的 cacheRead 取 promptTokensDetails.cachedTokens，cacheWrite 恒 0（自动缓存无写计数）。
 * 端点未返回时按零处理（N6）。
 */
public record Usage(long inputTokens, long outputTokens, long cacheWrite, long cacheRead) {

    public Usage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }
}
