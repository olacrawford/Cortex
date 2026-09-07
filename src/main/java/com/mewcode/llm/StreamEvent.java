package com.mewcode.llm;

public sealed interface StreamEvent {

    record TextDelta(String text) implements StreamEvent {}

    record ThinkingDelta(String text) implements StreamEvent {}

    record StreamEnd(String stopReason, int inputTokens, int outputTokens) implements StreamEvent {}

    record Error(String message) implements StreamEvent {}
}