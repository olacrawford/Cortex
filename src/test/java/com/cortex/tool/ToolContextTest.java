package com.cortex.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** ToolContext（F16）：withCwd/cwd/resolvePath 三方法。 */
class ToolContextTest {

    @TempDir
    Path tmp;

    @Test
    void 空ctx回落JVM当前目录() {
        assertTrue(ToolContext.EMPTY.cwd().isEmpty());
        assertEquals(Path.of("").toAbsolutePath(), ToolContext.EMPTY.resolvePath(""));
        assertEquals(Path.of("").toAbsolutePath().resolve("a.txt"), ToolContext.EMPTY.resolvePath("a.txt"));
    }

    @Test
    void withCwd后相对路径以cwd为基准() {
        ToolContext ctx = ToolContext.EMPTY.withCwd(tmp);
        assertEquals(tmp.resolve("a.txt"), ctx.resolvePath("a.txt"));
        assertEquals(tmp.resolve("sub").resolve("b.txt"), ctx.resolvePath("sub/b.txt"));
        assertTrue(ctx.resolvePath("").equals(tmp));
        assertTrue(ctx.cwd().isPresent());
    }

    @Test
    void 绝对路径原样返回() {
        ToolContext ctx = ToolContext.EMPTY.withCwd(tmp);
        Path abs = tmp.resolve("x").toAbsolutePath();
        assertEquals(abs, ctx.resolvePath(abs.toString()));
    }

    @Test
    void null与空串等价_返回基准目录() {
        ToolContext ctx = ToolContext.EMPTY.withCwd(tmp);
        assertEquals(ctx.resolvePath(""), ctx.resolvePath(null));
    }

    @Test
    void normalize生效() {
        ToolContext ctx = ToolContext.EMPTY.withCwd(tmp);
        Path p = ctx.resolvePath("a/../b.txt");
        assertEquals(tmp.resolve("b.txt"), p);
    }
}
