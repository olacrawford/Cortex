package com.cortex.command;

/**
 * 斜杠输入解析（F3/F5/F7）：纯粹字符串操作，无副作用。
 * 以 "/" 开头的输入绕过 LLM 由本地分发；其余（含空/纯空白）返回 isSlash=false 走正常对话路径。
 */
public final class Dispatch {

    private Dispatch() {}

    /**
     * @param name    命令名（小写、不带 "/"）；仅当 isSlash=true 且输入是干净的 "/name" 时非空
     * @param isSlash 输入是否为斜杠命令形态
     */
    public record Parsed(String name, boolean isSlash) {}

    /**
     * 解析规则：
     * <ul>
     *   <li>strip 后不以 "/" 开头（含空串）→ {@code ("", false)}</li>
     *   <li>仅为 "/" → {@code ("", true)}（lookup 必然 miss）</li>
     *   <li>"/name" 后还有非空白尾随字符（带参数）→ {@code ("", true)}，按未命中处理（F7）</li>
     *   <li>否则 → {@code ("name", true)}，名字大小写不敏感</li>
     * </ul>
     */
    public static Parsed parse(String input) {
        String text = input == null ? "" : input.strip();
        if (!text.startsWith("/")) {
            return new Parsed("", false);
        }
        String body = text.substring(1);
        String[] parts = body.split("\\s+", 2);
        String name = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].strip() : "";
        if (name.isEmpty() || !rest.isEmpty()) {
            return new Parsed("", true);
        }
        return new Parsed(name, true);
    }
}
