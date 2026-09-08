package com.cortex.mcp;

import com.cortex.tool.Tool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MCP 连接管理器（F9/F10/F11）：并发连接所有配置的 server（每个 30s 超时，失败仅跳过自身），
 * 把成功 server 的工具适配后缓存；程序退出时统一关闭（5s 总兜底，不卡死退出）。
 * 生产客户端用 SDK 的同步会话（requestTimeout=30s 承载每步超时）。
 */
public final class McpManager implements AutoCloseable {

    private static final PrintStream WARN = java.lang.System.err;

    /** 供单测临时调小的超时（volatile，生产常量 30s/5s）。 */
    static volatile long connectTimeoutSec = 30L;
    static volatile long closeTimeoutSec = 5L;

    private final Object lock = new Object();
    private final List<Session> sessions = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();

    private record Session(String name, McpSyncClient client) {}

    private McpManager() {}

    /**
     * 并发连接所有 server 并收集工具；阻塞直到所有连接尝试结束（成功/失败/超时）。
     * 单 server 失败只跳过自身（N1）。
     */
    public static McpManager start(McpConfig cfg, String version) throws InterruptedException {
        McpManager mgr = new McpManager();
        int count = cfg.servers().size();
        CountDownLatch latch = new CountDownLatch(count);
        for (var e : cfg.servers().entrySet()) {
            String name = e.getKey();
            ServerConfig srv = e.getValue();
            Thread.ofVirtual().name("mcp-connect-" + name).start(() -> {
                try {
                    connectOne(mgr, name, srv, version);
                } finally {
                    latch.countDown();
                }
            });
        }
        if (count > 0) {
            latch.await();
        }
        synchronized (mgr.lock) {
            mgr.tools.sort(Comparator.comparing(Tool::name));
        }
        return mgr;
    }

    private static void connectOne(McpManager mgr, String name, ServerConfig srv, String version) {
        McpSyncClient client = null;
        try {
            McpClient.SyncSpec spec = McpClient.sync(buildTransport(srv))
                    .requestTimeout(Duration.ofSeconds(connectTimeoutSec))
                    .clientInfo(new McpSchema.Implementation("cortex", version));
            client = spec.build();

            client.initialize(); // initialize 握手（超时由 requestTimeout 承载）
            List<McpSchema.Tool> remoteTools = client.listTools().tools();

            // CallerSession 适配：SDK 同步客户端 callTool(CallToolRequest)
            McpSyncClient finalClient = client;
            McpTool.CallerSession caller = (toolName, arguments) ->
                    finalClient.callTool(new McpSchema.CallToolRequest(toolName, arguments));

            List<Tool> adapted = new ArrayList<>();
            for (McpSchema.Tool t : remoteTools) {
                McpTool.adaptTool(name, t, caller).ifPresent(adapted::add);
            }
            synchronized (mgr.lock) {
                mgr.sessions.add(new Session(name, client));
                mgr.tools.addAll(adapted);
            }
            java.lang.System.err.printf("[mcp] server %s 已连接：%d 个工具%n", name, adapted.size());
        } catch (Exception e) {
            WARN.printf("[mcp] warn: connect server %s failed: %s%n", name, e.getMessage());
            if (client != null) {
                try {
                    client.closeGracefully(); // 连上但列工具失败的连接不泄漏
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 按 server 类型构造传输：stdio 子进程管道 / Streamable HTTP（headers 注入每次请求）。 */
    private static McpClientTransport buildTransport(ServerConfig srv) {
        if ("http".equals(srv.type())) {
            return HttpClientStreamableHttpTransport.builder(srv.url())
                    .customizeRequest(rb -> srv.headers().forEach(rb::header))
                    .build();
        }
        ServerParameters params = ServerParameters.builder(srv.command())
                .args(srv.args())
                .env(mergeOsEnv(srv.env()))
                .build();
        return new StdioClientTransport(params, McpJsonDefaults.getMapper());
    }

    /** 宿主环境 + server env（同名覆盖）合并。 */
    static Map<String, String> mergeOsEnv(Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        merged.putAll(extra);
        return merged;
    }

    /** 已适配成功的工具列表（按工具名稳定排序）。 */
    public List<Tool> tools() {
        synchronized (lock) {
            return List.copyOf(tools);
        }
    }

    /** 关闭所有会话：每会话并发关闭，5s 总兜底，超时即放弃（N7）。 */
    @Override
    public void close() {
        List<Session> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(sessions);
        }
        CountDownLatch done = new CountDownLatch(snapshot.size());
        for (Session s : snapshot) {
            Thread.ofVirtual().name("mcp-close-" + s.name()).start(() -> {
                try {
                    s.client().closeGracefully();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await(closeTimeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
