package com.cortex.worktree;

/**
 * Worktree 名字（slug）安全校验（F1/G2）：防 LLM 输入触发路径遍历。
 * 规则——非空、总长 ≤ 64、按 {@code /} 切段后每段匹配 {@code [a-zA-Z0-9._-]+}
 * 且不能是 {@code .} / {@code ..}、无连续 {@code //}、无首末 {@code /}。
 */
public final class WorktreeSlug {

    private static final int MAX_LENGTH = 64;

    private WorktreeSlug() {}

    /** 校验不通过抛 {@link IllegalArgumentException}（带具体原因，中文）。 */
    public static void validate(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("worktree 名称不能为空");
        }
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("worktree 名称超长（> " + MAX_LENGTH + " 字符）: " + abbreviate(name));
        }
        if (name.startsWith("/") || name.endsWith("/")) {
            throw new IllegalArgumentException("worktree 名称不能以 / 开头或结尾: " + abbreviate(name));
        }
        if (name.contains("//")) {
            throw new IllegalArgumentException("worktree 名称不能包含连续 //: " + abbreviate(name));
        }
        String[] segments = name.split("/");
        for (String seg : segments) {
            if (seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("worktree 名称的路径段不允许 . 或 ..: " + abbreviate(name));
            }
            if (!seg.matches("[a-zA-Z0-9._-]+")) {
                throw new IllegalArgumentException("worktree 名称只能包含字母/数字/点/下划线/连字符: " + abbreviate(name));
            }
        }
    }

    /** 嵌套 slug 的 {@code /} 替换为 {@code +}，避免 Git 同级 D/F 冲突（G3）。 */
    public static String flatten(String name) {
        return name.replace("/", "+");
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? "\"" + s + "\"" : "\"" + s.substring(0, 40) + "…\"";
    }
}
