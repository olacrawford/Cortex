package com.cortex.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.cortex.config.ProviderConfig;
import com.cortex.conversation.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Anthropic 协议适配器。
 * 缓存通道（F3）：system 传两块 TextBlockParam——稳定块打 cacheControl 断点（缓存前缀 =
 * 全部工具 + 稳定块），环境块不打断点（每轮变化不冲前缀命中）。
 * reminder 织入（F6/N3）：并入最后一条 user 消息的 content 块，避免连续 user 触发 400。
 * 缓存用量（F4）：Anthropic 在 message_start 事件携带 input 与缓存创建/读取 token。
 */
public class AnthropicClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderConfig config;

    public AnthropicClient(ProviderConfig config) {
        this.config = config;
    }

    /** 流式拼接中的工具调用：content_block_start 记 id/name，input_json_delta 追加参数碎片。 */
    private record ToolAcc(String id, String name, StringBuilder args) {}

    @Override
    public BlockingQueue<StreamEvent> stream(Request req) {
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
                        .system(toAnthropicSystem(req.system()));

                if (!req.tools().isEmpty()) {
                    paramsBuilder.tools(toAnthropicTools(req.tools()));
                }

                List<MessageParam> messages = toAnthropicMessages(req.messages());
                appendReminder(messages, req.reminder());
                paramsBuilder.messages(messages);

                // 含工具交互的请求不启用 thinking：回灌的 tool_use 回合没有原 thinking 签名，会 400
                boolean hasToolTurn = req.messages().stream()
                        .anyMatch(m -> m.getRole() == Message.Role.TOOL);
                if (config.isThinking() && !hasToolTurn) {
                    paramsBuilder.thinking(com.anthropic.models.messages.ThinkingConfigEnabled.builder()
                            .budgetTokens(16000L).build());
                }

                var params = paramsBuilder.build();

                Map<Long, ToolAcc> toolAccs = new LinkedHashMap<>();
                StreamEvent.StreamEnd end = null;
                long inputTokens = 0;
                long cacheWrite = 0;
                long cacheRead = 0;

                try (var response = client.messages().createStreaming(params)) {
                    var iter = response.stream().iterator();
                    while (iter.hasNext()) {
                        var event = iter.next();
                        if (event.isMessageStart()) {
                            // input 与缓存字段只在 message_start 携带
                            var usage = event.asMessageStart().message().usage();
                            inputTokens = usage.inputTokens();
                            cacheWrite = usage.cacheCreationInputTokens().orElse(0L);
                            cacheRead = usage.cacheReadInputTokens().orElse(0L);
                        } else if (event.isContentBlockStart()) {
                            var start = event.asContentBlockStart();
                            var block = start.contentBlock();
                            if (block.isToolUse()) {
                                var tu = block.asToolUse();
                                toolAccs.put(start.index(), new ToolAcc(tu.id(), tu.name(), new StringBuilder()));
                            }
                        } else if (event.isContentBlockDelta()) {
                            var deltaEvent = event.asContentBlockDelta();
                            var delta = deltaEvent.delta();
                            if (delta.isText()) {
                                queue.put(new StreamEvent.TextDelta(delta.asText().text()));
                            } else if (delta.isInputJson()) {
                                var acc = toolAccs.get(deltaEvent.index());
                                if (acc != null) {
                                    acc.args().append(delta.asInputJson().partialJson());
                                }
                            }
                            // thinking 增量接收即丢弃
                        } else if (event.isMessageDelta()) {
                            var md = event.asMessageDelta();
                            var stopReason = md.delta().stopReason();
                            var reason = stopReason.isPresent() ? stopReason.get().asString() : "stop";
                            var usage = md.usage();
                            int outTokens = (int) usage.outputTokens();
                            end = new StreamEvent.StreamEnd(reason, (int) inputTokens, outTokens);
                        }
                    }
                }

                for (var acc : toolAccs.values()) {
                    String args = acc.args().isEmpty() ? "{}" : acc.args().toString();
                    queue.put(new StreamEvent.ToolCallComplete(acc.id(), acc.name(), args));
                }
                queue.put(new StreamEvent.UsageEvent(new Usage(inputTokens,
                        end != null ? end.outputTokens() : 0, cacheWrite, cacheRead)));
                queue.put(end != null ? end : new StreamEvent.StreamEnd("stop", (int) inputTokens, 0));
            } catch (Exception e) {
                try {
                    queue.put(new StreamEvent.Error(e.getMessage() != null ? e.getMessage() : e.toString()));
                } catch (InterruptedException ignored) {}
            }
        });
        return queue;
    }

    // ─── 请求组装 ───

    /** system 两块：稳定块（缓存断点，默认 5m TTL）+ 环境块（不缓存）。包可见供测试断言。 */
    static MessageCreateParams.System toAnthropicSystem(SystemPrompt system) {
        List<TextBlockParam> blocks = new ArrayList<>();
        if (system.stable() != null && !system.stable().isEmpty()) {
            blocks.add(TextBlockParam.builder()
                    .text(system.stable())
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build());
        }
        if (system.environment() != null && !system.environment().isEmpty()) {
            blocks.add(TextBlockParam.builder()
                    .text(system.environment())
                    .build());
        }
        return MessageCreateParams.System.ofTextBlockParams(blocks);
    }

    private List<ToolUnion> toAnthropicTools(List<ToolDef> defs) {
        List<ToolUnion> result = new ArrayList<>();
        for (ToolDef d : defs) {
            var schemaBuilder = Tool.InputSchema.builder().type(JsonValue.from("object"));
            Object props = d.inputSchema().get("properties");
            if (props instanceof Map<?, ?> propsMap && !propsMap.isEmpty()) {
                var propsBuilder = Tool.InputSchema.Properties.builder();
                for (var entry : propsMap.entrySet()) {
                    propsBuilder.putAdditionalProperty(
                            String.valueOf(entry.getKey()),
                            JsonValue.fromJsonNode(MAPPER.valueToTree(entry.getValue())));
                }
                schemaBuilder.properties(propsBuilder.build());
            }
            if (d.inputSchema().get("required") instanceof List<?> required) {
                schemaBuilder.required(required.stream().map(String::valueOf).toList());
            }
            result.add(ToolUnion.ofTool(Tool.builder()
                    .name(d.name())
                    .description(d.description())
                    .inputSchema(schemaBuilder.build())
                    .build()));
        }
        return result;
    }

    private List<MessageParam> toAnthropicMessages(List<com.cortex.conversation.Message> convMessages) {
        List<MessageParam> messages = new ArrayList<>();
        for (var msg : convMessages) {
            switch (msg.getRole()) {
                case USER -> messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(msg.getContent())
                        .build());
                case ASSISTANT -> messages.add(toAnthropicAssistant(msg));
                case TOOL -> messages.add(toAnthropicToolResultMessage(msg));
            }
        }
        return messages;
    }

    /** assistant 回合：纯文本或 文本块 + tool_use 块。 */
    private MessageParam toAnthropicAssistant(Message msg) {
        if (msg.getToolCalls().isEmpty()) {
            return MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .content(msg.getContent())
                    .build();
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(msg.getContent()).build()));
        }
        for (ToolCall call : msg.getToolCalls()) {
            blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(call.id())
                    .name(call.name())
                    .input(toToolUseInput(call.args()))
                    .build()));
        }
        return MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build();
    }

    /** TOOL 回合 → 一条 user 消息，携带全部 tool_result 块。 */
    private MessageParam toAnthropicToolResultMessage(Message msg) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ToolResult r : msg.getToolResults()) {
            blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(r.toolCallId())
                    .content(r.content())
                    .isError(r.isError())
                    .build()));
        }
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(blocks)
                .build();
    }

    /**
     * reminder 织入（N3）：并入末条 user 消息（块列表追加或纯文本转块），
     * 避免连续 user 触发 400；极端情形（末条 assistant / 空）才新起 user 消息。
     */
    private void appendReminder(List<MessageParam> messages, String reminder) {
        if (reminder == null || reminder.isEmpty()) {
            return;
        }
        ContentBlockParam block = ContentBlockParam.ofText(TextBlockParam.builder().text(reminder).build());
        if (!messages.isEmpty()) {
            MessageParam last = messages.get(messages.size() - 1);
            if (last.role() == MessageParam.Role.USER) {
                List<ContentBlockParam> blocks = new ArrayList<>();
                if (last.content().isString()) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                            .text(last.content().asString()).build()));
                } else if (last.content().isBlockParams()) {
                    blocks.addAll(last.content().asBlockParams());
                }
                blocks.add(block);
                messages.set(messages.size() - 1, MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(blocks)
                        .build());
                return;
            }
        }
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(List.of(block))
                .build());
    }

    /** 把参数 JSON 字符串解析为 tool_use 块的 input 对象；解析失败按空对象处理。 */
    private ToolUseBlockParam.Input toToolUseInput(String argsJson) {
        var inputBuilder = ToolUseBlockParam.Input.builder();
        try {
            JsonNode node = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            node.properties().forEach(p ->
                    inputBuilder.putAdditionalProperty(p.getKey(), JsonValue.fromJsonNode(p.getValue())));
        } catch (Exception ignored) {
            // 参数不合法时回灌空对象，让模型从 tool_result 的错误反馈中调整
        }
        return inputBuilder.build();
    }
}
