package com.cortex.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String yaml) throws IOException {
        Path p = tempDir.resolve("config.yaml");
        Files.writeString(p, yaml);
        return p;
    }

    @Test
    void 加载合法配置() throws IOException {
        Path p = writeConfig("""
                providers:
                  - name: claude
                    protocol: anthropic
                    api_key: sk-ant-xxx
                    model: claude-sonnet-4-20250514
                    thinking: true
                """);
        AppConfig cfg = ConfigLoader.load(p.toString());
        assertEquals(1, cfg.getProviders().size());
        ProviderConfig pc = cfg.getProviders().get(0);
        assertEquals("claude", pc.getName());
        assertEquals("anthropic", pc.getProtocol());
        assertEquals("sk-ant-xxx", pc.getApiKey());
        assertEquals("claude-sonnet-4-20250514", pc.getModel());
        assertTrue(pc.isThinking());
    }

    @Test
    void 加载多provider() throws IOException {
        Path p = writeConfig("""
                providers:
                  - name: claude
                    protocol: anthropic
                    api_key: sk-ant-xxx
                    model: claude-sonnet-4-20250514
                  - name: deepseek
                    protocol: openai-compat
                    base_url: https://api.deepseek.com
                    api_key: sk-ds-xxx
                    model: deepseek-chat
                """);
        AppConfig cfg = ConfigLoader.load(p.toString());
        assertEquals(2, cfg.getProviders().size());
        assertEquals("deepseek", cfg.getProviders().get(1).getName());
        assertEquals("https://api.deepseek.com", cfg.getProviders().get(1).getBaseUrl());
    }

    @Test
    void 缺少apiKey抛出异常() throws IOException {
        Path p = writeConfig("""
                providers:
                  - name: claude
                    protocol: anthropic
                    model: claude-sonnet-4-20250514
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(p.toString()));
        assertTrue(e.getMessage().contains("api_key"));
    }

    @Test
    void 非法protocol抛出异常() throws IOException {
        Path p = writeConfig("""
                providers:
                  - name: bad
                    protocol: unknown
                    api_key: sk-xxx
                    model: m
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(p.toString()));
        assertTrue(e.getMessage().contains("protocol"));
    }

    @Test
    void 文件不存在抛出异常() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load("/nonexistent/path.yaml"));
        assertTrue(e.getMessage().contains("不存在"));
    }

    @Test
    void 空providers列表抛出异常() throws IOException {
        Path p = writeConfig("providers: []");
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(p.toString()));
        assertTrue(e.getMessage().contains("不能为空"));
    }

    @Test
    void 缺少providers字段抛出异常() throws IOException {
        Path p = writeConfig("foo: bar");
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(p.toString()));
        assertTrue(e.getMessage().contains("providers"));
    }
}