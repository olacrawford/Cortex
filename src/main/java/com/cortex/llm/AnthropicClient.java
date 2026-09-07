package com.cortex.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.cortex.config.ProviderConfig;
import com.cortex.conversation.ConversationManager;
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
 * Anthropic 协议适配器：注入工具定义、解析流式工具调用、把工具调用/结果回合映射为
 * tool_use / tool_result 内容块（tool_result 按 Anthropic 协议要求由 user 角色提交）。
 */
public class AnthropicClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderConfig config;
    private final String systemPrompt;

    public AnthropicClient(ProviderConfig config, String systemPrompt) {
        this.config = config;
        this.systemPrompt = systemPrompt;
    }

    /** 流式拼接中的工具调用：content_block_start 记 id/name，input_json_delta 追加参数碎片。 */
    private record ToolAcc(String id, String name, StringBuilder args) {}

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<ToolDef> tools) {
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

                if (tools != null && !tools.isEmpty()) {
                    paramsBuilder.tools(toAnthropicTools(tools));
                }

                paramsBuilder.messages(toAnthropicMessages(conv));

                // 含工具交互的续答请求不启用 thinking：回灌的 tool_use 回合没有原 thinking 签名，会 400
                boolean hasToolTurn = conv.getMessages().stream()
                        .anyMatch(m -> m.getRole() == Message.Role.TOOL);
                if (config.isThinking() && !hasToolTurn) {
                    paramsBuilder.thinking(ThinkingConfigEnabled.builder().budgetTokens(16000L).build());
                }

                var params = paramsBuilder.build();

                Map<Long, ToolAcc> toolAccs = new LinkedHashMap<>();
                StreamEvent.StreamEnd end = null;

                try (var response = client.messages().createStreaming(params)) {
                    var iter = response.stream().iterator();
                    while (iter.hasNext()) {
                        var event = iter.next();
                        if (event.isContentBlockStart()) {
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
                            // thinking 增量接收即丢弃（ThinkingDelta 不渲染）
                        } else if (event.isMessageDelta()) {
                            var md = event.asMessageDelta();
                            var stopReason = md.delta().stopReason();
                            var reason = stopReason.isPresent() ? stopReason.get().asString() : "stop";
                            var usage = md.usage();
                            int inTokens = usage.inputTokens().isPresent() ? usage.inputTokens().get().intValue() : 0;
                            int outTokens = (int) usage.outputTokens();
                            end = new StreamEvent.StreamEnd(reason, inTokens, outTokens);
                        }
                    }
                }

                // 工具调用在 StreamEnd 之前上抛，保证消费方先拿到完整调用
                for (var acc : toolAccs.values()) {
                    String args = acc.args().isEmpty() ? "{}" : acc.args().toString();
                    queue.put(new StreamEvent.ToolCallComplete(acc.id(), acc.name(), args));
                }
                queue.put(end != null ? end : new StreamEvent.StreamEnd("stop", 0, 0));
            } catch (Exception e) {
                try {
                    queue.put(new StreamEvent.Error(e.getMessage() != null ? e.getMessage() : e.toString()));
                } catch (InterruptedException ignored) {}
            }
        });
        return queue;
    }

    // ─── 请求组装 ───

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

    private List<MessageParam> toAnthropicMessages(ConversationManager conv) {
        List<MessageParam> messages = new ArrayList<>();
        for (var msg : conv.getMessages()) {
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
