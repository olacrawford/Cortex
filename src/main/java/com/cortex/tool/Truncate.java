package com.cortex.tool;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 结果体量控制（N5）：按行数与字节截断，超出在尾部加 [truncated] 标注。
 */
public final class Truncate {

    private Truncate() {}

    /**
     * 超过 maxLines 行或 maxBytes 字节时截断，尾部追加换行 + [truncated]。
     */
    public static String byLinesAndBytes(String s, int maxLines, int maxBytes) {
        boolean truncated = false;
        String[] lines = s.split("\n", -1);
        if (lines.length > maxLines) {
            lines = Arrays.copyOf(lines, maxLines);
            truncated = true;
        }
        String joined = String.join("\n", lines);
        byte[] bytes = joined.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            joined = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
            truncated = true;
        }
        return truncated ? joined + "\n[truncated]" : joined;
    }
}
