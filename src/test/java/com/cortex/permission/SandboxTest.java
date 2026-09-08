package com.cortex.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SandboxTest {

    @TempDir
    Path root;

    @Test
    void 项目内文件通过() throws Exception {
        Path inner = Files.writeString(root.resolve("a.txt"), "x");
        assertTrue(Sandbox.sandboxOK(Sandbox.resolveRoot(root), inner.toString()));
        assertTrue(Sandbox.sandboxOK(Sandbox.resolveRoot(root), "a.txt")); // 相对路径
        assertTrue(Sandbox.sandboxOK(Sandbox.resolveRoot(root), "")); // 空视为 root
    }

    @Test
    void 多级未创建中间目录的新建文件通过() throws Exception {
        // root 内新建文件，含尚未创建的多级中间目录（祖先回退分支）
        assertTrue(Sandbox.sandboxOK(Sandbox.resolveRoot(root), "a/b/c/new.txt"));
    }

    @Test
    void 项目外路径拒绝() throws Exception {
        Path resolved = Sandbox.resolveRoot(root);
        assertFalse(Sandbox.sandboxOK(resolved, "/etc/passwd"));
        assertFalse(Sandbox.sandboxOK(resolved, "../outside.txt"));
        assertFalse(Sandbox.sandboxOK(resolved, "/"));
    }

    @Test
    void 软链接指向项目外拒绝() throws Exception {
        Path resolved = Sandbox.resolveRoot(root);
        Path outside = Files.createTempDirectory("outside-root");
        Path link = root.resolve("evil-link");
        Files.createSymbolicLink(link, outside);
        assertFalse(Sandbox.sandboxOK(resolved, link.toString()), "软链接指向项目外必须拒绝");
        // 指向项目内的软链接通过
        Path innerTarget = Files.createDirectories(root.resolve("inner-dir"));
        Path goodLink = root.resolve("good-link");
        Files.createSymbolicLink(goodLink, innerTarget);
        assertTrue(Sandbox.sandboxOK(resolved, goodLink.toString()));
    }
}
