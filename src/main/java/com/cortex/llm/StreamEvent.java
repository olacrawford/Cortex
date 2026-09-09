package com.cortex.llm;

public sealed interface StreamEvent {

    record TextDelta(String text) implements StreamEvent {}

    record ThinkingDelta(String text) implements StreamEvent {}

    /** 一次工具调用在流中拼接完成（工具名 + 完整 JSON 参数），Done 之前发出。 */
    record ToolCallComplete(String toolId, String toolName, String arguments) implements StreamEvent {}

    /** 本轮请求的 token 用量，流结束时一次性发出（Done 之前）。 */
    record UsageEvent(Usage usage) implements StreamEvent {}

    record StreamEnd(String stopReason, int inputTokens, int outputTokens) implements StreamEvent {}

    record Error(String message, Throwable cause) implements StreamEvent {
        /** 一般错误：无 cause。 */
        public Error(String message) {
            this(message, null);
        }
    }
}
