package com.cortex.permission;

/**
 * 结构化匹配器（阶段11 F1）：权限规则与 Hook 条件共用的四种匹配语义。
 * 由 {@link Matchers#compile} 从规则串构造；加载期编译失败立即抛 {@link MatcherCompileException}。
 */
public sealed interface Matcher permits Matcher.ExactMatcher, Matcher.GlobMatcher,
        Matcher.RegexMatcher, Matcher.NotMatcher {

    /** 对目标串求值。 */
    boolean match(String s);

    /** 人类可读的匹配器描述（stderr 日志用）。 */
    String describe();

    /** 精确匹配：整串相等（F3）。 */
    record ExactMatcher(String value) implements Matcher {
        @Override
        public boolean match(String s) {
            return s != null && s.equals(value);
        }

        @Override
        public String describe() {
            return "exact:" + value;
        }
    }

    /**
     * glob 匹配（缺省语义，向后兼容）：{@code command=true} 走命令 glob（* 匹配任意字符、** 等价 *），
     * 否则走路径 glob（按 / 分段，* 段内、** 跨段）。
     */
    record GlobMatcher(String pattern, boolean command) implements Matcher {
        @Override
        public boolean match(String s) {
            return Rule.matchPattern(pattern, s == null ? "" : s, command);
        }

        @Override
        public String describe() {
            return "glob:" + pattern;
        }
    }

    /**
     * 正则匹配（F3）：{@code find()} 语义（子串命中即真）。
     * Pattern 在静态缓存中按 source 编译缓存，record 相等性只看 source——
     * 供「永久放行」规则去重等场景做值比较。
     */
    record RegexMatcher(String source) implements Matcher {
        @Override
        public boolean match(String s) {
            return Matchers.patternFor(source).matcher(s == null ? "" : s).find();
        }

        @Override
        public String describe() {
            return "regex:" + source;
        }
    }

    /** 反向匹配（F3）：对 inner 取反，inner 自身支持任意类型递归（如 {@code !=value}、{@code !~regex}、{@code !glob}）。 */
    record NotMatcher(Matcher inner) implements Matcher {
        @Override
        public boolean match(String s) {
            return !inner.match(s);
        }

        @Override
        public String describe() {
            return "not(" + inner.describe() + ")";
        }
    }
}
