package com.cortex.llm;

public sealed interface StreamEvent {

    record TextDelta(String text) implements StreamEvent {}

    record ThinkingDelta(String text) implements StreamEvent {}

    /** 一次工具调用在流中拼接完成（工具名 + 完整 JSON 参数），Done 之前发出。 */
    record ToolCallComplete(String toolId, String toolName, String arguments) implements StreamEvent {}

    record StreamEnd(String stopReason, int inputTokens, int outputTokens) implements StreamEvent {}

    record Error(String message) implements StreamEvent {}
}
