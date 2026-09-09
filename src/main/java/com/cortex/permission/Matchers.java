package com.cortex.permission;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 匹配器编译工厂（F2/F3）：把规则串的模式段解析成 {@link Matcher}。
 * <ul>
 *   <li>{@code =value} → 精确（整串相等）</li>
 *   <li>{@code ~regex} → 正则（加载期编译，失败抛 {@link MatcherCompileException}）</li>
 *   <li>{@code !inner} → 反向包装（inner 递归解析，支持 {@code !=value} / {@code !~regex} / {@code !glob}）</li>
 *   <li>其它 → glob（缺省语义，向后兼容）</li>
 * </ul>
 * 编译结果（含已编译 Pattern）在静态缓存中复用，运行期匹配零重编译（F15）。
 */
public final class Matchers {

    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    /** 匹配器或规则串编译失败（F4：调用方输出 stderr 后跳过该条）。 */
    public static final class MatcherCompileException extends RuntimeException {
        public MatcherCompileException(String message) {
            super(message);
        }

        public MatcherCompileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private Matchers() {}

    /**
     * 编译模式段；空串抛异常（调用方对「全匹配」语义自行用 null matcher 表达）。
     *
     * @param command true=命令 glob 语义（* 跨任意字符）；false=路径 glob 语义（* 段内）
     */
    public static Matcher compile(String pattern, boolean command) {
        if (pattern == null || pattern.isEmpty()) {
            throw new MatcherCompileException("empty matcher pattern");
        }
        char head = pattern.charAt(0);
        switch (head) {
            case '=':
                if (pattern.length() == 1) {
                    throw new MatcherCompileException("exact matcher requires a value: " + pattern);
                }
                return new Matcher.ExactMatcher(pattern.substring(1));
            case '~':
                if (pattern.length() == 1) {
                    throw new MatcherCompileException("regex matcher requires a pattern: " + pattern);
                }
                String regex = pattern.substring(1);
                try {
                    patternFor(regex);
                } catch (PatternSyntaxException e) {
                    throw new MatcherCompileException("invalid regex: " + regex, e);
                }
                return new Matcher.RegexMatcher(regex);
            case '!':
                if (pattern.length() == 1) {
                    throw new MatcherCompileException("not matcher requires an inner pattern: " + pattern);
                }
                return new Matcher.NotMatcher(compile(pattern.substring(1), command));
            default:
                return new Matcher.GlobMatcher(pattern, command);
        }
    }

    /** 取（并缓存）已编译 Pattern；正则匹配运行期复用（F15）。 */
    static Pattern patternFor(String regex) {
        Pattern p = PATTERN_CACHE.get(regex);
        if (p == null) {
            p = Pattern.compile(regex);
            PATTERN_CACHE.put(regex, p);
        }
        return p;
    }
}
