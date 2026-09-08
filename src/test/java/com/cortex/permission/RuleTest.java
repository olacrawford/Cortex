package com.cortex.permission;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RuleTest {

    @Test
    void 解析规则串() {
        Rule r1 = Rule.parse("Bash(git *)", true).orElseThrow();
        assertEquals("Bash", r1.tool());
        assertEquals("git *", r1.pattern());
        assertTrue(r1.allow());

        Rule r2 = Rule.parse("Read", false).orElseThrow();
        assertEquals("Read", r2.tool());
        assertEquals("", r2.pattern());
        assertFalse(r2.allow());

        Rule r3 = Rule.parse("Write(src/**)", false).orElseThrow();
        assertEquals("Write", r3.tool());
        assertEquals("src/**", r3.pattern());
    }

    @Test
    void 非法规则串返回空() {
        assertTrue(Rule.parse("", true).isEmpty());
        assertTrue(Rule.parse("Bash(git", true).isEmpty());
        assertTrue(Rule.parse("(x)", true).isEmpty());
        assertTrue(Rule.parse(null, true).isEmpty());
    }

    @Test
    void 命令glob匹配() {
        assertTrue(Rule.matchPattern("git *", "git status", true));
        assertTrue(Rule.matchPattern("git *", "git push origin main", true));
        assertFalse(Rule.matchPattern("git *", "npm i", true));
        assertTrue(Rule.matchPattern("git status", "git status", true)); // 精确
        assertFalse(Rule.matchPattern("git status", "git push", true));
        assertTrue(Rule.matchPattern("git **", "git status", true)); // ** 等价 *
        assertTrue(Rule.matchPattern("", "anything", true)); // 空=全匹配
    }

    @Test
    void 路径glob匹配() {
        assertTrue(Rule.matchPattern("src/**", "src/a/b.go", false)); // ** 跨段
        assertFalse(Rule.matchPattern("src/**", "docs/x", false));
        assertTrue(Rule.matchPattern("src/*", "src/A.java", false)); // * 单段
        assertFalse(Rule.matchPattern("src/*", "src/main/A.java", false));
        assertTrue(Rule.matchPattern("", "any/path", false));
    }

    @Test
    void 同层deny优先于allow() {
        RuleSet layer = new RuleSet(
                java.util.List.of(Rule.parse("Bash(git *)", true).orElseThrow()),
                java.util.List.of(Rule.parse("Bash(git push)", false).orElseThrow()));
        assertEquals(Optional.of(Decision.ALLOW), layer.match("Bash", "git status", true));
        assertEquals(Optional.of(Decision.DENY), layer.match("Bash", "git push", true)); // 双双命中 deny 优先
        assertEquals(Optional.empty(), layer.match("Bash", "ls", true));
    }
}
