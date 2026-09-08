package com.cortex.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentTest {

    @TempDir
    Path tempDir;

    @Test
    void 非git目录降级为空且不抛异常() {
        String status = Environment.gitStatus(tempDir); // 临时目录不是 git 仓库
        assertEquals("", status);
    }

    @Test
    void git目录能取到状态摘要() {
        String status = Environment.gitStatus(Path.of(System.getProperty("user.dir"))); // 测试运行于 git 仓库内
        assertFalse(status.isEmpty());
    }

    @Test
    void render含环境各项且空项省略() {
        Environment env = new Environment(
                "/tmp/work", "Mac OS X", "2026-09-08", "3 个文件改动", "0.1.0", "test-model");
        String text = env.render();
        assertTrue(text.startsWith("## 环境信息"));
        assertTrue(text.contains("工作目录: /tmp/work"));
        assertTrue(text.contains("平台: Mac OS X"));
        assertTrue(text.contains("日期: 2026-09-08"));
        assertTrue(text.contains("git 状态: 3 个文件改动"));
        assertTrue(text.contains("模型: test-model"));

        Environment minimal = new Environment("", "", "", "", "", "");
        assertFalse(minimal.render().contains("工作目录:"));
    }

    @Test
    void gather填充基本项() {
        Environment env = Environment.gather("dev", "m");
        assertFalse(env.workingDir().isEmpty());
        assertFalse(env.platform().isEmpty());
        assertFalse(env.date().isEmpty());
        assertEquals("dev", env.version());
        assertEquals("m", env.model());
    }
}
