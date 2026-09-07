package com.cortex.tui.tea;

/**
 * 窗口尺寸变化消息。
 */
public record WindowSizeMessage(int width, int height) implements Message {
}
