package com.cortex.llm;

import com.cortex.config.ProviderConfig;
import com.cortex.conversation.ConversationManager;

import java.util.List;
import java.util.concurrent.BlockingQueue;

public interface LlmClient {

    /**
     * 发起一次流式对话请求。
     *
     * @param conv  对话历史（含工具调用/结果回合，由适配器映射为各协议格式）
     * @param tools 本请求携带的工具定义；空列表表示不带工具
     */
    BlockingQueue<StreamEvent> stream(ConversationManager conv, List<ToolDef> tools);

    static LlmClient create(ProviderConfig cfg, String systemPrompt) {
        return switch (cfg.getProtocol()) {
            case "anthropic" -> new AnthropicClient(cfg, systemPrompt);
            case "openai", "openai-compat" -> new OpenAiClient(cfg, systemPrompt);
            default -> throw new IllegalArgumentException("不支持的协议: " + cfg.getProtocol());
        };
    }
}
