package com.cortex.llm;

import com.cortex.config.ProviderConfig;
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
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
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
 * OpenAI 协议适配器（含 openai-compat）。
 * 缓存通道（F3）：单条 system 消息 = stable 在前 + environment 在后——stable 居请求前缀，
 * 端点前缀缓存自动命中稳定部分（尽力而为，不强制端点支持）。
 * reminder 织入（F6）：追加一条尾部 user 消息（OpenAI 容忍连续 user）。
 * 缓存用量（F4/N6）：cacheRead 取 promptTokensDetails.cachedTokens（缺字段为 0），cacheWrite 恒 0。
 */
public class OpenAiClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderConfig config;

    public OpenAiClient(ProviderConfig config) {
        this.config = config;
    }

    /** 流式拼接中的工具调用片段：按 index 聚合 id / 函数名 / 参数碎片。 */
    private static final class Frag {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }

    @Override
    public BlockingQueue<StreamEvent> stream(Request req) {
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
                                .content(effectiveSystem(req.system()))
                                .build()));
                messages.addAll(toOpenAIMessages(req.messages()));
                if (req.reminder() != null && !req.reminder().isEmpty()) {
                    messages.add(ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder()
                                    .content(req.reminder())
                                    .build()));
                }

                var paramsBuilder = ChatCompletionCreateParams.builder()
                        .model(config.getModel())
                        .messages(messages)
                        // 不开 includeUsage 流式 usage 为空（F8）
                        .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
                if (!req.tools().isEmpty()) {
                    paramsBuilder.tools(toOpenAITools(req.tools()));
                }
                var params = paramsBuilder.build();

                var streamResponse = clientBuilder.build().chat().completions().createStreaming(params);

                int inputTokens = 0;
                int outputTokens = 0;
                long cachedTokens = 0;
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
                            cachedTokens = usage.promptTokensDetails()
                                    .flatMap(d -> d.cachedTokens())
                                    .orElse(0L);
                        }
                    }
                }

                for (Frag frag : frags.values()) {
                    String args = frag.args.isEmpty() ? "{}" : frag.args.toString();
                    queue.put(new StreamEvent.ToolCallComplete(frag.id, frag.name, args));
                }
                queue.put(new StreamEvent.UsageEvent(new Usage(inputTokens, outputTokens, 0, cachedTokens)));
                queue.put(new StreamEvent.StreamEnd("stop", inputTokens, outputTokens));
            } catch (Exception e) {
                try {
                    Throwable cause = PromptTooLongException.isPromptTooLong(e)
                            ? new PromptTooLongException(e)
                            : e;
                    queue.put(new StreamEvent.Error(
                            cause.getMessage() != null ? cause.getMessage() : cause.toString(), cause));
                } catch (InterruptedException ignored) {}
            }
        });
        return queue;
    }

    // ─── 请求组装 ───

    /** 单条 system：stable 在前（居缓存前缀），environment 非空时拼在后面。 */
    private String effectiveSystem(SystemPrompt system) {
        String stable = system.stable() == null ? "" : system.stable();
        String env = system.environment() == null ? "" : system.environment();
        if (env.isEmpty()) {
            return stable;
        }
        return stable.isEmpty() ? env : stable + "\n\n" + env;
    }

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

    private List<ChatCompletionMessageParam> toOpenAIMessages(List<Message> convMessages) {
        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        for (var msg : convMessages) {
            switch (msg.getRole()) {
                case USER -> messages.add(ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                                .content(msg.getContent())
                                .build()));
                case ASSISTANT -> messages.add(ChatCompletionMessageParam.ofAssistant(
                        toOpenAIAssistant(msg)));
                case TOOL -> {
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
