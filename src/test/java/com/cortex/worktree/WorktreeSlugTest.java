package com.cortex.worktree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Slug 校验（F1/G2/AC1）。 */
class WorktreeSlugTest {

    @Test
    void 合法slug通过() {
        for (String ok : new String[]{"alice", "team/alice", "v1.0", "a_b", "a-b/c_d.e", "A1"}) {
            assertDoesNotThrow(() -> WorktreeSlug.validate(ok), "应通过: " + ok);
        }
    }

    @Test
    void 非法slug被拒() {
        for (String bad : new String[]{"", "   ", null, "../etc", "..", "./x", "a//b", "/x", "a/",
                "a b", "a;b", "a|b", "x".repeat(65)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> WorktreeSlug.validate(bad), "应拒绝: " + bad);
            assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
        }
    }

    @Test
    void 拒绝原因是路径遍历时给出明确文案() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorktreeSlug.validate("../etc"));
        assertTrue(e.getMessage().contains(".."));
    }

    @Test
    void flatten把斜杠换加号() {
        assertEquals("alice", WorktreeSlug.flatten("alice"));
        assertEquals("team+alice", WorktreeSlug.flatten("team/alice"));
        assertEquals("a+b+c", WorktreeSlug.flatten("a/b/c"));
    }
}
