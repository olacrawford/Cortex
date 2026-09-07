package com.mewcode.tui.tea;

/**
 * 按键消息。
 *
 * @param key   归一化按键名，如 "enter"、"up"、"down"、"backspace"、"ctrl+c"、"alt+enter"；对可打印字符则为该字符本身。
 * @param runes 该按键携带的字符（用于输入框追加），可为空数组。
 */
public record KeyPressMessage(String key, char[] runes) implements Message {
}
