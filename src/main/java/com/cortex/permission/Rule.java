package com.cortex.permission;

import java.util.Optional;

/**
 * 单条权限规则（F3）：以「工具名(模式)」声明，如 {@code Bash(git *)}、{@code Write(src/**)}；
 * 不带模式段（如 {@code Read}）表示匹配该工具的全部调用。
 * 工具名用面向用户的友好名（Bash/Read/Write/Edit/Glob/Grep）。
 *
 * @param tool    友好名
 * @param pattern 模式段；空串表示匹配该工具全部调用
 * @param allow   true=allow，false=deny
 */
public record Rule(String tool, String pattern, boolean allow) {

    /**
     * 解析规则串；非法（空、括号不配对）返回 empty（调用方降级跳过，N5）。
     */
    public static Optional<Rule> parse(String s, boolean allow) {
        if (s == null || s.isBlank()) {
            return Optional.empty();
        }
        String text = s.strip();
        int open = text.indexOf('(');
        if (open < 0) {
            // 无模式段：匹配该工具全部调用
            return text.isEmpty() ? Optional.empty() : Optional.of(new Rule(text, "", allow));
        }
        if (!text.endsWith(")")) {
            return Optional.empty();
        }
        String tool = text.substring(0, open).strip();
        String pattern = text.substring(open + 1, text.length() - 1);
        if (tool.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Rule(tool, pattern, allow));
    }

    /**
     * glob 匹配（F3）：{@code pattern.isEmpty()} 恒匹配；
     * 命令串走「命令 glob」（* 匹配任意字符含空格，** 等价 *）；
     * 文件路径按 / 分段（* 段内、** 跨段）。
     */
    public static boolean matchPattern(String pattern, String target, boolean isCommand) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        if (target == null) {
            target = "";
        }
        return isCommand
                ? matchCommandGlob(pattern, target)
                : matchPathGlob(pattern.split("/"), 0, target.split("/"), 0);
    }

    /** 命令 glob：把 ** 折叠为 * 后单星匹配（支持 \x 字面转义）。 */
    private static boolean matchCommandGlob(String pattern, String command) {
        String collapsed = pattern.replace("**", "*");
        return matchStars(collapsed, 0, command, 0);
    }

    private static boolean matchStars(String pat, int pi, String s, int si) {
        if (pi == pat.length()) {
            return si == s.length();
        }
        char c = pat.charAt(pi);
        if (c == '\\' && pi + 1 < pat.length()) {
            // 反斜杠转义：下一个字符按字面匹配（永久放行的精确规则靠它防泛化）
            if (si >= s.length() || s.charAt(si) != pat.charAt(pi + 1)) {
                return false;
            }
            return matchStars(pat, pi + 2, s, si + 1);
        }
        if (c == '*') {
            for (int i = si; i <= s.length(); i++) {
                if (matchStars(pat, pi + 1, s, i)) {
                    return true;
                }
            }
            return false;
        }
        if (si >= s.length() || c != s.charAt(si)) {
            return false;
        }
        return matchStars(pat, pi + 1, s, si + 1);
    }

    /** 路径 glob：** 匹配零个或多个目录段，* 匹配段内任意字符，其余段字面。 */
    private static boolean matchPathGlob(String[] pat, int pi, String[] seg, int si) {
        if (pi == pat.length) {
            return si == seg.length;
        }
        if (pat[pi].equals("**")) {
            for (int i = si; i <= seg.length; i++) {
                if (matchPathGlob(pat, pi + 1, seg, i)) {
                    return true;
                }
            }
            return false;
        }
        if (si >= seg.length) {
            return false;
        }
        return matchStars(pat[pi], 0, seg[si], 0) && matchPathGlob(pat, pi + 1, seg, si + 1);
    }
}
