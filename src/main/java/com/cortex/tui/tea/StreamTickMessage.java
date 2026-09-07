package com.cortex.tui.tea;

/**
 * 流式期间由定时器派发的轮询消息，驱动 Model 从 Stream 队列取增量并刷新。
 */
public record StreamTickMessage() implements Message {
}
