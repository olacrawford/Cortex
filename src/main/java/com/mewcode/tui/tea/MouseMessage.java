package com.mewcode.tui.tea;

/**
 * 鼠标消息（滚轮回看历史）。
 */
public record MouseMessage(int x, int y, String action) implements Message {
}
