package com.cortex.hook;

import com.cortex.agent.CancelToken;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class HookExecutorTest {

    private final HookExecutor executor = new HookExecutor();

    private static HookRule rule(Action action) {
        return new HookRule("t", Event.PRE_TOOL_USE, null, action, false, false,
                Duration.ofSeconds(5), "test");
    }

    private static Payload payload(String kvPairsJson) {
        try {
            return new Payload(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(kvPairsJson, Map.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── shell ───

    @Test
    void shell_拦截事件exit2时blocked且原因取stderr() {
        ExecutionResult r = executor.run(rule(new Action.Shell("echo blocked by hook >&2; exit 2")),
                payload("{\"event\":\"PreToolUse\"}"), true, null);
        assertTrue(r.blocked());
        assertEquals("blocked by hook", r.reason(), "stderr 去尾换行作为拒绝原因（F19）");
        assertNull(r.error());
    }

    @Test
    void shell_exit2但非拦截事件_按失败处理不拦截() {
        ExecutionResult r = executor.run(rule(new Action.Shell("echo x >&2; exit 2")),
                payload("{}"), false, null);
        assertFalse(r.blocked(), "非拦截事件下 exit 2 不表达拦截");
        assertNotNull(r.error(), "非拦截事件下按普通非零退出=hook 失败（F19 只为拦截事件定义 exit 2 语义）");
    }

    @Test
    void shell_exit0放行_stderr缺失时stdout兜底仍不拦截() {
        ExecutionResult r = executor.run(rule(new Action.Shell("echo to stdout; exit 2")),
                payload("{}"), true, null);
        assertTrue(r.blocked());
        assertEquals("to stdout", r.reason(), "stderr 为空时用 stdout（F19）");
    }

    @Test
    void shell_exit0_正常放行() {
        ExecutionResult r = executor.run(rule(new Action.Shell("true")), payload("{}"), true, null);
        assertFalse(r.blocked());
        assertNull(r.error());
    }

    @Test
    void shell_exit1非拦截信号_视为失败() {
        ExecutionResult r = executor.run(rule(new Action.Shell("echo boom >&2; exit 1")),
                payload("{}"), true, null);
        assertFalse(r.blocked(), "其它非零 exit 是失败不拦截（F19）");
        assertNotNull(r.error());
        assertTrue(r.error().getMessage().contains("exit 1"));
    }

    @Test
    void shell_stdin收到按字典序的payloadJSON() {
        // 把 stdin 原样转 stderr 并 exit 2 → 拦截 reason 即 stdin 内容，可断言 key 字典序（N6/F17）
        ExecutionResult r = executor.run(rule(new Action.Shell("cat >&2; exit 2")),
                payload("{\"zzz\":\"1\",\"event\":\"Stop\",\"abc\":\"2\"}"), true, null);
        assertTrue(r.blocked());
        assertTrue(r.reason().startsWith("{\"abc\""), "key 按字典序（N6）: " + r.reason());
        assertTrue(r.reason().contains("\"zzz\":\"1\""));
    }

    @Test
    void shell_超时按失败处理() {
        HookRule r = new HookRule("t", Event.STOP, null,
                new Action.Shell("sleep 2"), false, false, Duration.ofMillis(150), "test");
        ExecutionResult result = executor.run(r, payload("{}"), false, null);
        assertNotNull(result.error());
        assertTrue(result.error().getMessage().contains("timeout"));
    }

    // ─── prompt ───

    @Test
    void prompt_文本进入prompt字段且不拦截() {
        ExecutionResult r = executor.run(rule(new Action.Prompt("用 zh-CN 回复")), payload("{}"), true, null);
        assertEquals("用 zh-CN 回复", r.prompt());
        assertFalse(r.blocked(), "prompt 永不拦截（F22）");
    }

    // ─── http ───

    private record Server(HttpServer server, List<String> bodies) implements AutoCloseable {
        static Server start(String responsePath, int status, String responseBody) throws IOException {
            List<String> bodies = new CopyOnWriteArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                bodies.add(new String(body, StandardCharsets.UTF_8));
                byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, resp.length == 0 ? -1 : resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
            server.start();
            return new Server(server, bodies);
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void http_block决策触发拦截() throws IOException {
        try (Server s = Server.start("/", 200, "{\"decision\":\"block\",\"reason\":\"network policy\"}")) {
            ExecutionResult r = executor.run(
                    rule(new Action.Http(s.base() + "/check", "POST", Map.of(), null)),
                    payload("{\"tool_name\":\"bash\"}"), true, null);
            assertTrue(r.blocked());
            assertEquals("network policy", r.reason());
        }
    }

    @Test
    void http_非block决策与5xx按放行或失败() throws IOException {
        try (Server s = Server.start("/", 200, "{\"decision\":\"allow\"}")) {
            ExecutionResult r = executor.run(
                    rule(new Action.Http(s.base() + "/ok", "POST", Map.of(), null)),
                    payload("{}"), true, null);
            assertFalse(r.blocked());
            assertNull(r.error());
        }
        try (Server s = Server.start("/", 500, "boom")) {
            ExecutionResult r = executor.run(
                    rule(new Action.Http(s.base() + "/bad", "POST", Map.of(), null)),
                    payload("{}"), true, null);
            assertFalse(r.blocked(), "非 2xx 视为 hook 失败不拦截（F25）");
            assertNotNull(r.error());
        }
    }

    @Test
    void http_模板body渲染与缺省payload体() throws IOException {
        try (Server s = Server.start("/", 200, "{}")) {
            executor.run(rule(new Action.Http(s.base() + "/tpl", "POST", Map.of(), "tool=${tool_name}!")),
                    payload("{\"tool_name\":\"write_file\"}"), false, null);
            assertEquals("tool=write_file!", s.bodies().get(0), "${field} 模板渲染（F23/N10）");

            executor.run(rule(new Action.Http(s.base() + "/raw", "POST", Map.of(), null)),
                    payload("{\"event\":\"Stop\"}"), false, null);
            assertEquals("{\"event\":\"Stop\"}", s.bodies().get(1), "缺省 body 序列化 payload（F23）");
        }
    }

    // ─── subagent 占位 ───

    @Test
    void subagent_占位仅stderr不报错不拦截() {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.err;
        System.setErr(new java.io.PrintStream(buf, true));
        ExecutionResult r;
        try {
            r = executor.run(rule(new Action.Subagent("foo", "test")), payload("{}"), true, null);
        } finally {
            System.setErr(old);
        }
        assertFalse(r.blocked());
        assertNull(r.error());
        assertTrue(buf.toString().contains("[hook subagent] not yet implemented, skipped: foo"), "N8 固定格式");
    }

    // ─── 取消 ───

    @Test
    void shell_取消信号及时退出() {
        CancelToken cancel = new CancelToken();
        cancel.cancel();
        ExecutionResult r = executor.run(rule(new Action.Shell("sleep 5")),
                payload("{}"), false, cancel);
        assertNotNull(r.error());
    }
}
