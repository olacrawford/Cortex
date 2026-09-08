package com.cortex.llm;

import com.cortex.config.ProviderConfig;

import java.util.concurrent.BlockingQueue;

public interface LlmClient {

    /**
     * 发起一次流式对话请求。全部入参由 {@link Request} 承载：
     * 消息历史、工具集、系统提示（稳定段 + 环境段）、本轮 system-reminder。
     * 适配器负责按各协议装配缓存通道（Anthropic 显式断点 / OpenAI 前缀顺序）与消息通道。
     */
    BlockingQueue<StreamEvent> stream(Request req);

    static LlmClient create(ProviderConfig cfg) {
        return switch (cfg.getProtocol()) {
            case "anthropic" -> new AnthropicClient(cfg);
            case "openai", "openai-compat" -> new OpenAiClient(cfg);
            default -> throw new IllegalArgumentException("不支持的协议: " + cfg.getProtocol());
        };
    }
}
