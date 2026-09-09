package com.cortex.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 四种匹配类型的语义与边界（阶段11 F1-F3 / N7）。 */
class MatchersTest {

    // ─── exact ───

    @Test
    void exact_整串相等() {
        Matcher m = Matchers.compile("=git status", true);
        assertTrue(m instanceof Matcher.ExactMatcher);
        assertTrue(m.match("git status"));
        assertFalse(m.match("git status -s"));
        assertFalse(m.match("git Status"));
        assertFalse(m.match(""));
    }

    @Test
    void exact_空串值与空目标() {
        // "=…" 后面的空 value：compile 拒绝，空串匹配语义无意义
        assertThrows(Matchers.MatcherCompileException.class, () -> Matchers.compile("=", true));
        assertFalse(Matchers.compile("=x", true).match(null));
    }

    // ─── glob ───

    @Test
    void glob_命令语义_缺省类型() {
        Matcher m = Matchers.compile("git *", true);
        assertTrue(m instanceof Matcher.GlobMatcher);
        assertTrue(m.match("git status"));
        assertTrue(m.match("git push origin main"));
        assertFalse(m.match("npm i"));
    }

    @Test
    void glob_路径语义_单星不跨段() {
        Matcher m = Matchers.compile("**/*.java", false);
        assertTrue(m.match("src/A.java"));
        assertTrue(m.match("src/deep/A.java"));
        assertFalse(m.match("src/A.kt"));
    }

    @Test
    void glob_转义字面() {
        assertTrue(Rule.matchPattern("git\\*tag", "git*tag", true));
        assertFalse(Rule.matchPattern("git\\*tag", "gitXtag", true));
    }

    // ─── regex ───

    @Test
    void regex_find语义() {
        Matcher m = Matchers.compile("~^npm (install|test)$", true);
        assertTrue(m instanceof Matcher.RegexMatcher);
        assertTrue(m.match("npm install"));
        assertTrue(m.match("npm test"));
        assertFalse(m.match("npm run dev"));
        // find 语义：无锚点时子串命中即真
        assertTrue(Matchers.compile("~delete", false).match("please delete it"));
    }

    @Test
    void regex_编译失败抛MatcherCompileException() {
        Matchers.MatcherCompileException ex = assertThrows(
                Matchers.MatcherCompileException.class, () -> Matchers.compile("~[invalid", true));
        assertTrue(ex.getMessage().contains("invalid regex"));
    }

    @Test
    void regex_缓存复用同一Pattern实例() {
        assertSame(Matchers.patternFor("^abc"), Matchers.patternFor("^abc"), "F15：编译缓存复用");
    }

    // ─── not ───

    @Test
    void not_嵌套各类型() {
        assertTrue(Matchers.compile("!=foo", true).match("bar"));
        assertFalse(Matchers.compile("!=foo", true).match("foo"));

        assertTrue(Matchers.compile("!~^rm", true).match("ls -lh"), "AC3：不以 rm 起头命中");
        assertFalse(Matchers.compile("!~^rm", true).match("rm -rf ."));

        assertTrue(Matchers.compile("!git *", true).match("npm install"));
        assertFalse(Matchers.compile("!git *", true).match("git status"));

        // 双重否定回到正向
        Matcher notNot = Matchers.compile("!=foo", true);
        assertTrue(notNot.match("bar"));
    }

    @Test
    void not_缺inner抛异常() {
        Matchers.MatcherCompileException ex = assertThrows(
                Matchers.MatcherCompileException.class, () -> Matchers.compile("!", true));
        assertTrue(ex.getMessage().contains("inner"));
    }

    // ─── 空 pattern ───

    @Test
    void 空pattern抛异常_全匹配由Rule表达() {
        Matchers.MatcherCompileException e1 = assertThrows(
                Matchers.MatcherCompileException.class, () -> Matchers.compile("", true));
        assertEquals("empty matcher pattern", e1.getMessage());
        assertThrows(Matchers.MatcherCompileException.class, () -> Matchers.compile(null, true));
    }

    @Test
    void describe_人类可读() {
        assertEquals("exact:git status", Matchers.compile("=git status", true).describe());
        assertEquals("glob:git *", Matchers.compile("git *", true).describe());
        assertEquals("regex:^a", Matchers.compile("~^a", true).describe());
        assertTrue(Matchers.compile("!=x", true).describe().startsWith("not("));
    }
}
