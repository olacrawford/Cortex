package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class OpenAiClient implements LlmClient {

    private final ProviderConfig config;
    private final String systemPrompt;

    public OpenAiClient(ProviderConfig config, String systemPrompt) {
        this.config = config;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("openai-stream").start(() -> {
            try {
                var client = OpenAIOkHttpClient.builder().apiKey(config.getApiKey());
                if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                    client.baseUrl(config.getBaseUrl());
                }

                List<ChatCompletionMessageParam> messages = new ArrayList<>();
                messages.add(ChatCompletionMessageParam.ofSystem(
                        ChatCompletionSystemMessageParam.builder()
                                .content(systemPrompt)
                                .build()));

                for (var msg : conv.getMessages()) {
                    switch (msg.getRole()) {
                        case USER -> messages.add(ChatCompletionMessageParam.ofUser(
                                ChatCompletionUserMessageParam.builder()
                                        .content(msg.getContent())
                                        .build()));
                        case ASSISTANT -> messages.add(ChatCompletionMessageParam.ofAssistant(
                                ChatCompletionAssistantMessageParam.builder()
                                        .content(msg.getContent())
                                        .build()));
                    }
                }

                var params = ChatCompletionCreateParams.builder()
                        .model(config.getModel())
                        .messages(messages)
                        .build();

                var streamResponse = client.build().chat().completions().createStreaming(params);

                int inputTokens = 0;
                int outputTokens = 0;

                try (var stream = streamResponse.stream()) {
                    var iterator = stream.iterator();
                    while (iterator.hasNext()) {
                        var chunk = iterator.next();
                        var choice = chunk.choices().stream().findFirst();
                        if (choice.isPresent()) {
                            var delta = choice.get().delta();
                            if (delta.content().isPresent()) {
                                String text = delta.content().get();
                                if (!text.isEmpty()) {
                                    queue.put(new StreamEvent.TextDelta(text));
                                }
                            }
                        }
                        if (chunk.usage().isPresent()) {
                            var usage = chunk.usage().get();
                            inputTokens = (int) usage.promptTokens();
                            outputTokens = (int) usage.completionTokens();
                        }
                    }
                }

                queue.put(new StreamEvent.StreamEnd("stop", inputTokens, outputTokens));
            } catch (Exception e) {
                try {
                    queue.put(new StreamEvent.Error(e.getMessage() != null ? e.getMessage() : e.toString()));
                } catch (InterruptedException ignored) {}
            }
        });
        return queue;
    }
}
