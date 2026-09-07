package com.cortex.llm;

import com.cortex.config.ProviderConfig;
import com.cortex.conversation.ConversationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public interface LlmClient {

    BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools);

    static LlmClient create(ProviderConfig cfg, String systemPrompt) {
        return switch (cfg.getProtocol()) {
            case "anthropic" -> new AnthropicClient(cfg, systemPrompt);
            case "openai", "openai-compat" -> new OpenAiClient(cfg, systemPrompt);
            default -> throw new IllegalArgumentException("不支持的协议: " + cfg.getProtocol());
        };
    }
}