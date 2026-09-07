package com.cortex.llm;

import com.cortex.config.ProviderConfig;
import com.cortex.conversation.ConversationManager;
import com.cortex.conversation.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * OpenAI 协议适配器（含 openai-compat）：注入工具定义、解析流式工具调用分片、
 * 把工具调用/结果回合映射为 assistant.tool_calls / tool 角色消息。
 */
public class OpenAiClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderConfig config;
    private final String systemPrompt;

    public OpenAiClient(ProviderConfig config, String systemPrompt) {
        this.config = config;
        this.systemPrompt = systemPrompt;
    }

    /** 流式拼接中的工具调用片段：按 index 聚合 id / 函数名 / 参数碎片。 */
    private static final class Frag {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<ToolDef> tools) {
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("openai-stream").start(() -> {
            try {
                var clientBuilder = OpenAIOkHttpClient.builder().apiKey(config.getApiKey());
                if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                    clientBuilder.baseUrl(config.getBaseUrl());
                }

                List<ChatCompletionMessageParam> messages = new ArrayList<>();
                messages.add(ChatCompletionMessageParam.ofSystem(
                        ChatCompletionSystemMessageParam.builder()
                                .content(systemPrompt)
                                .build()));
                messages.addAll(toOpenAIMessages(conv));

                var paramsBuilder = ChatCompletionCreateParams.builder()
                        .model(config.getModel())
                        .messages(messages);
                if (tools != null && !tools.isEmpty()) {
                    paramsBuilder.tools(toOpenAITools(tools));
                }
                var params = paramsBuilder.build();

                var streamResponse = clientBuilder.build().chat().completions().createStreaming(params);

                int inputTokens = 0;
                int outputTokens = 0;
                Map<Long, Frag> frags = new LinkedHashMap<>();

                try (var stream = streamResponse.stream()) {
                    var iterator = stream.iterator();
                    while (iterator.hasNext()) {
                        var chunk = iterator.next();
                        var choice = chunk.choices().stream().findFirst();
                        if (choice.isPresent()) {
                            var delta = choice.get().delta();
                            if (delta.content().isPresent() && !delta.content().get().isEmpty()) {
                                queue.put(new StreamEvent.TextDelta(delta.content().get()));
                            }
                            if (delta.toolCalls().isPresent()) {
                                for (var tc : delta.toolCalls().get()) {
                                    var frag = frags.computeIfAbsent(tc.index(), k -> new Frag());
                                    if (tc.id().isPresent()) {
                                        frag.id = tc.id().get();
                                    }
                                    if (tc.function().isPresent()) {
                                        var fn = tc.function().get();
                                        if (fn.name().isPresent()) {
                                            frag.name = fn.name().get();
                                        }
                                        if (fn.arguments().isPresent()) {
                                            frag.args.append(fn.arguments().get());
                                        }
                                    }
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

                // 工具调用在 StreamEnd 之前上抛，保证消费方先拿到完整调用
                for (Frag frag : frags.values()) {
                    String args = frag.args.isEmpty() ? "{}" : frag.args.toString();
                    queue.put(new StreamEvent.ToolCallComplete(frag.id, frag.name, args));
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

    // ─── 请求组装 ───

    private List<ChatCompletionTool> toOpenAITools(List<ToolDef> defs) {
        List<ChatCompletionTool> result = new ArrayList<>();
        for (ToolDef d : defs) {
            var paramsBuilder = FunctionParameters.builder();
            for (var entry : d.inputSchema().entrySet()) {
                paramsBuilder.putAdditionalProperty(
                        entry.getKey(),
                        JsonValue.fromJsonNode(MAPPER.valueToTree(entry.getValue())));
            }
            result.add(ChatCompletionTool.ofFunction(ChatCompletionFunctionTool.builder()
                    .function(FunctionDefinition.builder()
                            .name(d.name())
                            .description(d.description())
                            .parameters(paramsBuilder.build())
                            .build())
                    .build()));
        }
        return result;
    }

    private List<ChatCompletionMessageParam> toOpenAIMessages(ConversationManager conv) {
        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        for (var msg : conv.getMessages()) {
            switch (msg.getRole()) {
                case USER -> messages.add(ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                                .content(msg.getContent())
                                .build()));
                case ASSISTANT -> messages.add(ChatCompletionMessageParam.ofAssistant(
                        toOpenAIAssistant(msg)));
                case TOOL -> {
                    // OpenAI 协议：每个 tool_call_id 一条 tool 角色消息
                    for (ToolResult r : msg.getToolResults()) {
                        messages.add(ChatCompletionMessageParam.ofTool(
                                ChatCompletionToolMessageParam.builder()
                                        .toolCallId(r.toolCallId())
                                        .content(r.content())
                                        .build()));
                    }
                }
            }
        }
        return messages;
    }

    /** assistant 回合：纯文本，或文本 + tool_calls（便捷构造不携带工具调用，须手工组）。 */
    private ChatCompletionAssistantMessageParam toOpenAIAssistant(Message msg) {
        var builder = ChatCompletionAssistantMessageParam.builder();
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            builder.content(msg.getContent());
        }
        if (!msg.getToolCalls().isEmpty()) {
            List<ChatCompletionMessageToolCall> calls = new ArrayList<>();
            for (ToolCall c : msg.getToolCalls()) {
                calls.add(ChatCompletionMessageToolCall.ofFunction(
                        ChatCompletionMessageFunctionToolCall.builder()
                                .id(c.id())
                                .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                        .name(c.name())
                                        .arguments(normalizeArgs(c.args()))
                                        .build())
                                .build()));
            }
            builder.toolCalls(calls);
        }
        return builder.build();
    }

    /** 空参数归一为 "{}"，严格兼容端点对空串会报 400。 */
    private static String normalizeArgs(String args) {
        return (args == null || args.isBlank()) ? "{}" : args;
    }
}
