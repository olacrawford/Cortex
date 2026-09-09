package com.cortex.permission;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RuleTest {

    @Test
    void 解析规则串() {
        Rule r1 = Rule.parse("Bash(git *)", true);
        assertEquals("Bash", r1.tool());
        assertEquals("git *", r1.pattern());
        assertTrue(r1.allow());

        Rule r2 = Rule.parse("Read", false);
        assertEquals("Read", r2.tool());
        assertEquals("", r2.pattern());
        assertFalse(r2.allow());

        Rule r3 = Rule.parse("Write(src/**)", false);
        assertEquals("Write", r3.tool());
        assertEquals("src/**", r3.pattern());
    }

    @Test
    void 非法规则串抛RuleParseException() {
        assertThrows(Rule.RuleParseException.class, () -> Rule.parse("", true));
        assertThrows(Rule.RuleParseException.class, () -> Rule.parse("Bash(git", true));
        assertThrows(Rule.RuleParseException.class, () -> Rule.parse("(x)", true));
        assertThrows(Rule.RuleParseException.class, () -> Rule.parse(null, true));
        // 阶段11 F4：模式编译失败也在 parse 期暴露
        assertThrows(Rule.RuleParseException.class, () -> Rule.parse("Bash(~[invalid)", true));
    }

    @Test
    void matches_向后兼容glob() {
        assertTrue(Rule.parse("Bash(git *)", true).matches("git status", true));
        assertFalse(Rule.parse("Bash(git *)", true).matches("npm i", true));
        assertTrue(Rule.parse("Write(src/**)", false).matches("src/a/B.java", false));
        assertFalse(Rule.parse("Write(src/**)", false).matches("docs/x", false));
        assertTrue(Rule.parse("Read", true).matches("任意目标", false), "空模式=全匹配");
    }

    @Test
    void matches_精确与正则与反向() {
        // 精确：整串相等（AC1 语义）
        Rule exact = Rule.parse("Bash(=git status)", true);
        assertTrue(exact.matches("git status", true));
        assertFalse(exact.matches("git status -s", true));
        // 正则（AC2 语义）
        Rule regex = Rule.parse("Bash(~^npm (install|test)$)", true);
        assertTrue(regex.matches("npm install", true));
        assertFalse(regex.matches("npm run dev", true));
        // 反向正则（AC3 语义）
        Rule notRegex = Rule.parse("Bash(!~^rm)", true);
        assertFalse(notRegex.matches("rm -rf .", true), "以 rm 起头不命中");
        assertTrue(notRegex.matches("ls -lh", true), "不以 rm 起头命中");
        // 嵌套反向精确
        Rule notExact = Rule.parse("Bash(!=git status)", true);
        assertFalse(notExact.matches("git status", true));
        assertTrue(notExact.matches("git status -s", true));
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
                List.of(Rule.parse("Bash(git *)", true)),
                List.of(Rule.parse("Bash(git push)", false)));
        assertEquals(Optional.of(Decision.ALLOW), layer.match("Bash", "git status", true));
        assertEquals(Optional.of(Decision.DENY), layer.match("Bash", "git push", true)); // 双双命中 deny 优先
        assertEquals(Optional.empty(), layer.match("Bash", "ls", true));
    }
}
