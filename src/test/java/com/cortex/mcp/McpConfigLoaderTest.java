package com.cortex.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path projectDir;

    private Path init() throws Exception {
        projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".cortex"));
        // 把 user.home 指到临时目录，隔离用户级配置
        Path savedHome = Path.of(System.getProperty("user.home"));
        System.setProperty("user.home", tempDir.resolve("home").toString());
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.setProperty("user.home", savedHome.toString())));
        return projectDir;
    }

    private Path userFile() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        Files.createDirectories(home.resolve(".cortex"));
        return home.resolve(".cortex/mcp.yaml");
    }

    private Path projectFile() throws Exception {
        return projectDir.resolve(".cortex/mcp.yaml");
    }

    private McpConfig load() {
        return McpConfigLoader.loadConfig(projectDir);
    }

    @Test
    void 两文件缺失_得空配置且不抛() throws Exception {
        init();
        assertTrue(load().servers().isEmpty());
    }

    @Test
    void 同名server项目级完整覆盖用户级() throws Exception {
        init();
        Files.writeString(userFile(), """
                mcp_servers:
                  demo:
                    type: stdio
                    command: echo-user
                  other:
                    type: stdio
                    command: other-user
                """);
        Files.writeString(projectFile(), """
                mcp_servers:
                  demo:
                    type: stdio
                    command: echo-project
                """);
        McpConfig cfg = load();
        assertEquals(2, cfg.servers().size());
        assertEquals("echo-project", cfg.servers().get("demo").command()); // 项目级完整覆盖
        assertEquals("other-user", cfg.servers().get("other").command()); // 用户级独有保留
    }

    @Test
    void 非法YAML跳过该层不致启动失败() throws Exception {
        init();
        Files.writeString(userFile(), "{{{ 非法 :::");
        Files.writeString(projectFile(), """
                mcp_servers:
                  ok:
                    type: stdio
                    command: echo-ok
                """);
        McpConfig cfg = load();
        assertEquals(1, cfg.servers().size());
        assertEquals("echo-ok", cfg.servers().get("ok").command());
    }

    @Test
    void env值展开变量_command与args不展开() throws Exception {
        init();
        Files.writeString(projectFile(), """
                mcp_servers:
                  demo:
                    type: stdio
                    command: "run ${UNDEFINED_VAR_XYZ}"
                    args: ["a", "${HOME}"]
                    env:
                      TOKEN: "${HOME}/token"
                      MISSING: "${UNDEFINED_VAR_XYZ}"
                """);
        McpConfig cfg = load();
        ServerConfig srv = cfg.servers().get("demo");
        // command / args 不展开（保留字面量）
        assertEquals("run ${UNDEFINED_VAR_XYZ}", srv.command());
        assertEquals("${HOME}", srv.args().get(1));
        // env 的值展开：已定义变量取环境值
        String home = System.getenv("HOME");
        assertEquals(home + "/token", srv.env().get("TOKEN"));
        // 未定义变量展开为空串
        assertEquals("", srv.env().get("MISSING"));
    }

    @Test
    void type缺失或非法或必填字段缺失的server被跳过() throws Exception {
        init();
        Files.writeString(projectFile(), """
                mcp_servers:
                  no-type:
                    command: x
                  bad-type:
                    type: sse
                    url: "http://x"
                  no-command:
                    type: stdio
                  no-url:
                    type: http
                  good:
                    type: stdio
                    command: echo-ok
                """);
        McpConfig cfg = load();
        assertEquals(1, cfg.servers().size());
        assertTrue(cfg.servers().containsKey("good"));
    }
}
