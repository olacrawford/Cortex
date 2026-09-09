package com.cortex.tui.tea;

/**
 * 手动 {@code /compact} 完成后的系统提示消息：由后台虚拟线程投递到 UI 线程渲染。
 */
public record CompactNoticeMessage(String text) implements Message {}
