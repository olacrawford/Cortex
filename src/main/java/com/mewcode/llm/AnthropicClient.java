package com.mewcode.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AnthropicClient implements LlmClient {

    private final ProviderConfig config;
    private final String systemPrompt;

    public AnthropicClient(ProviderConfig config, String systemPrompt) {
        this.config = config;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("anthropic-stream").start(() -> {
            try {
                var builder = AnthropicOkHttpClient.builder().apiKey(config.getApiKey());
                if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                    builder.baseUrl(config.getBaseUrl());
                }
                var client = builder.build();

                var paramsBuilder = MessageCreateParams.builder()
                        .model(config.getModel())
                        .maxTokens(8192)
                        .system(systemPrompt);

                List<MessageParam> messages = new ArrayList<>();
                for (var msg : conv.getMessages()) {
                    var role = switch (msg.getRole()) {
                        case USER -> MessageParam.Role.USER;
                        case ASSISTANT -> MessageParam.Role.ASSISTANT;
                    };
                    messages.add(MessageParam.builder()
                            .role(role)
                            .content(msg.getContent())
                            .build());
                }
                paramsBuilder.messages(messages);

                if (config.isThinking()) {
                    paramsBuilder.thinking(ThinkingConfigEnabled.builder().budgetTokens(16000L).build());
                }

                var params = paramsBuilder.build();

                try (var response = client.messages().createStreaming(params)) {
                    var iter = response.stream().iterator();
                    while (iter.hasNext()) {
                        var event = iter.next();
                        if (event.isContentBlockDelta()) {
                            var delta = event.contentBlockDelta().orElseThrow().delta();
                            if (delta.isText()) {
                                queue.put(new StreamEvent.TextDelta(delta.text().orElseThrow().text()));
                            }
                            // thinking 增量接收即丢弃（ThinkingDelta 不渲染）
                        } else if (event.isMessageDelta()) {
                            var md = event.messageDelta().orElseThrow();
                            var stopReason = md.delta().stopReason();
                            var reason = stopReason.isPresent() ? stopReason.get().asString() : "stop";
                            var usage = md.usage();
                            int inTokens = usage.inputTokens().isPresent() ? usage.inputTokens().get().intValue() : 0;
                            int outTokens = (int) usage.outputTokens();
                            queue.put(new StreamEvent.StreamEnd(reason, inTokens, outTokens));
                        }
                    }
                }
            } catch (Exception e) {
                try {
                    queue.put(new StreamEvent.Error(e.getMessage() != null ? e.getMessage() : e.toString()));
                } catch (InterruptedException ignored) {}
            }
        });
        return queue;
    }
}
